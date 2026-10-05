package dev.mokouliszt.githubstars

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class GitHubException(message: String, val needLogin: Boolean = false) : Exception(message)

/**
 * GitHub sign-in (OAuth Device Flow) and the few REST calls the app needs.
 * The token is kept in EncryptedSharedPreferences.
 *
 * Both kinds of client ID work. An OAuth App token does not expire. A GitHub App issues user
 * tokens that expire after 8 hours together with a refresh token (valid about 6 months); the
 * token is refreshed automatically before it expires and once more if GitHub answers 401, so the
 * user only has to sign in again when the refresh token itself is gone or revoked.
 */
class GitHub(context: Context) {

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresIn: Int,
        val interval: Int,
    )

    private val prefs: SharedPreferences = runCatching {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "github_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { context.getSharedPreferences("github_plain", Context.MODE_PRIVATE) }

    val token: String? get() = prefs.getString("token", null)
    val isLoggedIn: Boolean get() = !token.isNullOrBlank()

    private val refreshLock = Mutex()
    private val expiresAt: Long get() = prefs.getLong("expiresAt", 0L)
    private val refreshToken: String? get() = prefs.getString("refresh", null)

    /** Saves a token response; expiry and refresh token are present only for GitHub App tokens. */
    private fun saveTokens(body: JSONObject, clientId: String) {
        val now = System.currentTimeMillis()
        val expiresIn = body.optLong("expires_in", 0L)
        val refreshIn = body.optLong("refresh_token_expires_in", 0L)
        val refresh = body.optString("refresh_token").takeUnless { it.isBlank() || it == "null" }
        val e = prefs.edit()
        e.putString("token", body.getString("access_token"))
        e.putString("tokenClientId", clientId)
        e.putLong("expiresAt", if (expiresIn > 0) now + expiresIn * 1000 else 0L)
        e.putLong("refreshExpiresAt", if (refreshIn > 0) now + refreshIn * 1000 else 0L)
        if (refresh != null) e.putString("refresh", refresh) else e.remove("refresh")
        e.commit()
    }

    /** A token that is valid for at least a few more minutes (refreshing it if needed), or null. */
    suspend fun validToken(): String? {
        runCatching { ensureFresh() }
        return token
    }

    private suspend fun ensureFresh() {
        val exp = expiresAt
        if (exp > 0 && System.currentTimeMillis() > exp - 5 * 60_000 && refreshToken != null) refresh(stale = null)
    }

    /**
     * Exchanges the refresh token for a new pair. Device-flow tokens can be refreshed without a
     * client secret. The refresh token rotates, so only one refresh runs at a time.
     * Returns false when GitHub rejects the refresh token (sign-in needed); network trouble throws
     * a normal error instead, so a bad connection never signs the user out.
     */
    private suspend fun refresh(stale: String?): Boolean = refreshLock.withLock {
        // Someone else may have refreshed while this caller waited for the lock.
        if (stale != null && token != stale) return@withLock true
        if (stale == null && expiresAt > System.currentTimeMillis() + 5 * 60_000) return@withLock true
        val rt = refreshToken ?: return@withLock false
        val cid = prefs.getString("tokenClientId", null) ?: return@withLock false
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url("https://github.com/login/oauth/access_token")
                .header("Accept", "application/json")
                .header("User-Agent", Net.USER_AGENT)
                .post(FormBody.Builder()
                    .add("client_id", cid)
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", rt)
                    .build())
                .build()
            val body = try {
                Net.http.newCall(req).execute().use { JSONObject(it.body?.string().orEmpty().ifBlank { "{}" }) }
            } catch (e: IOException) {
                throw GitHubException(I18n.t("GitHubに接続できませんでした", "Could not reach GitHub"))
            }
            if (body.optString("access_token").isNotBlank()) {
                saveTokens(body, cid)
                true
            } else false
        }
    }

    private fun signInAgain() = GitHubException(
        I18n.t("GitHubのログインが無効になりました。ログインし直してください", "Your GitHub sign-in is no longer valid. Please sign in again."),
        needLogin = true,
    )

    fun user(): JSONObject = JSONObject()
        .put("login", prefs.getString("login", "") ?: "")
        .put("avatar", prefs.getString("avatar", "") ?: "")
        .put("name", prefs.getString("name", "") ?: "")

    fun logout() {
        prefs.edit().clear().apply()
    }

    // ---------------------------------------------------------------- Device Flow

    suspend fun startDeviceFlow(clientId: String): DeviceCode = withContext(Dispatchers.IO) {
        if (clientId.isBlank()) throw GitHubException(I18n.t("GitHub OAuth AppのClient IDが未設定です。設定から入力してください", "No GitHub OAuth App client ID yet. Enter it in Settings."))
        val req = Request.Builder().url("https://github.com/login/device/code")
            .header("Accept", "application/json")
            .header("User-Agent", Net.USER_AGENT)
            .post(FormBody.Builder().add("client_id", clientId).add("scope", "").build())
            .build()
        Net.http.newCall(req).execute().use { res ->
            val body = JSONObject(res.body?.string().orEmpty().ifBlank { "{}" })
            if (!res.isSuccessful || body.has("error")) {
                throw GitHubException(deviceError(body.optString("error"), body.optString("error_description")))
            }
            DeviceCode(
                body.getString("device_code"),
                body.getString("user_code"),
                body.optString("verification_uri", "https://github.com/login/device"),
                body.optInt("expires_in", 900),
                body.optInt("interval", 5),
            )
        }
    }

    /** Polls until the user approves the code. Stores the token and profile on success. */
    suspend fun awaitDeviceToken(clientId: String, dc: DeviceCode): JSONObject {
        var interval = dc.interval.coerceAtLeast(5)
        val deadline = System.currentTimeMillis() + dc.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val body = withContext(Dispatchers.IO) {
                val req = Request.Builder().url("https://github.com/login/oauth/access_token")
                    .header("Accept", "application/json")
                    .header("User-Agent", Net.USER_AGENT)
                    .post(FormBody.Builder()
                        .add("client_id", clientId)
                        .add("device_code", dc.deviceCode)
                        .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                        .build())
                    .build()
                runCatching {
                    Net.http.newCall(req).execute().use { JSONObject(it.body?.string().orEmpty().ifBlank { "{}" }) }
                }.getOrElse { JSONObject().put("error", "network") }
            }
            if (body.optString("access_token").isNotBlank()) {
                saveTokens(body, clientId)
                return refreshProfile()
            }
            when (val err = body.optString("error")) {
                "authorization_pending", "network" -> Unit
                "slow_down" -> interval = body.optInt("interval", interval + 5)
                else -> throw GitHubException(deviceError(err, body.optString("error_description")))
            }
        }
        throw GitHubException(I18n.t("認証コードの有効期限が切れました。もう一度やり直してください", "The code expired. Please try again."))
    }

    private fun deviceError(code: String, desc: String): String = when (code) {
        "expired_token" -> I18n.t("認証コードの有効期限が切れました。もう一度やり直してください", "The code expired. Please try again.")
        "access_denied" -> I18n.t("GitHub側で認可がキャンセルされました", "Authorization was cancelled on GitHub")
        "incorrect_client_credentials" -> I18n.t("Client IDが正しくありません", "The client ID is not valid")
        "device_flow_disabled" -> I18n.t("このOAuth AppでDevice Flowが有効になっていません（Enable Device Flow にチェック）", "Device Flow is not enabled for this OAuth App (tick Enable Device Flow)")
        "unsupported_grant_type", "incorrect_device_code" -> I18n.t("認証に失敗しました（$code）", "Sign-in failed ($code)")
        else -> desc.ifBlank { I18n.t("GitHubへのログインに失敗しました", "GitHub sign-in failed") + if (code.isNotBlank()) " ($code)" else "" }
    }

    // ---------------------------------------------------------------- REST

    private fun request(path: String, accept: String, t: String): Request =
        Request.Builder().url("https://api.github.com$path")
            .header("Accept", accept)
            .header("Authorization", "Bearer $t")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", Net.USER_AGENT)
            .build()

    /**
     * GET with token upkeep: refreshes an expiring GitHub App token first, and on 401 refreshes
     * once and retries before asking the user to sign in again.
     */
    private suspend fun <T> get(path: String, accept: String = "application/vnd.github+json", read: (Response) -> T): T =
        withContext(Dispatchers.IO) {
            if (token.isNullOrBlank()) {
                throw GitHubException(I18n.t("GitHubにログインしてください", "Sign in to GitHub first"), needLogin = true)
            }
            ensureFresh()
            var t = token ?: throw signInAgain()
            var res = Net.http.newCall(request(path, accept, t)).execute()
            if (res.code == 401) {
                res.close()
                if (refreshToken == null || !refresh(stale = t)) throw signInAgain()
                t = token ?: throw signInAgain()
                res = Net.http.newCall(request(path, accept, t)).execute()
            }
            res.use(read)
        }

    private fun check(res: Response) {
        if (res.isSuccessful) return
        when (res.code) {
            401 -> throw signInAgain()
            403, 429 -> {
                if (res.header("X-RateLimit-Remaining") == "0") {
                    val reset = res.header("X-RateLimit-Reset")?.toLongOrNull()
                    val mins = reset?.let { ((it * 1000 - System.currentTimeMillis()) / 60000).coerceAtLeast(1) }
                    throw GitHubException(I18n.t("GitHub APIの利用上限に達しました", "GitHub API rate limit reached") + (mins?.let { I18n.t("（約${it}分後に回復）", " (resets in about $it min)") } ?: ""))
                }
                throw GitHubException(I18n.t("GitHubへのアクセスが拒否されました（${res.code}）", "GitHub refused the request (${res.code})"))
            }
            else -> throw GitHubException(I18n.t("GitHub APIエラー（${res.code}）", "GitHub API error (${res.code})"))
        }
    }

    suspend fun refreshProfile(): JSONObject = withContext(Dispatchers.IO) {
        get("/user") { res ->
            check(res)
            val u = JSONObject(res.body!!.string())
            prefs.edit()
                .putString("login", u.optString("login"))
                .putString("avatar", u.optString("avatar_url"))
                .putString("name", u.optString("name").takeUnless { it == "null" } ?: "")
                .apply()
            user()
        }
    }

    /** All starred repositories, newest star first, in the app's compact record format. */
    suspend fun starred(onPage: (Int) -> Unit = {}): JSONArray = withContext(Dispatchers.IO) {
        val out = JSONArray()
        var page = 1
        while (page <= 200) {
            val (items, hasNext) = get("/user/starred?per_page=100&page=$page&sort=created&direction=desc",
                "application/vnd.github.star+json") { res ->
                check(res)
                val arr = JSONArray(res.body!!.string())
                arr to (res.header("Link")?.contains("rel=\"next\"") == true)
            }
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val repo = it.optJSONObject("repo") ?: it
                out.put(compact(repo, it.optString("starred_at")))
            }
            onPage(out.length())
            if (!hasNext || items.length() == 0) break
            page++
        }
        out
    }

    /** README as raw text, or null if the repository has none. */
    suspend fun readme(fullName: String): String? = withContext(Dispatchers.IO) {
        get("/repos/$fullName/readme", "application/vnd.github.raw+json") { res ->
            if (res.code == 404) null else {
                check(res)
                res.body?.string()
            }
        }
    }

    private fun compact(r: JSONObject, starredAt: String): JSONObject {
        val owner = r.optJSONObject("owner") ?: JSONObject()
        fun str(k: String) = r.optString(k).takeUnless { it == "null" } ?: ""
        val topics = r.optJSONArray("topics") ?: JSONArray()
        return JSONObject()
            .put("id", r.optLong("id").toString())
            .put("fullName", str("full_name"))
            .put("name", str("name"))
            .put("owner", owner.optString("login"))
            .put("avatar", owner.optString("avatar_url"))
            .put("desc", str("description"))
            .put("lang", str("language"))
            .put("topics", topics)
            .put("stars", r.optInt("stargazers_count"))
            .put("forks", r.optInt("forks_count"))
            .put("pushedAt", str("pushed_at"))
            .put("updatedAt", str("updated_at"))
            .put("starredAt", starredAt.takeUnless { it == "null" } ?: "")
            .put("url", str("html_url"))
            .put("homepage", str("homepage"))
            .put("archived", r.optBoolean("archived"))
            .put("fork", r.optBoolean("fork"))
            .put("license", r.optJSONObject("license")?.optString("spdx_id")?.takeUnless { it == "null" || it == "NOASSERTION" } ?: "")
    }
}
