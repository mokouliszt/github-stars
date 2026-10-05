package dev.mokouliszt.githubstars

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var store: Store
    private lateinit var github: GitHub

    companion object {
        /** Work outlives the Activity (rotation, low-memory recreation, backgrounding). */
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val jobs = ConcurrentHashMap<String, Job>()
        @Volatile private var sink: ((String, JSONObject) -> Unit)? = null
        /** Latest progress per kind (sync / summarize / codexUpdate), replayed after recreation. */
        private val progressByKind = ConcurrentHashMap<String, JSONObject>()
        @Volatile private var codexLoginId: String? = null
        @Volatile private var startupChecked = false

        fun cancelAll() {
            val ids = jobs.keys.toList()
            ids.forEach { jobs.remove(it)?.cancel() }
            sink?.invoke("cancelled", JSONObject().put("count", ids.size))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val idleRelease = Runnable { HyperSearch.releaseIfIdle(60_000) }


    private val dark: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applySystemBars()

        store = Store(this)
        github = GitHub(this)
        I18n.lang = store.lang()

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this).apply {
            setBackgroundColor(if (dark) 0xFF0A0A0A.toInt() else 0xFFFAFAFA.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.textZoom = 100
            // The page draws its own light/dark theme; never let WebView darken it.
            @Suppress("DEPRECATION")
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
            }
            overScrollMode = View.OVER_SCROLL_NEVER
            addJavascriptInterface(Bridge(), "Native")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, req: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(req.url)

                override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                    val u = req.url
                    if (u.host == "appassets.androidplatform.net") return false
                    openExternal(u)
                    return true
                }

                override fun onPageFinished(v: WebView, url: String) {
                    pushInsets(); pushTheme()
                }
            }
            loadUrl("https://appassets.androidplatform.net/assets/webui/index.html")
        }
        setContentView(web)
        ViewCompat.setOnApplyWindowInsetsListener(web) { _, insets -> pushInsets(insets); insets }
        sink = { type, payload -> deliver(type, payload) }

        // Back closes open sheets / clears the search first; only then leaves the app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.__back?String(window.__back()):'false'") { r ->
                    if (r?.trim('"') != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        // targetSdk 28: on Android 13+ the system asks for the notification permission itself
        // the first time the progress notification channel is created.

        startupCodexCheck()
    }

    /** Checks openai/codex for a newer app-server once per app start (at most hourly). */
    private fun startupCodexCheck() {
        if (startupChecked) return
        startupChecked = true
        val ctx = applicationContext
        appScope.launch {
            val policy = store.settings().optString("codexAutoUpdate", "wifi")
            if (!CodexUpdater.shouldCheck(ctx)) return@launch
            val rel = runCatching { CodexUpdater.latest(ctx, github.validToken()) }.getOrNull() ?: return@launch
            if (!CodexUpdater.isNewer(ctx, rel)) {
                emit("codexUpdate", CodexUpdater.status(ctx)); return@launch
            }
            val auto = policy == "always" || (policy == "wifi" && !CodexUpdater.isMetered(ctx))
            if (auto) runCodexUpdate("codex-update", rel)
            else emit("codexUpdate", CodexUpdater.status(ctx).put("available", rel.version))
        }
    }

    private fun runCodexUpdate(reqId: String, known: CodexUpdater.Release? = null) =
        launch(reqId, I18n.t("Codexを更新中", "Updating Codex"), progressKind = "codexUpdate") {
            val ctx = applicationContext
            val rel = known ?: CodexUpdater.latest(ctx, github.validToken())
            if (!CodexUpdater.isNewer(ctx, rel)) {
                emit("codexUpdate", CodexUpdater.status(ctx).put("upToDate", true)); return@launch
            }
            val v = CodexUpdater.install(ctx, rel) { have, total ->
                if (total > 0) RequestService.progress(reqId, I18n.t("Codex ${rel.version} を取得中", "Downloading Codex ${rel.version}"),
                    (have / 1_048_576).toInt(), (total / 1_048_576).toInt())
                progress("codexUpdate", have, total) { put("version", rel.version) }
            }
            CodexRuntime.restartIfIdle()
            emit("codexUpdate", CodexUpdater.status(ctx).put("updated", v))
        }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBars()
        web.setBackgroundColor(if (dark) 0xFF0A0A0A.toInt() else 0xFFFAFAFA.toInt())
        pushTheme()
    }

    override fun onStart() {
        super.onStart()
        main.removeCallbacks(idleRelease)
    }

    override fun onStop() {
        super.onStop()
        // Laya holds several hundred MB; give it back if the app stays in the background.
        main.postDelayed(idleRelease, 90_000)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) HyperSearch.release()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            sink = null
            if (jobs.isEmpty() && isFinishing) CodexRuntime.stop()
        }
        super.onDestroy()
    }

    private fun applySystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    // ---------------------------------------------------------------- to JS

    private fun pushInsets(insets: WindowInsetsCompat? = null) {
        val i = insets ?: ViewCompat.getRootWindowInsets(web) ?: return
        val bars = i.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = i.getInsets(WindowInsetsCompat.Type.ime())
        val d = resources.displayMetrics.density
        emit("insets", JSONObject()
            .put("top", bars.top / d)
            .put("bottom", bars.bottom / d)
            .put("ime", (if (ime.bottom > 0) ime.bottom - bars.bottom else 0) / d))
    }

    private fun pushTheme() = emit("theme", JSONObject().put("dark", dark))

    private fun emit(type: String, payload: JSONObject) {
        (sink ?: return).invoke(type, payload)
    }

    /** Progress is tracked per kind so concurrent jobs never clear each other's display. */
    private fun progress(kind: String, done: Number, total: Number, extra: JSONObject.() -> Unit = {}) {
        val p = JSONObject().put("kind", kind).put("done", done).put("total", total).apply(extra)
        progressByKind[kind] = p
        emit("progress", p)
    }

    private fun progressEnd(kind: String) {
        progressByKind.remove(kind)
        emit("progressEnd", JSONObject().put("kind", kind))
    }

    private fun deliver(type: String, payload: JSONObject) {
        if (isDestroyed || isFinishing) return
        val json = payload.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        runOnUiThread {
            runCatching { web.evaluateJavascript("window.__native&&window.__native(\"$type\",$json)", null) }
        }
    }

    private fun req(id: String) = JSONObject().put("reqId", id)

    private fun fail(reqId: String, t: Throwable) {
        if (t is CancellationException) return
        val needGithub = (t as? GitHubException)?.needLogin == true
        val needCodex = (t as? CodexException)?.needLogin == true
        emit("error", req(reqId)
            .put("message", t.message ?: t.javaClass.simpleName)
            .put("needGithubLogin", needGithub)
            .put("needCodexLogin", needCodex))
    }

    /**
     * Runs [block] under the foreground service so it survives backgrounding.
     * [progressKind] is cleared when the job ends for any reason (done, failed, cancelled).
     */
    private fun launch(
        reqId: String,
        label: String,
        foreground: Boolean = true,
        progressKind: String? = null,
        block: suspend () -> Unit,
    ) {
        val app = applicationContext
        jobs.remove(reqId)?.cancel()
        if (foreground) RequestService.begin(app, reqId, label)
        jobs[reqId] = appScope.launch {
            try {
                block()
            } catch (t: Throwable) {
                fail(reqId, t)
            } finally {
                jobs.remove(reqId)
                if (progressKind != null) progressEnd(progressKind)
                if (foreground) RequestService.end(app, reqId)
                emit("done", req(reqId))
            }
        }
    }

    private fun openExternal(u: Uri) {
        runCatching {
            if (u.scheme == "https" || u.scheme == "http") {
                CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, u)
            } else startActivity(Intent(Intent.ACTION_VIEW, u))
        }
    }

    private fun bringToFront() {
        runCatching {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    // ---------------------------------------------------------------- operations

    /** The user's own OAuth App client ID, kept in settings.json across restarts. */
    private fun clientId(): String = store.settings().optString("githubClientId").trim()

    private fun stateJson(): JSONObject {
        val repos = store.repos()
        return JSONObject()
            .put("github", JSONObject().put("loggedIn", github.isLoggedIn).put("user", github.user()))
            .put("clientIdSet", clientId().isNotBlank())
            .put("codex", JSONObject()
                .put("bundled", CodexRuntime.isBundled(this))
                .put("update", CodexUpdater.status(this)))
            .put("settings", store.settings())
            .put("syncedAt", repos.optLong("syncedAt"))
            .put("repos", repos.optJSONArray("items") ?: JSONArray())
            .put("summaries", store.summaries())
            .put("model", HyperSearch.status(this))
            .put("progress", JSONObject().apply { progressByKind.forEach { (k, v) -> put(k, v) } })
            .put("running", JSONArray(jobs.keys.toList()))
            .put("version", BuildConfig.VERSION_NAME)
    }

    private fun runSync(reqId: String) = launch(reqId, I18n.t("スターを同期中", "Syncing stars"), progressKind = "sync") {
        progress("sync", 0, 0)
        val items = github.starred { n -> progress("sync", n, 0) }
        withContext(Dispatchers.IO) { store.saveRepos(items) }
        runCatching { github.refreshProfile() }
        emit("repos", req(reqId).put("syncedAt", System.currentTimeMillis()).put("items", items)
            .put("user", github.user()))
        // Summaries are only ever created when the user asks for them.
    }

    /** Repositories without a summary in the current app language. */
    private fun missingIds(): List<String> {
        val sums = store.summaries()
        val lang = store.lang()
        return store.repoMap().keys.filter { !store.hasSummary(sums, it, lang) }
    }

    /**
     * [bulk] runs (missing / all) own the "summarize" progress shown in the list and the sheet.
     * Single-repository runs from the detail sheet report only through their own request id,
     * so they can run alongside a bulk run without disturbing its progress.
     */
    private fun runSummarize(reqId: String, ids: List<String>, bulk: Boolean) {
        if (ids.isEmpty()) {
            emit("done", req(reqId)); return
        }
        val label = if (bulk) I18n.t("概要を作成中", "Creating summaries") else I18n.t("概要を作成中（個別）", "Creating a summary")
        launch(reqId, label, progressKind = if (bulk) "summarize" else null) {
            val result = Summarizer(this@MainActivity, store, github).run(
                ids,
                onSaved = { entries ->
                    val o = JSONObject()
                    entries.forEach { (k, v) -> o.put(k, v) }
                    emit("summaries", JSONObject().put("entries", o))
                },
                onNotice = { msg -> emit("notice", JSONObject().put("message", msg)) },
                onProgress = { p ->
                    if (bulk) {
                        RequestService.progress(reqId, label, p.done, p.total)
                        progress("summarize", p.done, p.total) { put("failed", p.failed) }
                    }
                },
            )
            emit("summarized", req(reqId).put("done", result.done).put("failed", result.failed).put("single", !bulk))
        }
    }

    private fun bulkRunning() = jobs.keys.any { it.startsWith("summarize-") }

    // ---------------------------------------------------------------- bridge

    inner class Bridge {

        @JavascriptInterface
        fun state(): String = stateJson().toString()

        @JavascriptInterface
        fun saveSettings(json: String): String {
            val s = store.saveSettings(runCatching { JSONObject(json) }.getOrElse { JSONObject() })
            I18n.lang = s.optString("lang")
            return s.toString()
        }

        /** Read synchronously at start-up so the first frame already uses the system theme. */
        @JavascriptInterface
        fun isDark(): Boolean = dark

        // ---- GitHub
        @JavascriptInterface
        fun githubLogin(reqId: String) = launch(reqId, I18n.t("GitHubの認可を待っています", "Waiting for GitHub authorization")) {
            val id = clientId()
            val dc = github.startDeviceFlow(id)
            emit("githubCode", req(reqId)
                .put("userCode", dc.userCode)
                .put("verificationUri", dc.verificationUri)
                .put("expiresIn", dc.expiresIn))
            val user = github.awaitDeviceToken(id, dc)
            bringToFront()
            emit("githubLoggedIn", req(reqId).put("user", user))
        }

        /** Signs out of GitHub. Generated summaries are kept (keyed by repository id). */
        @JavascriptInterface
        fun githubLogout() {
            github.logout()
            store.clearRepos()
        }

        @JavascriptInterface
        fun sync(reqId: String) = runSync(reqId)

        // ---- Codex (ChatGPT)
        @JavascriptInterface
        fun codexStatus(reqId: String) = launch(reqId, "Codex", foreground = false) {
            val a = CodexRuntime.account(this@MainActivity)
            emit("codexAccount", req(reqId).put("loggedIn", a != null)
                .put("email", a?.optString("email") ?: "")
                .put("plan", a?.optString("planType") ?: ""))
        }

        @JavascriptInterface
        fun codexLogin(reqId: String, device: Boolean) = launch(reqId, I18n.t("ChatGPTのログインを待っています", "Waiting for ChatGPT sign-in")) {
            val r = CodexRuntime.startLogin(this@MainActivity, device)
            val loginId = r.optString("loginId")
            codexLoginId = loginId
            emit("codexLoginStarted", req(reqId)
                .put("type", r.optString("type"))
                .put("authUrl", r.optString("authUrl"))
                .put("verificationUrl", r.optString("verificationUrl"))
                .put("userCode", r.optString("userCode")))
            if (!device) withContext(Dispatchers.Main) { openExternal(Uri.parse(r.optString("authUrl"))) }
            try {
                val done = CodexRuntime.awaitLogin(loginId)
                if (!done.optBoolean("success")) {
                    throw CodexException(done.optString("error").takeUnless { it.isBlank() || it == "null" }
                        ?: I18n.t("ChatGPTへのログインに失敗しました", "ChatGPT sign-in failed"))
                }
            } finally {
                codexLoginId = null
            }
            bringToFront()
            val a = CodexRuntime.account(this@MainActivity)
            emit("codexAccount", req(reqId).put("loggedIn", a != null)
                .put("email", a?.optString("email") ?: "")
                .put("plan", a?.optString("planType") ?: ""))
        }

        @JavascriptInterface
        fun codexCancelLogin() {
            val id = codexLoginId ?: return
            appScope.launch { runCatching { CodexRuntime.cancelLogin(this@MainActivity, id) } }
        }

        @JavascriptInterface
        fun codexLogout(reqId: String) = launch(reqId, "Codex", foreground = false) {
            CodexRuntime.logout(this@MainActivity)
            emit("codexAccount", req(reqId).put("loggedIn", false).put("email", "").put("plan", ""))
        }

        @JavascriptInterface
        fun codexModels(reqId: String) = launch(reqId, "Codex", foreground = false) {
            emit("codexModels", req(reqId).put("models", CodexRuntime.models(this@MainActivity)))
        }

        // ---- summaries
        @JavascriptInterface
        fun summarize(reqId: String, mode: String) {
            val bulk = mode == "missing" || mode == "all"
            if (bulk && bulkRunning()) {
                emit("error", req(reqId).put("message", I18n.t("概要の一括作成はすでに実行中です", "Summaries are already being created")))
                emit("done", req(reqId))
                return
            }
            val ids = when (mode) {
                "missing" -> missingIds()
                "all" -> store.repoMap().keys.toList()
                else -> mode.split(',').filter { it.isNotBlank() }
            }
            runSummarize(reqId, ids, bulk)
        }

        /** Stops the bulk run only; single-repository runs and other work continue. */
        @JavascriptInterface
        fun cancelSummaries() {
            jobs.keys.filter { it.startsWith("summarize-") }.forEach { jobs.remove(it)?.cancel() }
        }

        @JavascriptInterface
        fun clearSummaries() = store.clearSummaries()

        /** Undo a regeneration: returns the restored entry as JSON, or "" when there is none. */
        @JavascriptInterface
        fun restoreSummary(id: String): String = store.restorePrevious(id, store.lang())?.toString() ?: ""

        @JavascriptInterface
        fun cancel(reqId: String) {
            jobs.remove(reqId)?.cancel()
        }

        @JavascriptInterface
        fun cancelAll() = MainActivity.cancelAll()

        // ---- hyper fuzzy search
        @JavascriptInterface
        fun hyperRank(reqId: String, query: String, idsJson: String) {
            // Only the newest query matters; drop any ranking still running.
            jobs.keys.filter { it.startsWith("hyper-") && it != reqId }.forEach { jobs.remove(it)?.cancel() }
            val ids = runCatching {
                val a = JSONArray(idsJson); (0 until a.length()).map { a.optString(it) }
            }.getOrElse { emptyList() }
            launch(reqId, "search", foreground = false) {
                HyperSearch.rank(this@MainActivity, store, query, ids) { id, p ->
                    emit("hyperScore", req(reqId).put("id", id).put("p", p))
                }
                emit("hyperDone", req(reqId).put("model", HyperSearch.status(this@MainActivity)))
            }
        }

        @JavascriptInterface
        fun modelStatus(): String = HyperSearch.status(this@MainActivity).toString()

        // ---- Codex updates
        @JavascriptInterface
        fun codexCheckUpdate(reqId: String) = launch(reqId, "Codex", foreground = false) {
            val ctx = applicationContext
            val rel = CodexUpdater.latest(ctx, github.validToken())
            emit("codexUpdate", CodexUpdater.status(ctx).put("available",
                if (CodexUpdater.isNewer(ctx, rel)) rel.version else ""))
        }

        @JavascriptInterface
        fun codexUpdate(reqId: String) = runCodexUpdate(reqId)

        // ---- misc
        @JavascriptInterface
        fun openUrl(url: String) = runOnUiThread { openExternal(Uri.parse(url)) }

        @JavascriptInterface
        fun copy(text: String) {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("text", text))
        }

        @JavascriptInterface
        fun share(text: String) = runOnUiThread {
            runCatching {
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
            }
        }

        @JavascriptInterface
        fun haptic(kind: String) = runOnUiThread {
            web.performHapticFeedback(when (kind) {
                "confirm" -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
                "long" -> HapticFeedbackConstants.LONG_PRESS
                else -> HapticFeedbackConstants.CLOCK_TICK
            })
        }
    }
}
