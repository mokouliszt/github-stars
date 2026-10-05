package dev.mokouliszt.githubstars

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Small JSON-file store. A few thousand starred repositories fit comfortably in memory, and
 * whole-file writes keep the format easy to inspect and back up.
 *
 * summaries.json keeps one block per language:
 *   { "<repoId>": { "ja": {text, tags[], model, at, basis, prev?}, "en": {...} } }
 * Only the block for the current app language is shown; the other one is kept untouched.
 */
class Store(context: Context) {

    private val dir = context.filesDir
    private val reposFile = File(dir, "repos.json")
    private val summariesFile = File(dir, "summaries.json")
    private val settingsFile = File(dir, "settings.json")

    companion object {
        private val lock = Any()
        val LANGS = listOf("ja", "en")
        private val JA = Regex("[\\u3040-\\u30ff\\u3400-\\u9fff]")

        val DEFAULTS: JSONObject
            get() = JSONObject()
                .put("lang", I18n.defaultLang()) // ja | en: UI language = summary language
                .put("model", "")                // "" = follow Codex's current default
                .put("effort", "")               // "" = the model's default effort
                .put("sandbox", "workspace-write")
                .put("webSearch", true)
                .put("codexAutoUpdate", "wifi")  // wifi | always | off
                .put("batchSize", 6)
                .put("concurrency", 2)
                .put("hyperCandidates", 20)      // CPU-only: 10 | 20 | 30
                .put("githubClientId", "")
    }

