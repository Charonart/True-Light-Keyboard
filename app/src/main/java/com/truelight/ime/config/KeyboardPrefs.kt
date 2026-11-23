package com.truelight.ime.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Central SharedPreferences configuration shared between SettingsActivity and TrueLightIME.
 * Zero external dependencies.
 */
class KeyboardPrefs(context: Context) {

    companion object {
        const val PREFS_NAME = "true_light_prefs"

        const val KEY_ENABLED_LANGUAGES = "enabled_languages"
        const val KEY_ACTIVE_LANGUAGE = "active_language"
        const val KEY_VI_MODERN_TONE = "vi_modern_tone"

        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_KEYBOARD_HEIGHT_DP = "keyboard_height_dp"
        const val KEY_SHOW_NUMBER_ROW = "show_number_row"
        const val KEY_SHOW_SUB_LABELS = "show_sub_labels"
        const val KEY_LONG_PRESS_SYMBOLS = "long_press_symbols"
        const val KEY_HAPTIC_ENABLED = "haptic_enabled"

        const val KEY_SUGGESTIONS_ENABLED = "suggestions_enabled"
        const val KEY_AUTO_CORRECT_ENABLED = "auto_correct_enabled"

        const val GITHUB_RELEASES_URL = "https://github.com/Charonart/True-Light-Keyboard/releases"

        const val KEY_AUTO_CAPS = "auto_caps"
        const val KEY_DOUBLE_SPACE_PERIOD = "double_space_period"
        const val KEY_CLIPBOARD_ENABLED = "clipboard_enabled"
        const val KEY_CUSTOM_CSS_ENABLED = "custom_css_enabled"
        const val KEY_CUSTOM_CSS = "custom_css"
        const val KEY_DICT_REVISION = "dict_revision"
        const val KEY_HISTORY_REVISION = "history_revision"

        const val THEME_SYSTEM = 0
        const val THEME_DARK = 1
        const val THEME_AMOLED = 2
        const val THEME_LIGHT = 3

        const val DEFAULT_LANGUAGE = "en_us"
        const val DEFAULT_HEIGHT_DP = 230
        const val MIN_HEIGHT_DP = 180
        const val MAX_HEIGHT_DP = 300
    }

    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabledLanguages: List<String>
        get() {
            val raw = prefs.getString(KEY_ENABLED_LANGUAGES, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
            val list = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            return if (list.isEmpty()) listOf(DEFAULT_LANGUAGE) else list
        }
        set(value) {
            val clean = value.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val finalList = if (clean.isEmpty()) listOf(DEFAULT_LANGUAGE) else clean
            prefs.edit().putString(KEY_ENABLED_LANGUAGES, finalList.joinToString(",")).apply()
            if (activeLanguageId !in finalList) {
                activeLanguageId = finalList.first()
            }
        }

    var activeLanguageId: String
        get() {
            val active = prefs.getString(KEY_ACTIVE_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
            val enabled = enabledLanguages
            return if (active in enabled) active else enabled.first()
        }
        set(value) {
            prefs.edit().putString(KEY_ACTIVE_LANGUAGE, value).apply()
        }

    fun addLanguage(langId: String) {
        val list = enabledLanguages.toMutableList()
        if (langId !in list) {
            list.add(langId)
            enabledLanguages = list
        }
    }

    fun removeLanguage(langId: String): Boolean {
        val list = enabledLanguages.toMutableList()
        if (list.size <= 1) return false
        val removed = list.remove(langId)
        if (removed) {
            enabledLanguages = list
        }
        return removed
    }

    fun cycleNextLanguage(): String {
        val list = enabledLanguages
        if (list.size <= 1) return list.first()
        val idx = list.indexOf(activeLanguageId)
        val next = list[(idx + 1) % list.size]
        activeLanguageId = next
        return next
    }

    /** false = old style (hòa, thúy), true = modern style (hoà, thuý) */
    var vietnameseModernTone: Boolean
        get() = prefs.getBoolean(KEY_VI_MODERN_TONE, false)
        set(value) = prefs.edit().putBoolean(KEY_VI_MODERN_TONE, value).apply()

    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, THEME_SYSTEM)
        set(value) = prefs.edit().putInt(KEY_THEME_MODE, value).apply()

    var keyboardHeightDp: Int
        get() = prefs.getInt(KEY_KEYBOARD_HEIGHT_DP, DEFAULT_HEIGHT_DP).coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP)
        set(value) = prefs.edit().putInt(KEY_KEYBOARD_HEIGHT_DP, value.coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP)).apply()

    var showNumberRow: Boolean
        get() = prefs.getBoolean(KEY_SHOW_NUMBER_ROW, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_NUMBER_ROW, value).apply()

    var showSubLabels: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SUB_LABELS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_SUB_LABELS, value).apply()

    var longPressSymbols: Boolean
        get() = prefs.getBoolean(KEY_LONG_PRESS_SYMBOLS, true)
        set(value) = prefs.edit().putBoolean(KEY_LONG_PRESS_SYMBOLS, value).apply()

    var hapticEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC_ENABLED, value).apply()

    /** Toggle 1: Show suggestion strip & load dictionary (turn OFF for zero dictionary RAM) */
    var suggestionsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SUGGESTIONS_ENABLED, true)
        set(value) {
            val editor = prefs.edit().putBoolean(KEY_SUGGESTIONS_ENABLED, value)
            if (!value) {
                editor.putBoolean(KEY_AUTO_CORRECT_ENABLED, false)
            }
            editor.apply()
        }

    /** Toggle 2: Automatically replace typos on Space (requires suggestionsEnabled == true) */
    var autoCorrectEnabled: Boolean
        get() = suggestionsEnabled && prefs.getBoolean(KEY_AUTO_CORRECT_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CORRECT_ENABLED, value && suggestionsEnabled).apply()

    var autoCapsEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CAPS, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CAPS, value).apply()

    var doubleSpacePeriodEnabled: Boolean
        get() = prefs.getBoolean(KEY_DOUBLE_SPACE_PERIOD, true)
        set(value) = prefs.edit().putBoolean(KEY_DOUBLE_SPACE_PERIOD, value).apply()

    var clipboardEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLIPBOARD_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_CLIPBOARD_ENABLED, value).apply()

    var customCssEnabled: Boolean
        get() = prefs.getBoolean(KEY_CUSTOM_CSS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_CUSTOM_CSS_ENABLED, value).apply()

    var customCss: String
        get() = prefs.getString(KEY_CUSTOM_CSS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_CSS, value).apply()

    var dictRevision: Int
        get() = prefs.getInt(KEY_DICT_REVISION, 0)
        set(value) = prefs.edit().putInt(KEY_DICT_REVISION, value).apply()

    fun bumpDictRevision() {
        dictRevision = dictRevision + 1
    }

    var historyRevision: Int
        get() = prefs.getInt(KEY_HISTORY_REVISION, 0)
        set(value) = prefs.edit().putInt(KEY_HISTORY_REVISION, value).apply()

    fun bumpHistoryRevision() {
        historyRevision = historyRevision + 1
    }
}
