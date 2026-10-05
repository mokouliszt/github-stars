package dev.mokouliszt.githubstars

import android.content.Context
import com.laya.LayaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * "Hyper fuzzy search": the Web UI ranks candidates lexically, then this re-scores the top
 * candidates with Laya, an open-weight Jev-style decision model (mmBERT-base, 322M), running
 * on the CPU through LiteRT (no GPU required). Each candidate gets a calibrated yes/no
 * probability for "is this the repository the user means?".
 */
object HyperSearch {

    private val lock = Mutex()
    @Volatile private var engine: LayaEngine? = null
    @Volatile private var lastUse = 0L
    @Volatile private var lastError: String = ""

    /** The model ships inside the APK (assets/laya). */
    fun available(ctx: Context): Boolean = runCatching {
        val names = ctx.assets.list("laya")?.toSet() ?: emptySet()
        LayaEngine.FILES.all { it in names }
    }.getOrDefault(false)

    fun status(ctx: Context): JSONObject = JSONObject()
        .put("available", available(ctx))
        .put("loaded", engine != null)
        .put("threads", engine?.threads ?: 0)
        .put("error", lastError)

    // ---------------------------------------------------------------- inference

    private suspend fun engine(ctx: Context): LayaEngine = lock.withLock {
        engine?.let { return@withLock it }
        withContext(Dispatchers.Default) {
            val e = try {
                LayaEngine(ctx).also { it.initialize() }
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                throw IllegalStateException(I18n.t("検索モデルを読み込めませんでした: ", "Could not load the search model: ") + lastError)
            }
            lastError = ""
            engine = e
            e
        }
    }

    /** Frees ~0.5–1 GB. Called when the app is backgrounded for a while or memory is low. */
    fun release() {
        engine?.let { runCatching { it.close() } }
        engine = null
    }

    fun releaseIfIdle(idleMs: Long) {
        if (engine != null && System.currentTimeMillis() - lastUse > idleMs) release()
    }

    /**
     * Scores [ids] for [query] in order and reports each probability as soon as it is known,
     * so the UI can re-sort progressively.
     */
    suspend fun rank(
        ctx: Context,
        store: Store,
        query: String,
        ids: List<String>,
        onScore: (String, Double) -> Unit,
    ) = withContext(Dispatchers.Default) {
        val e = engine(ctx)
        val repos = store.repoMap()
        val sums = store.summaries()
        val lang = store.lang()
        val question = mapOf(
            "type" to "noul",
            "instructions" to "Is this repository what the user is looking for? The user's request: \"${query.take(160)}\"",
            "criteria" to linkedMapOf(
                "false" to "no, it is not what the user means",
                "true" to "yes, it matches the request",
            ),
        )
        for (id in ids) {
            coroutineContext.ensureActive()
            val r = repos[id] ?: continue
            // Only the current language's summary counts, as in the list.
            val block = sums.optJSONObject(id)?.optJSONObject(lang)
            val answer = e.answer(state(r, block), question)
            lastUse = System.currentTimeMillis()
            val p = (answer["noul"] as? Number)?.toDouble() ?: continue
            onScore(id, p)
        }
    }

    /** Compact state; key order is stable so the prompt stays deterministic. */
    private fun state(r: JSONObject, block: JSONObject?): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["repository"] = r.optString("fullName")
        val summary = block?.optString("text")?.takeIf { it.isNotBlank() }
        if (summary != null) m["summary"] = summary
        val desc = r.optString("desc")
        if (desc.isNotBlank() && desc != summary) m["description"] = desc.take(240)
        val lang = r.optString("lang")
        if (lang.isNotBlank()) m["language"] = lang
        val tags = ArrayList<String>()
        r.optJSONArray("topics")?.let { t -> for (i in 0 until t.length()) tags.add(t.optString(i)) }
        block?.optJSONArray("tags")?.let { t -> for (i in 0 until t.length()) tags.add(t.optString(i)) }
        if (tags.isNotEmpty()) m["tags"] = tags.distinct().take(12)
        return m
    }
}
