package dev.mokouliszt.githubstars

import java.util.Locale

/**
 * App language (日本語 / English), chosen in Settings and independent of the system locale.
 * Native messages that reach the UI (errors, notifications) are picked with [t].
 */
object I18n {
    @Volatile var lang: String = defaultLang()

    fun t(ja: String, en: String): String = if (lang == "en") en else ja

    /** First launch follows the device language. */
    fun defaultLang(): String = if (Locale.getDefault().language == "ja") "ja" else "en"

    fun normalize(v: String?): String = if (v == "en") "en" else if (v == "ja") "ja" else defaultLang()
}