    private fun write(f: File, o: JSONObject) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(f)) {
            f.delete(); tmp.renameTo(f)
        }
    }

    // ------------------------------------------------------------------ settings

    fun settings(): JSONObject = synchronized(lock) {
        val s = runCatching { if (settingsFile.isFile) JSONObject(settingsFile.readText()) else JSONObject() }
            .getOrElse { JSONObject() }
        // Older builds had separate summary/display languages; the display language wins.
        if (!s.has("lang") && s.has("displayLang")) s.put("lang", I18n.normalize(s.optString("displayLang")))
        s.remove("displayLang")
        s.remove("summaryLang")
        val d = DEFAULTS
        d.keys().forEach { k -> if (!s.has(k)) s.put(k, d.get(k)) }
        s.put("lang", I18n.normalize(s.optString("lang")))
        s
    }

    fun saveSettings(patch: JSONObject): JSONObject = synchronized(lock) {
        val s = settings()
        patch.keys().forEach { k -> if (DEFAULTS.has(k)) s.put(k, patch.get(k)) }
        s.put("lang", I18n.normalize(s.optString("lang")))
        write(settingsFile, s)
        s
    }

    fun lang(): String = settings().optString("lang")

    // ------------------------------------------------------------------ repositories

    fun repos(): JSONObject = synchronized(lock) {
        runCatching { if (reposFile.isFile) JSONObject(reposFile.readText()) else null }.getOrNull()
            ?: JSONObject().put("syncedAt", 0).put("items", JSONArray())
    }

    fun saveRepos(items: JSONArray) = synchronized(lock) {
        write(reposFile, JSONObject().put("syncedAt", System.currentTimeMillis()).put("items", items))
    }

    fun repoMap(): Map<String, JSONObject> {
        val items = repos().optJSONArray("items") ?: JSONArray()
        val m = LinkedHashMap<String, JSONObject>(items.length())
        for (i in 0 until items.length()) items.optJSONObject(i)?.let { m[it.optString("id")] = it }
        return m
    }

    /** Sign-out: forget the account's repository list but keep generated summaries. */
    fun clearRepos() = synchronized(lock) { reposFile.delete() }

    // ------------------------------------------------------------------ summaries
    //
    // Generated text is kept until the user explicitly regenerates or deletes it: syncing,
    // signing out, switching language, Codex updates and failed runs never remove anything.

    fun summaries(): JSONObject = synchronized(lock) {
        if (!summariesFile.isFile) return@synchronized JSONObject()
        val raw = runCatching { JSONObject(summariesFile.readText()) }.getOrElse {
            // Never let an unreadable file be overwritten by the next save: set it aside.
            summariesFile.renameTo(File(dir, "summaries.corrupt-${System.currentTimeMillis()}.json"))
            return@synchronized JSONObject()
        }
        val out = JSONObject()
        var migrated = false
        raw.keys().forEach { id ->
            val e = raw.optJSONObject(id) ?: return@forEach
            if (isLegacy(e)) { migrated = true; out.put(id, migrate(e)) } else out.put(id, e)
        }
        if (migrated) write(summariesFile, out)
        out
    }

    /** True for the pre-1.1 shape {ja: "...", en: "...", tags: [...], model, at, prev}. */
    private fun isLegacy(e: JSONObject) = e.opt("ja") is String || e.opt("en") is String || e.has("tags")

    private fun migrate(e: JSONObject): JSONObject {
        val out = JSONObject()
        val tags = e.optJSONArray("tags") ?: JSONArray()
        val prev = e.optJSONObject("prev")
        for (lang in LANGS) {
            val text = e.optString(lang).takeUnless { it == "null" }.orEmpty()
            if (text.isBlank()) continue
            val block = JSONObject()
                .put("text", text)
                .put("tags", tagsFor(lang, tags))
                .put("model", e.optString("model"))
                .put("at", e.optLong("at"))
                .put("basis", e.optString("basis"))
            val prevText = prev?.optString(lang)?.takeUnless { it == "null" }.orEmpty()
            if (prevText.isNotBlank() && prevText != text) {
                block.put("prev", JSONObject()
                    .put("text", prevText)
                    .put("tags", tagsFor(lang, prev?.optJSONArray("tags") ?: JSONArray()))
                    .put("model", prev?.optString("model").orEmpty())
                    .put("at", prev?.optLong("at") ?: 0L))
            }
            out.put(lang, block)
        }
        return out
    }

    /** Old tags mixed both languages: English keeps the non-Japanese ones. */
    private fun tagsFor(lang: String, tags: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until tags.length()) {
            val t = tags.optString(i)
            if (t.isBlank()) continue
            if (lang == "en" && JA.containsMatchIn(t)) continue
            out.put(t)
        }
        return out
    }

    /** True when the repository has a summary in [lang]. */
    fun hasSummary(all: JSONObject, id: String, lang: String): Boolean =
        all.optJSONObject(id)?.optJSONObject(lang)?.optString("text")?.isNotBlank() == true

    /**
     * Stores freshly generated blocks for [lang] ({text, tags, model, at, basis}). The block it
     * replaces is kept as `prev` so the regeneration can be undone; other languages are untouched.
     * Returns the full entries for the UI.
     */
    fun putSummaries(lang: String, blocks: Map<String, JSONObject>): Map<String, JSONObject> = synchronized(lock) {
        val all = summaries()
        val changed = LinkedHashMap<String, JSONObject>()
        blocks.forEach { (id, block) ->
            val entry = all.optJSONObject(id) ?: JSONObject()
            val old = entry.optJSONObject(lang)
            if (old != null && old.optString("text").isNotBlank()) {
                old.remove("prev")
                block.put("prev", old)
            }
            entry.put(lang, block)
            all.put(id, entry)
            changed[id] = entry
        }
        write(summariesFile, all)
        changed
    }

    /** Swaps the [lang] block with its previous version. Returns the full entry, or null. */
    fun restorePrevious(id: String, lang: String): JSONObject? = synchronized(lock) {
        val all = summaries()
        val entry = all.optJSONObject(id) ?: return@synchronized null
        val cur = entry.optJSONObject(lang) ?: return@synchronized null
        val prev = cur.optJSONObject("prev") ?: return@synchronized null
        cur.remove("prev")
        prev.put("prev", cur)
        entry.put(lang, prev)
        all.put(id, entry)
        write(summariesFile, all)
        entry
    }

    /** Explicit "delete all summaries" from Settings (every language). */
    fun clearSummaries() = synchronized(lock) { summariesFile.delete() }
}
