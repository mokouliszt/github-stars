package dev.mokouliszt.githubstars

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

/**
 * Keeps the Codex app-server current without waiting for an APK update.
 *
 * The APK ships a known-good build (jniLibs). On start-up the app asks GitHub for the latest
 * openai/codex release; a newer app-server build is downloaded, checked against the SHA-256
 * digest GitHub publishes for the asset, smoke-tested, and used from then on. Executing a
 * downloaded binary from app storage is only permitted for apps targeting API 28 or lower,
 * which is why this app's targetSdk is 28 (the same trade-off Termux makes).
 * If a downloaded build ever fails to start, the app falls back to the bundled one.
 */
object CodexUpdater {

    private const val ASSET = "codex-app-server-aarch64-unknown-linux-musl.tar.gz"
    private const val ENTRY = "codex-app-server-aarch64-unknown-linux-musl"
    private const val CHECK_INTERVAL_MS = 60 * 60 * 1000L

    class Release(val version: String, val url: String, val size: Long, val sha256: String?)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("codex_update", Context.MODE_PRIVATE)
    private fun dir(ctx: Context) = File(ctx.filesDir, "codex-bin")

    val bundledVersion: String get() = BuildConfig.CODEX_VERSION

    /** Version of the binary that will be started. */
    fun activeVersion(ctx: Context): String {
        val v = prefs(ctx).getString("current", null) ?: return bundledVersion
        val f = File(dir(ctx), "$v/codex-app-server")
        return if (f.canExecute() && compare(v, bundledVersion) > 0 && !isBad(ctx, v)) v else bundledVersion
    }

    fun activeBinary(ctx: Context): File {
        val v = activeVersion(ctx)
        return if (v == bundledVersion) CodexRuntime.bundled(ctx) else File(dir(ctx), "$v/codex-app-server")
    }

    /** Called when a downloaded build could not be started: stop using it. */
    fun markBad(ctx: Context, version: String) {
        if (version == bundledVersion) return
        val bad = prefs(ctx).getStringSet("bad", emptySet())!!.toMutableSet().apply { add(version) }
        prefs(ctx).edit().putStringSet("bad", bad).remove("current").apply()
    }

    private fun isBad(ctx: Context, v: String) = prefs(ctx).getStringSet("bad", emptySet())!!.contains(v)

    fun status(ctx: Context): JSONObject = JSONObject()
        .put("active", activeVersion(ctx))
        .put("bundled", bundledVersion)
        .put("latest", prefs(ctx).getString("latest", "") ?: "")
        .put("checkedAt", prefs(ctx).getLong("checkedAt", 0))

    fun isMetered(ctx: Context): Boolean =
        runCatching { ctx.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }.getOrDefault(true)

    fun shouldCheck(ctx: Context): Boolean =
        System.currentTimeMillis() - prefs(ctx).getLong("checkedAt", 0) > CHECK_INTERVAL_MS

    /** Latest openai/codex release. Uses the GitHub API (with the user's token when available). */
    suspend fun latest(ctx: Context, token: String?): Release = withContext(Dispatchers.IO) {
        val rel = runCatching { viaApi(token) }.getOrElse { viaRedirect() }
        prefs(ctx).edit().putString("latest", rel.version).putLong("checkedAt", System.currentTimeMillis()).apply()
        rel
    }

