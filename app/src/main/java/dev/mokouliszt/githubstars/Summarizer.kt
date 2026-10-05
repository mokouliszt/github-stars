package dev.mokouliszt.githubstars

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * Writes short catalogue entries for starred repositories with Codex, in the app's current
 * language only. Entries in the other language are left as they are.
 *
 * Repositories are sent in small batches: one turn per batch keeps the per-turn overhead low,
 * and the README excerpt is fetched by the app (GitHub API) so Codex rarely needs tools.
 */
class Summarizer(
    private val ctx: Context,
    private val store: Store,
    private val github: GitHub,
) {

    class Progress(val done: Int, val total: Int, val failed: Int)

    suspend fun run(
        ids: List<String>,
        onSaved: (Map<String, JSONObject>) -> Unit,
        onProgress: (Progress) -> Unit,
        onNotice: (String) -> Unit = {},
    ): Progress = coroutineScope {
        val s = store.settings()
        val lang = s.optString("lang")
        val repos = store.repoMap()
        val targets = ids.mapNotNull { repos[it] }
        val batchSize = s.optInt("batchSize", 6).coerceIn(1, 12)
        val concurrency = s.optInt("concurrency", 2).coerceIn(1, 4)
        // Blank = let Codex decide, so a Codex update that changes the default model/effort is
        // picked up without touching the app.
        var model: String? = s.optString("model").ifBlank { null }
        var effort: String? = s.optString("effort").ifBlank { null }
        val sandbox = s.optString("sandbox", "workspace-write")
        val webSearch = s.optBoolean("webSearch", true)
        fun opts() = CodexRuntime.TurnOptions(model, effort, sandbox, webSearch)
        val instructions = Prompts.instructions(lang)
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val total = targets.size
        onProgress(Progress(0, total, 0))
        if (total == 0) return@coroutineScope Progress(0, 0, 0)

        // Check sign-in once up front so a signed-out user gets one clear message.
        if (CodexRuntime.account(ctx) == null) {
            throw CodexException(I18n.t("ChatGPTにログインしてください", "Sign in to ChatGPT first"), needLogin = true)
        }
        // A pinned model or effort that Codex no longer offers falls back to Codex's defaults.
        if (model != null || effort != null) {
            val offered = runCatching { CodexRuntime.models(ctx) }.getOrNull()
            if (offered != null && offered.length() > 0) {
                val ids2 = (0 until offered.length()).map { offered.getJSONObject(it) }
                val m = ids2.firstOrNull { it.optString("id") == model }
                if (model != null && m == null) {
                    onNotice(I18n.t("設定したモデル（$model）は現在使えないため、Codexのデフォルトで作成します",
                        "The selected model ($model) is no longer offered, so Codex's default is used"))
                    model = null
                    effort = null
                }
                val target = m ?: ids2.firstOrNull { it.optBoolean("isDefault") }
                val efforts = target?.optJSONArray("efforts")
                if (effort != null && efforts != null && efforts.length() > 0 &&
                    (0 until efforts.length()).none { efforts.optString(it) == effort }
                ) {
                    effort = null
                }
            }
        }

        val sem = Semaphore(concurrency)
        val readmeSem = Semaphore(4)
        targets.chunked(batchSize).map { batch ->
            async {
                sem.withPermit {
                    coroutineContext.ensureActive()
                    val inputs = batch.map { r ->
                        async {
                            readmeSem.withPermit {
                                val readme = runCatching { github.readme(r.optString("fullName")) }.getOrNull()
                                r to Prompts.cleanReadme(readme)
                            }
                        }
                    }.awaitAll()
                    val prompt = Prompts.summaryPrompt(inputs, lang)
                    val result = runCatching {
                        try {
                            CodexRuntime.runTurn(ctx, instructions, prompt, opts(), Prompts.SUMMARY_SCHEMA)
                        } catch (e: CodexException) {
                            // A pinned model/effort rejected mid-run: retry once with Codex's defaults.
                            val msg = e.message.orEmpty()
                            if ((effort != null && msg.contains("effort", ignoreCase = true)) ||
                                (model != null && msg.contains("model", ignoreCase = true))
                            ) {
                                if (msg.contains("model", ignoreCase = true)) model = null
                                effort = null
                                CodexRuntime.runTurn(ctx, instructions, prompt, opts(), Prompts.SUMMARY_SCHEMA)
                            } else throw e
                        }
                    }
                    val err = result.exceptionOrNull()
                    if (err is CodexException && err.needLogin) throw err
                    val parsed = result.getOrNull()?.let { parse(it, batch, model) } ?: emptyMap()
                    if (parsed.isNotEmpty()) onSaved(store.putSummaries(lang, parsed))
                    failed.addAndGet(batch.size - parsed.size)
                    onProgress(Progress(done.addAndGet(batch.size), total, failed.get()))
                }
            }
        }.awaitAll()
        Progress(done.get(), total, failed.get())
    }

    private fun parse(text: String, batch: List<JSONObject>, model: String?): Map<String, JSONObject> {
        val json = extractJson(text) ?: return emptyMap()
        val items = json.optJSONArray("items") ?: return emptyMap()
        val known = batch.associateBy { it.optString("id") }
        val out = LinkedHashMap<String, JSONObject>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val id = it.optString("id")
            val repo = known[id] ?: continue
            val summary = it.optString("summary").trim()
            if (summary.isEmpty()) continue
            val tags = JSONArray()
            val t = it.optJSONArray("tags") ?: JSONArray()
            for (j in 0 until t.length()) t.optString(j).trim().takeIf { s -> s.isNotEmpty() }?.let { s -> tags.put(s) }
            out[id] = JSONObject()
                .put("text", summary)
                .put("tags", tags)
                .put("model", model ?: CodexRuntime.lastModel)
                .put("at", System.currentTimeMillis())
                .put("basis", repo.optString("pushedAt"))
        }
        return out
    }

    private fun extractJson(text: String): JSONObject? {
        runCatching { return JSONObject(text.trim()) }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }
}

