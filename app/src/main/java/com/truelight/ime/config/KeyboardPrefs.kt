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

        const val KEY_LONG_PRESS_ENTER_ACTION = "long_press_enter_action"
        const val ENTER_LONG_PRESS_NONE = 0
        const val ENTER_LONG_PRESS_EMOJI = 1
        const val ENTER_LONG_PRESS_NEWLINE = 2
        const val ENTER_LONG_PRESS_POPUP = 3

        const val KEY_SHOW_EMOJI_KEY = "show_emoji_key"
        const val KEY_KEYPRESS_ANIMATION = "keypress_animation"

        const val KEY_VI_AUTO_RESTORE = "vi_auto_restore"
        const val KEY_SMART_PUNCTUATION = "smart_punctuation"
        const val KEY_SPACE_CURSOR_GLIDE = "space_cursor_glide"
        const val KEY_SPACE_LONG_PRESS_LANG = "space_long_press_lang"
        const val KEY_SWIPE_DELETE_WORD = "swipe_delete_word"
        const val KEY_LONG_PRESS_DELAY_MS = "long_press_delay_ms"
        const val KEY_KEY_POPUP_ENABLED = "key_popup_enabled"
        const val KEY_SHOW_TOP_UTILITY_BAR = "show_top_utility_bar"
        const val KEY_EMOJI_BUTTON_MODE = "emoji_button_mode"
        const val KEY_SHOW_SETTINGS_KEY = "show_settings_key"
        const val KEY_SHOW_COMMA_KEY = "show_comma_key"
        const val KEY_SHOW_PERIOD_KEY = "show_period_key"
        const val KEY_SOUND_ENABLED = "sound_enabled"
        const val KEY_HAPTIC_STRENGTH = "haptic_strength"

        const val EMOJI_BTN_TOP_BAR = 0
        const val EMOJI_BTN_BOTTOM_ROW = 1
        const val EMOJI_BTN_HIDDEN = 2

        const val HAPTIC_LIGHT = 0
        const val HAPTIC_MEDIUM = 1
        const val HAPTIC_STRONG = 2

        const val MIN_LONG_PRESS_MS = 200
        const val MAX_LONG_PRESS_MS = 600
        const val DEFAULT_LONG_PRESS_MS = 340

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

    var longPressEnterAction: Int
        get() = prefs.getInt(KEY_LONG_PRESS_ENTER_ACTION, ENTER_LONG_PRESS_POPUP)
            .coerceIn(ENTER_LONG_PRESS_NONE, ENTER_LONG_PRESS_POPUP)
        set(value) = prefs.edit()
            .putInt(KEY_LONG_PRESS_ENTER_ACTION, value.coerceIn(ENTER_LONG_PRESS_NONE, ENTER_LONG_PRESS_POPUP))
            .apply()

    var showEmojiKey: Boolean
        get() = prefs.getBoolean(KEY_SHOW_EMOJI_KEY, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_EMOJI_KEY, value).apply()

    var keypressAnimationEnabled: Boolean
        get() = prefs.getBoolean(KEY_KEYPRESS_ANIMATION, true)
        set(value) = prefs.edit().putBoolean(KEY_KEYPRESS_ANIMATION, value).apply()

    var viAutoRestoreEnabled: Boolean
        get() = prefs.getBoolean(KEY_VI_AUTO_RESTORE, true)
        set(value) = prefs.edit().putBoolean(KEY_VI_AUTO_RESTORE, value).apply()

    var smartPunctuationEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMART_PUNCTUATION, true)
        set(value) = prefs.edit().putBoolean(KEY_SMART_PUNCTUATION, value).apply()

    var spaceCursorGlideEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPACE_CURSOR_GLIDE, true)
        set(value) = prefs.edit().putBoolean(KEY_SPACE_CURSOR_GLIDE, value).apply()

    var spaceLongPressLangEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPACE_LONG_PRESS_LANG, true)
        set(value) = prefs.edit().putBoolean(KEY_SPACE_LONG_PRESS_LANG, value).apply()

    var swipeDeleteWordEnabled: Boolean
        get() = prefs.getBoolean(KEY_SWIPE_DELETE_WORD, true)
        set(value) = prefs.edit().putBoolean(KEY_SWIPE_DELETE_WORD, value).apply()

    var longPressDelayMs: Int
        get() = prefs.getInt(KEY_LONG_PRESS_DELAY_MS, DEFAULT_LONG_PRESS_MS)
            .coerceIn(MIN_LONG_PRESS_MS, MAX_LONG_PRESS_MS)
        set(value) = prefs.edit()
            .putInt(KEY_LONG_PRESS_DELAY_MS, value.coerceIn(MIN_LONG_PRESS_MS, MAX_LONG_PRESS_MS))
            .apply()

    var keyPopupEnabled: Boolean
        get() = prefs.getBoolean(KEY_KEY_POPUP_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_KEY_POPUP_ENABLED, value).apply()

    var showTopUtilityBar: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TOP_UTILITY_BAR, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_TOP_UTILITY_BAR, value).apply()

    var emojiButtonMode: Int
        get() {
            if (!prefs.contains(KEY_EMOJI_BUTTON_MODE) && prefs.getBoolean(KEY_SHOW_EMOJI_KEY, false)) {
                return EMOJI_BTN_BOTTOM_ROW
            }
            return prefs.getInt(KEY_EMOJI_BUTTON_MODE, EMOJI_BTN_TOP_BAR)
                .coerceIn(EMOJI_BTN_TOP_BAR, EMOJI_BTN_HIDDEN)
        }
        set(value) = prefs.edit()
            .putInt(KEY_EMOJI_BUTTON_MODE, value.coerceIn(EMOJI_BTN_TOP_BAR, EMOJI_BTN_HIDDEN))
            .apply()

    var showSettingsKey: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SETTINGS_KEY, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_SETTINGS_KEY, value).apply()

    var showCommaKey: Boolean
        get() = prefs.getBoolean(KEY_SHOW_COMMA_KEY, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_COMMA_KEY, value).apply()

    var showPeriodKey: Boolean
        get() = prefs.getBoolean(KEY_SHOW_PERIOD_KEY, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_PERIOD_KEY, value).apply()

    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SOUND_ENABLED, value).apply()

    var hapticStrength: Int
        get() = prefs.getInt(KEY_HAPTIC_STRENGTH, HAPTIC_MEDIUM)
            .coerceIn(HAPTIC_LIGHT, HAPTIC_STRONG)
        set(value) = prefs.edit()
            .putInt(KEY_HAPTIC_STRENGTH, value.coerceIn(HAPTIC_LIGHT, HAPTIC_STRONG))
            .apply()
}
