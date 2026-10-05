package dev.mokouliszt.githubstars

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class CodexException(message: String, val needLogin: Boolean = false) : Exception(message)

/**
 * Drives the official Codex app-server (bundled as libcodex_app_server.so) over JSON-RPC on stdio.
 *
 * Nothing in this app talks to ChatGPT's backend directly: login, token refresh and model calls
 * are all performed by the Codex binary itself. This class only starts the process, gives it a
 * working network environment on Android, and speaks the documented app-server protocol
 * (initialize, account methods, thread/start, turn/start).
 */
object CodexRuntime {

    private const val CLIENT_NAME = "github_stars"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val writeLock = Any()
    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val _events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 512)
    val events: SharedFlow<JSONObject> = _events

    private val proxy by lazy { LocalProxy() }
    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    private val stderrTail = ArrayDeque<String>()

    /** The build shipped in the APK. */
    fun bundled(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libcodex_app_server.so")

    fun isBundled(ctx: Context) = bundled(ctx).isFile

    val isRunning: Boolean get() = process?.isAlive == true

    /** Version of the running process (or of the one that will start next). */
    @Volatile var runningVersion: String = ""
        private set

    private val busy = java.util.concurrent.atomic.AtomicInteger(0)

    /** Model Codex actually used for the most recent thread (its default unless overridden). */
    @Volatile var lastModel: String = ""
        private set

    /** Starts the app-server if needed and completes the initialize handshake. */
    suspend fun ensure(ctx: Context) = lock.withLock {
        if (process?.isAlive == true) return@withLock
        val app = ctx.applicationContext
        val version = CodexUpdater.activeVersion(app)
        try {
            start(app, CodexUpdater.activeBinary(app), version)
        } catch (e: Exception) {
            if (version == CodexUpdater.bundledVersion) throw e
            // A downloaded build that cannot start is retired; fall back to the bundled one.
            stop()
            CodexUpdater.markBad(app, version)
            start(app, bundled(app), CodexUpdater.bundledVersion)
        }
    }

    /** Restarts on the next request so a freshly installed build is picked up. */
    fun restartIfIdle(): Boolean {
        if (busy.get() > 0 || process == null) return false
        stop()
        return true
    }

    private suspend fun start(ctx: Context, bin: File, version: String) = withContext(Dispatchers.IO) {
        if (!bin.isFile) throw CodexException(I18n.t("Codex本体が見つかりません", "The Codex binary is missing"))
        runningVersion = version
        val home = File(ctx.filesDir, "codex").apply { mkdirs() }
        val work = File(ctx.filesDir, "codex-work").apply { mkdirs() }
        val userHome = File(ctx.filesDir, "home").apply { mkdirs() }
        val ca = writeCaBundle(home)
        val proxyUrl = proxy.url()

        val pb = ProcessBuilder(
            bin.absolutePath, "--listen", "stdio://",
            // Background plugin/app catalogue sync is not needed here and only costs traffic.
            "-c", "features.plugins=false",
            "-c", "features.apps=false",
        ).directory(work)
        pb.environment().apply {
            put("HOME", userHome.absolutePath)
            put("CODEX_HOME", home.absolutePath)
            put("TMPDIR", ctx.cacheDir.absolutePath)
            put("LANG", "C.UTF-8")
            put("RUST_LOG", "warn")
            put("HTTPS_PROXY", proxyUrl)
            put("HTTP_PROXY", proxyUrl)
            put("ALL_PROXY", proxyUrl)
            put("NO_PROXY", "localhost,127.0.0.1,::1")
            put("SSL_CERT_FILE", ca.absolutePath)
            put("CODEX_CA_CERTIFICATE", ca.absolutePath)
        }
        val p = pb.start()
        process = p
        writer = BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8))

        Thread({
            runCatching {
                p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line -> handleLine(line) }
            }
            onExit(p)
        }, "codex-stdout").apply { isDaemon = true }.start()
        Thread({
            runCatching {
                p.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    synchronized(stderrTail) {
                        stderrTail.addLast(line.replace(Regex("\u001B\\[[0-9;]*m"), ""))
                        while (stderrTail.size > 40) stderrTail.removeFirst()
                    }
                }
            }
        }, "codex-stderr").apply { isDaemon = true }.start()

        val init = JSONObject()
            .put("clientInfo", JSONObject()
                .put("name", CLIENT_NAME)
                .put("title", "GitHub Stars")
                .put("version", BuildConfig.VERSION_NAME))
        requestRaw("initialize", init, 30_000)
        notify("initialized", null)
    }

    private fun onExit(p: Process) {
        if (process === p) {
            process = null
            writer = null
        }
        val reason = CodexException(I18n.t("Codexが終了しました", "Codex stopped") + lastError().let { if (it.isBlank()) "" else ": $it" })
        pending.values.forEach { it.completeExceptionally(reason) }
        pending.clear()
        _events.tryEmit(JSONObject().put("method", "local/exited"))
    }

    fun lastError(): String = synchronized(stderrTail) {
        stderrTail.lastOrNull { it.contains("ERROR") || it.contains("error") }?.take(300) ?: ""
    }

    fun stop() {
        process?.destroy()
        process = null
    }

    // ---------------------------------------------------------------- JSON-RPC

    private fun handleLine(line: String) {
        val msg = runCatching { JSONObject(line) }.getOrNull() ?: return
        val method = msg.optString("method", "")
        if (msg.has("id") && method.isEmpty()) {
            val id = msg.optLong("id", -1)
            pending.remove(id)?.complete(msg)
            return
        }
        if (method.isNotEmpty() && msg.has("id")) {
            answerServerRequest(msg)
            return
        }
        if (method.isNotEmpty()) _events.tryEmit(msg)
    }

    /**
     * The app runs Codex read-only with approvals disabled, so these should not arrive.
     * If one does, decline it rather than leave the turn hanging.
     */
    private fun answerServerRequest(msg: JSONObject) {
        val method = msg.optString("method")
        val reply = JSONObject().put("id", msg.get("id"))
        if (method.contains("Approval", ignoreCase = true) || method.endsWith("requestApproval")) {
            reply.put("result", JSONObject().put("decision", "decline"))
        } else {
            reply.put("error", JSONObject().put("code", -32601).put("message", "not supported by this client"))
        }
        write(reply)
    }

    private fun write(obj: JSONObject) {
        val w = writer ?: throw CodexException(I18n.t("Codexが起動していません", "Codex is not running"))
        synchronized(writeLock) {
            w.write(obj.toString())
            w.write("\n")
            w.flush()
        }
    }

    private fun notify(method: String, params: JSONObject?) {
        val o = JSONObject().put("method", method)
        if (params != null) o.put("params", params)
        write(o)
    }

    private suspend fun requestRaw(method: String, params: JSONObject, timeoutMs: Long): JSONObject {
        val id = ids.getAndIncrement()
        val d = CompletableDeferred<JSONObject>()
        pending[id] = d
        try {
            write(JSONObject().put("id", id).put("method", method).put("params", params))
            val res = withTimeout(timeoutMs) { d.await() }
            res.optJSONObject("error")?.let { err ->
                val m = err.optString("message", I18n.t("Codexエラー", "Codex error"))
                throw CodexException(m, needLogin = m.contains("auth", true) && m.contains("login", true))
            }
            return res.optJSONObject("result") ?: JSONObject()
        } finally {
            pending.remove(id)
        }
    }

    suspend fun request(ctx: Context, method: String, params: JSONObject = JSONObject(), timeoutMs: Long = 60_000): JSONObject {
        ensure(ctx)
        return requestRaw(method, params, timeoutMs)
    }

    // ---------------------------------------------------------------- account

    /** Returns {email, planType} when signed in with ChatGPT, or null. */
    suspend fun account(ctx: Context): JSONObject? {
        val r = request(ctx, "account/read", JSONObject())
        val a = r.optJSONObject("account") ?: return null
        return if (a.optString("type") == "chatgpt") a else null
    }

    /** Begins ChatGPT sign-in. Returns the login response (authUrl or verificationUrl/userCode). */
    suspend fun startLogin(ctx: Context, deviceCode: Boolean): JSONObject =
        request(ctx, "account/login/start", JSONObject().put("type", if (deviceCode) "chatgptDeviceCode" else "chatgpt"))

    /** Suspends until Codex reports the login with [loginId] finished. */
    suspend fun awaitLogin(loginId: String, timeoutMs: Long = 15 * 60_000L): JSONObject {
        busy.incrementAndGet()
        try {
            return awaitLoginInner(timeoutMs)
        } finally {
            busy.decrementAndGet()
        }
    }

    private suspend fun awaitLoginInner(timeoutMs: Long): JSONObject =
        withTimeout(timeoutMs) {
            // Only one sign-in runs at a time, so any completion belongs to it.
            events.first {
                val m = it.optString("method")
                m == "account/login/completed" || m == "local/exited"
            }
        }.let {
            if (it.optString("method") == "local/exited") throw CodexException(I18n.t("Codexが終了しました", "Codex stopped"))
            it.optJSONObject("params") ?: JSONObject()
        }

    suspend fun cancelLogin(ctx: Context, loginId: String) {
        runCatching { request(ctx, "account/login/cancel", JSONObject().put("loginId", loginId), 10_000) }
    }

    suspend fun logout(ctx: Context) {
        request(ctx, "account/logout", JSONObject())
    }

    /**
     * Models Codex currently offers to this account: [{id, displayName, description, isDefault,
     * efforts[], defaultEffort}]. Nothing is hard-coded: the list (and each model's reasoning
     * efforts) comes from the running Codex build, so models added later appear automatically,
     * especially as the app keeps Codex itself up to date.
     */
    suspend fun models(ctx: Context): JSONArray {
        val out = JSONArray()
        var cursor: String? = null
        var pages = 0
        do {
            val params = JSONObject().put("limit", 100)
            if (cursor != null) params.put("cursor", cursor)
            val r = request(ctx, "model/list", params)
            val data = r.optJSONArray("data") ?: JSONArray()
            for (i in 0 until data.length()) {
                val m = data.optJSONObject(i) ?: continue
                if (m.optBoolean("hidden")) continue
                val efforts = JSONArray()
                val se = m.optJSONArray("supportedReasoningEfforts") ?: JSONArray()
                for (j in 0 until se.length()) {
                    val e = se.opt(j)
                    val v = if (e is JSONObject) e.optString("reasoningEffort") else e?.toString()
                    if (!v.isNullOrBlank()) efforts.put(v)
                }
                out.put(JSONObject()
                    .put("id", m.optString("model", m.optString("id")))
                    .put("displayName", m.optString("displayName", m.optString("id")))
                    .put("description", m.optString("description").takeUnless { it == "null" } ?: "")
                    .put("isDefault", m.optBoolean("isDefault"))
                    .put("defaultEffort", m.optString("defaultReasoningEffort").takeUnless { it == "null" } ?: "")
                    .put("efforts", efforts))
            }
            cursor = r.optString("nextCursor").takeUnless { it.isBlank() || it == "null" }
        } while (cursor != null && ++pages < 10)
        return out
    }

    // ---------------------------------------------------------------- turns

    /**
     * Runs one ephemeral, read-only turn and returns the final assistant message.
     * [outputSchema] constrains the final message to JSON matching the schema.
     */
    class TurnOptions(
        /** null = whatever Codex currently uses by default (follows Codex updates). */
        val model: String? = null,
        /** null = the model's default reasoning effort. */
        val effort: String? = null,
        /** read-only | workspace-write | danger-full-access */
        val sandbox: String = "workspace-write",
        val webSearch: Boolean = true,
    )

    suspend fun runTurn(
        ctx: Context,
        developerInstructions: String,
        prompt: String,
        options: TurnOptions,
        outputSchema: JSONObject?,
        timeoutMs: Long = 8 * 60_000L,
    ): String {
        busy.incrementAndGet()
        try {
            return runTurnInner(ctx, developerInstructions, prompt, options, outputSchema, timeoutMs)
        } finally {
            busy.decrementAndGet()
        }
    }

    private suspend fun runTurnInner(
        ctx: Context,
        developerInstructions: String,
        prompt: String,
        options: TurnOptions,
        outputSchema: JSONObject?,
        timeoutMs: Long,
    ): String {
        ensure(ctx)
        val work = File(ctx.filesDir, "codex-work").apply { mkdirs() }
        // Full-auto: approvals never asked, commands confined to the chosen sandbox, live web search.
        val threadParams = JSONObject()
            .put("ephemeral", true)
            .put("sandbox", options.sandbox)
            .put("approvalPolicy", "never")
            .put("cwd", work.absolutePath)
            .put("developerInstructions", developerInstructions)
            .put("config", JSONObject().put("web_search", if (options.webSearch) "live" else "disabled"))
        if (!options.model.isNullOrBlank()) threadParams.put("model", options.model)
        val thread = requestRaw("thread/start", threadParams, 60_000)
        val threadId = thread.optJSONObject("thread")?.optString("id")
            ?: throw CodexException(I18n.t("スレッドを開始できませんでした", "Could not start a Codex thread"))
        thread.optString("model").takeIf { it.isNotBlank() && it != "null" }?.let { lastModel = it }

        // Subscribe before starting the turn so no notification is missed.
        val inbox = Channel<JSONObject>(Channel.UNLIMITED)
        val collector = scope.launch {
            events.collect { ev ->
                val p = ev.optJSONObject("params")
                if (ev.optString("method") == "local/exited" || p?.optString("threadId") == threadId) inbox.send(ev)
            }
        }
        try {
            val turnParams = JSONObject()
                .put("threadId", threadId)
                .put("input", JSONArray().put(JSONObject()
                    .put("type", "text").put("text", prompt).put("text_elements", JSONArray())))
            if (!options.effort.isNullOrBlank()) turnParams.put("effort", options.effort)
            if (outputSchema != null) turnParams.put("outputSchema", outputSchema)
            requestRaw("turn/start", turnParams, 60_000)

            val deltas = StringBuilder()
            var finalText: String? = null
            return withTimeout(timeoutMs) {
                while (true) {
                    val ev = inbox.receive()
                    val p = ev.optJSONObject("params") ?: JSONObject()
                    when (ev.optString("method")) {
                        "item/agentMessage/delta" -> deltas.append(p.optString("delta"))
                        "item/completed" -> {
                            val item = p.optJSONObject("item")
                            if (item?.optString("type") == "agentMessage") finalText = item.optString("text")
                        }
                        "error" -> {
                            if (!p.optBoolean("willRetry")) {
                                val m = p.optJSONObject("error")?.optString("message") ?: I18n.t("Codexエラー", "Codex error")
                                throw CodexException(m, needLogin = isAuthError(m))
                            }
                        }
                        "turn/completed" -> {
                            val turn = p.optJSONObject("turn") ?: JSONObject()
                            when (turn.optString("status")) {
                                "completed" -> return@withTimeout (finalText ?: deltas.toString())
                                else -> {
                                    val m = turn.optJSONObject("error")?.optString("message")
                                        ?: I18n.t("処理が中断されました", "The turn was interrupted")
                                    throw CodexException(m, needLogin = isAuthError(m))
                                }
                            }
                        }
                        "local/exited" -> throw CodexException(I18n.t("Codexが終了しました: ", "Codex stopped: ") + lastError())
                    }
                }
                @Suppress("UNREACHABLE_CODE") ""
            }
        } finally {
            collector.cancel()
            inbox.close()
        }
    }

    private fun isAuthError(m: String) =
        m.contains("401") || m.contains("unauthorized", true) || m.contains("log in", true) || m.contains("login", true)

    // ---------------------------------------------------------------- TLS roots

    /**
     * Android keeps its trust anchors in AndroidCAStore rather than /etc/ssl, so they are exported
     * as a PEM bundle for Codex (SSL_CERT_FILE / CODEX_CA_CERTIFICATE).
     */
    private fun writeCaBundle(dir: File): File {
        val out = File(dir, "cacert.pem")
        val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val sb = StringBuilder()
        val now = System.currentTimeMillis()
        for (alias in ks.aliases()) {
            val cert = ks.getCertificate(alias) as? X509Certificate ?: continue
            if (cert.notAfter.time < now) continue
            val b64 = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
            sb.append("-----BEGIN CERTIFICATE-----\n")
            b64.chunked(64).forEach { sb.append(it).append('\n') }
            sb.append("-----END CERTIFICATE-----\n")
        }
        val tmp = File(dir, "cacert.pem.tmp")
        tmp.writeText(sb.toString())
        tmp.renameTo(out)
        return out
    }
}