object Prompts {

    fun instructions(lang: String): String {
        val language = if (lang == "en") "English" else "Japanese"
        val style = if (lang == "en") {
            "1–2 sentences, at most about 220 characters."
        } else {
            "1–2 sentences, about 60–140 characters. Do not start with 「このリポジトリは」."
        }
        val tagRule = if (lang == "en") {
            "4–8 short English keywords in lowercase"
        } else {
            "4–8 short Japanese keywords (well-known technical terms such as \"cli\" or \"llm\" may stay in English)"
        }
        return """
            You write short catalogue entries for GitHub repositories that a user has starred, so they can
            recognise each one at a glance later. You receive repository metadata and a README excerpt.

            For every repository in the input, return one item with the same "id":
            - summary: written in $language. $style Say concretely what it is, what it does and for whom;
              mention the platform or language when it matters. Plain, factual tone. No marketing words, no emoji.
            - tags: $tagRule that someone might type when they only vaguely remember the tool
              (category, use case, platform, notable technology).

            Base the entries on the provided metadata and README first. When that is missing or too thin
            to tell what the project is, you may use web search to check. Do not guess. Shell commands are
            not needed for this task. Reply with JSON matching the schema only.
        """.trimIndent()
    }

    val SUMMARY_SCHEMA: JSONObject = JSONObject("""
        {
          "type": "object",
          "properties": {
            "items": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "id": {"type": "string"},
                  "summary": {"type": "string"},
                  "tags": {"type": "array", "items": {"type": "string"}}
                },
                "required": ["id", "summary", "tags"],
                "additionalProperties": false
              }
            }
          },
          "required": ["items"],
          "additionalProperties": false
        }
    """.trimIndent())

    fun summaryPrompt(inputs: List<Pair<JSONObject, String>>, lang: String): String {
        val arr = JSONArray()
        inputs.forEach { (r, readme) ->
            arr.put(JSONObject()
                .put("id", r.optString("id"))
                .put("repository", r.optString("fullName"))
                .put("description", r.optString("desc"))
                .put("language", r.optString("lang"))
                .put("topics", r.optJSONArray("topics") ?: JSONArray())
                .put("homepage", r.optString("homepage"))
                .put("archived", r.optBoolean("archived"))
                .put("readme_excerpt", readme))
        }
        val language = if (lang == "en") "English" else "Japanese"
        return "Write every summary and tag in $language.\n\nRepositories:\n" + arr.toString(1)
    }

    /** Strips badges, images, HTML and long code blocks; keeps the first ~2800 characters. */
    fun cleanReadme(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw
        s = s.replace(Regex("<!--[\\s\\S]*?-->"), " ")
        s = s.replace(Regex("\\[!\\[[^\\]]*]\\([^)]*\\)]\\([^)]*\\)"), " ")   // linked badges
        s = s.replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), " ")                  // images
        s = s.replace(Regex("```[\\s\\S]*?```")) { m ->
            val lines = m.value.lines()
            if (lines.size <= 8) m.value else (lines.take(6) + "...```").joinToString("\n")
        }
        s = s.replace(Regex("<img[^>]*>", RegexOption.IGNORE_CASE), " ")
        s = s.replace(Regex("</?[a-zA-Z][^>]*>"), " ")
        s = s.replace(Regex("[ \\t]+"), " ")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim().take(2800)
    }
}