    private fun viaApi(token: String?): Release {
        val req = Request.Builder().url("https://api.github.com/repos/openai/codex/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", Net.USER_AGENT)
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
            .build()
        Net.http.newCall(req).execute().use { res ->
            check(res.isSuccessful) { "GitHub API ${res.code}" }
            val o = JSONObject(res.body!!.string())
            val version = versionOf(o.getString("tag_name"))
            val assets = o.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == ASSET) {
                    val digest = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
                    return Release(version, a.getString("browser_download_url"), a.optLong("size"), digest)
                }
            }
            error("release $version has no $ASSET")
        }
    }

    /** Fallback when the API is rate-limited: read the tag from the /releases/latest redirect. */
    private fun viaRedirect(): Release {
        val client = Net.http.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val req = Request.Builder().url("https://github.com/openai/codex/releases/latest")
            .header("User-Agent", Net.USER_AGENT).build()
        client.newCall(req).execute().use { res ->
            val loc = res.header("Location") ?: error("no redirect")
            val tag = loc.substringAfterLast("/tag/")
            val version = versionOf(tag)
            return Release(version, "https://github.com/openai/codex/releases/download/$tag/$ASSET", 0, null)
        }
    }

    private fun versionOf(tag: String) = tag.removePrefix("rust-v").removePrefix("v")

    fun isNewer(ctx: Context, rel: Release): Boolean =
        compare(rel.version, activeVersion(ctx)) > 0 && !isBad(ctx, rel.version)

    /** Downloads, verifies, extracts and smoke-tests [rel]. Returns the installed version. */
    suspend fun install(ctx: Context, rel: Release, onProgress: (Long, Long) -> Unit): String = withContext(Dispatchers.IO) {
        val root = dir(ctx).apply { mkdirs() }
        val tgz = File(root, "${rel.version}.tar.gz.part")
        tgz.delete()
        val client = Net.http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
        val md = MessageDigest.getInstance("SHA-256")
        client.newCall(Request.Builder().url(rel.url).header("User-Agent", Net.USER_AGENT).build()).execute().use { res ->
            check(res.isSuccessful) { I18n.t("ダウンロードに失敗しました（${res.code}）", "Download failed (${res.code})") }
            val body = res.body!!
            val total = if (rel.size > 0) rel.size else body.contentLength()
            var have = 0L
            var last = 0L
            tgz.outputStream().use { out ->
                val input = body.byteStream()
                val buf = ByteArray(1 shl 16)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    md.update(buf, 0, n)
                    have += n
                    val now = System.currentTimeMillis()
                    if (now - last > 300) { last = now; onProgress(have, total) }
                }
            }
            onProgress(have, total)
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (rel.sha256 != null && !rel.sha256.equals(actual, ignoreCase = true)) {
            tgz.delete()
            error(I18n.t("ダウンロードしたCodexの検証に失敗しました", "The downloaded Codex failed verification"))
        }
        val target = File(root, "${rel.version}/codex-app-server")
        target.parentFile!!.mkdirs()
        val tmp = File(target.parentFile, "codex-app-server.tmp")
        extract(tgz, tmp)
        tgz.delete()
        check(tmp.setExecutable(true, true)) { I18n.t("実行権限を設定できませんでした", "Could not mark the binary executable") }
        smokeTest(tmp)
        tmp.renameTo(target)
        prefs(ctx).edit().putString("current", rel.version).apply()
        // Keep only the active build.
        root.listFiles()?.forEach { f -> if (f.isDirectory && f.name != rel.version) f.deleteRecursively() }
        rel.version
    }

    private fun smokeTest(bin: File) {
        val p = ProcessBuilder(bin.absolutePath, "--help").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val ok = p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0 && out.contains("--listen")
        if (!ok) {
            p.destroy()
            bin.delete()
            error(I18n.t("新しいCodexを起動できませんでした", "The new Codex build did not start"))
        }
    }

    /** Minimal ustar reader: copies the app-server entry out of the release archive. */
    private fun extract(tgz: File, out: File) {
        GZIPInputStream(BufferedInputStream(FileInputStream(tgz), 1 shl 16)).use { gz ->
            val header = ByteArray(512)
            while (true) {
                if (!readFully(gz, header) || header.all { it == 0.toByte() }) error("$ENTRY not found in archive")
                val name = String(header, 0, 100, Charsets.UTF_8).trimEnd('\u0000')
                val prefix = String(header, 345, 155, Charsets.UTF_8).trimEnd('\u0000')
                val full = if (prefix.isNotEmpty()) "$prefix/$name" else name
                val size = String(header, 124, 12, Charsets.US_ASCII).trim('\u0000', ' ').ifEmpty { "0" }.toLong(8)
                val type = header[156].toInt().toChar()
                val padded = (size + 511) / 512 * 512
                if ((type == '0' || type == '\u0000') && (full == ENTRY || full.endsWith("/$ENTRY"))) {
                    out.outputStream().use { o -> copyExactly(gz, o, size) }
                    return
                }
                skipExactly(gz, padded)
            }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun copyExactly(input: InputStream, out: java.io.OutputStream, size: Long) {
        val buf = ByteArray(1 shl 16)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) error("archive truncated")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skipExactly(input: InputStream, size: Long) {
        val buf = ByteArray(1 shl 16)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) error("archive truncated")
            left -= n
        }
    }

    fun compare(a: String, b: String): Int {
        fun parts(v: String) = v.split('-', limit = 2)[0].split('.').map { it.toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }
}
