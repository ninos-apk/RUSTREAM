package de.iptv.tv

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** UI language from the settings; applied to the activity's resources before any view exists. */
object AppLanguage {
    /** Same file as MainActivity.getPreferences, so startup still loads a single preference file. */
    private const val PREFERENCES = "MainActivity"
    private const val KEY = "language"
    /** Language tag to its own name: a viewer who picked the wrong language still finds theirs. */
    val choices = listOf("de" to "Deutsch", "en" to "English")
    const val DEFAULT = "de"

    fun current(context: Context): String {
        val saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null)
        return choices.firstOrNull { it.first == saved }?.first ?: DEFAULT
    }
    /** apply() updates the process-wide in-memory copy at once; the recreated activity reads that. */
    fun save(context: Context, tag: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
    }
    fun wrap(base: Context): Context {
        val locale = Locale.forLanguageTag(current(base))
        val config = Configuration(base.resources.configuration).apply { setLocale(locale); setLayoutDirection(locale) }
        return base.createConfigurationContext(config)
    }
    /** Locale of [context]'s resources, for number formatting that matches the UI language. */
    fun locale(context: Context): Locale = context.resources.configuration.let {
        if (android.os.Build.VERSION.SDK_INT >= 24) it.locales[0] else @Suppress("DEPRECATION") it.locale
    }
}
