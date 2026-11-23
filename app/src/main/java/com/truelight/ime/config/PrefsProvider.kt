package com.truelight.ime.config

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.os.Process
import com.truelight.ime.emoji.EmojiHistoryManager
import com.truelight.ime.suggest.SuggestionManager

/**
 * Lightweight, non-exported ContentProvider running in the main IME process (`com.truelight.ime`).
 *
 * Bridges `:settings` (Material 3 SettingsActivity) and the pure-Framework IME process so:
 *  - Settings reads/writes hit the single authoritative SharedPreferences in the IME process.
 *  - Live IME process RAM telemetry is measured directly via Debug.getMemoryInfo() in-process.
 *  - Zero AndroidX / Material classes ever load into IME RAM.
 */
class PrefsProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.truelight.ime.prefs"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")

        const val METHOD_GET_SNAPSHOT = "get_snapshot"
        const val METHOD_GET_MEMORY = "get_memory"
        const val METHOD_PUT_BOOL = "put_bool"
        const val METHOD_PUT_INT = "put_int"
        const val METHOD_PUT_STRING = "put_string"
        const val METHOD_ADD_LANGUAGE = "add_language"
        const val METHOD_REMOVE_LANGUAGE = "remove_language"
        const val METHOD_IMPORT_DICT = "import_dict"
        const val METHOD_CLEAR_USER_DATA = "clear_user_data"
        const val METHOD_HAS_CUSTOM_DICT = "has_custom_dict"

        const val KEY_IME_PSS_KB = "ime_pss_kb"
        const val KEY_IME_PRIVATE_DIRTY_KB = "ime_private_dirty_kb"
        const val KEY_IME_DALVIK_KB = "ime_dalvik_kb"
        const val KEY_IME_NATIVE_KB = "ime_native_kb"
        const val KEY_IME_PID = "ime_pid"
        const val KEY_IME_ACTIVE = "ime_active"

        const val EXTRA_VALUE = "value"
        const val EXTRA_CONTENT = "content"
        const val EXTRA_COUNT = "count"
        const val EXTRA_EXISTS = "exists"
        const val EXTRA_REMOVED = "removed"
        const val EXTRA_FILE_WRITTEN = "file_written"
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null

        return when (method) {
            METHOD_GET_SNAPSHOT -> {
                val prefs = KeyboardPrefs(ctx)
                Bundle().apply {
                    putString(KeyboardPrefs.KEY_ENABLED_LANGUAGES, prefs.enabledLanguages.joinToString(","))
                    putString(KeyboardPrefs.KEY_ACTIVE_LANGUAGE, prefs.activeLanguageId)
                    putBoolean(KeyboardPrefs.KEY_VI_MODERN_TONE, prefs.vietnameseModernTone)
                    putInt(KeyboardPrefs.KEY_THEME_MODE, prefs.themeMode)
                    putInt(KeyboardPrefs.KEY_KEYBOARD_HEIGHT_DP, prefs.keyboardHeightDp)
                    putBoolean(KeyboardPrefs.KEY_SHOW_NUMBER_ROW, prefs.showNumberRow)
                    putBoolean(KeyboardPrefs.KEY_SHOW_SUB_LABELS, prefs.showSubLabels)
                    putBoolean(KeyboardPrefs.KEY_LONG_PRESS_SYMBOLS, prefs.longPressSymbols)
                    putBoolean(KeyboardPrefs.KEY_HAPTIC_ENABLED, prefs.hapticEnabled)
                    putBoolean(KeyboardPrefs.KEY_SUGGESTIONS_ENABLED, prefs.suggestionsEnabled)
                    putBoolean(KeyboardPrefs.KEY_AUTO_CORRECT_ENABLED, prefs.autoCorrectEnabled)
                    putBoolean(KeyboardPrefs.KEY_AUTO_CAPS, prefs.autoCapsEnabled)
                    putBoolean(KeyboardPrefs.KEY_DOUBLE_SPACE_PERIOD, prefs.doubleSpacePeriodEnabled)
                    putBoolean(KeyboardPrefs.KEY_CLIPBOARD_ENABLED, prefs.clipboardEnabled)
                    putBoolean(KeyboardPrefs.KEY_CUSTOM_CSS_ENABLED, prefs.customCssEnabled)
                    putString(KeyboardPrefs.KEY_CUSTOM_CSS, prefs.customCss)
                    putInt(KeyboardPrefs.KEY_LONG_PRESS_ENTER_ACTION, prefs.longPressEnterAction)
                    putBoolean(KeyboardPrefs.KEY_SHOW_EMOJI_KEY, prefs.showEmojiKey)
                    putInt(KeyboardPrefs.KEY_DICT_REVISION, prefs.dictRevision)
                    putInt(KeyboardPrefs.KEY_HISTORY_REVISION, prefs.historyRevision)
                    putMemoryStats(this)
                }
            }

            METHOD_GET_MEMORY -> {
                Bundle().apply { putMemoryStats(this) }
            }

            METHOD_PUT_BOOL -> {
                val key = arg ?: return Bundle.EMPTY
                val value = when {
                    extras == null -> false
                    extras.containsKey(EXTRA_VALUE) -> extras.getBoolean(EXTRA_VALUE)
                    extras.containsKey(key) -> extras.getBoolean(key)
                    else -> false
                }
                val prefs = KeyboardPrefs(ctx)
                when (key) {
                    KeyboardPrefs.KEY_VI_MODERN_TONE -> prefs.vietnameseModernTone = value
                    KeyboardPrefs.KEY_SHOW_NUMBER_ROW -> prefs.showNumberRow = value
                    KeyboardPrefs.KEY_SHOW_SUB_LABELS -> prefs.showSubLabels = value
                    KeyboardPrefs.KEY_LONG_PRESS_SYMBOLS -> prefs.longPressSymbols = value
                    KeyboardPrefs.KEY_HAPTIC_ENABLED -> prefs.hapticEnabled = value
                    KeyboardPrefs.KEY_SUGGESTIONS_ENABLED -> prefs.suggestionsEnabled = value
                    KeyboardPrefs.KEY_AUTO_CORRECT_ENABLED -> prefs.autoCorrectEnabled = value
                    KeyboardPrefs.KEY_AUTO_CAPS -> prefs.autoCapsEnabled = value
                    KeyboardPrefs.KEY_DOUBLE_SPACE_PERIOD -> prefs.doubleSpacePeriodEnabled = value
                    KeyboardPrefs.KEY_CLIPBOARD_ENABLED -> prefs.clipboardEnabled = value
                    KeyboardPrefs.KEY_CUSTOM_CSS_ENABLED -> prefs.customCssEnabled = value
                    KeyboardPrefs.KEY_SHOW_EMOJI_KEY -> prefs.showEmojiKey = value
                    else -> prefs.prefs.edit().putBoolean(key, value).apply()
                }
                ctx.contentResolver.notifyChange(CONTENT_URI, null)
                Bundle.EMPTY
            }

            METHOD_PUT_INT -> {
                val key = arg ?: return Bundle.EMPTY
                val value = when {
                    extras == null -> 0
                    extras.containsKey(EXTRA_VALUE) -> extras.getInt(EXTRA_VALUE)
                    extras.containsKey(key) -> extras.getInt(key)
                    else -> 0
                }
                val prefs = KeyboardPrefs(ctx)
                when (key) {
                    KeyboardPrefs.KEY_THEME_MODE -> prefs.themeMode = value
                    KeyboardPrefs.KEY_KEYBOARD_HEIGHT_DP -> prefs.keyboardHeightDp = value
                    KeyboardPrefs.KEY_LONG_PRESS_ENTER_ACTION -> prefs.longPressEnterAction = value
                    KeyboardPrefs.KEY_DICT_REVISION -> prefs.dictRevision = value
                    KeyboardPrefs.KEY_HISTORY_REVISION -> prefs.historyRevision = value
                    else -> prefs.prefs.edit().putInt(key, value).apply()
                }
                ctx.contentResolver.notifyChange(CONTENT_URI, null)
                Bundle.EMPTY
            }

            METHOD_PUT_STRING -> {
                val key = arg ?: return Bundle.EMPTY
                val value = when {
                    extras == null -> ""
                    extras.containsKey(EXTRA_VALUE) -> extras.getString(EXTRA_VALUE) ?: ""
                    extras.containsKey(key) -> extras.getString(key) ?: ""
                    else -> ""
                }
                val prefs = KeyboardPrefs(ctx)
                when (key) {
                    KeyboardPrefs.KEY_ACTIVE_LANGUAGE -> prefs.activeLanguageId = value
                    KeyboardPrefs.KEY_ENABLED_LANGUAGES -> prefs.enabledLanguages = value.split(",")
                    KeyboardPrefs.KEY_CUSTOM_CSS -> prefs.customCss = value
                    else -> prefs.prefs.edit().putString(key, value).apply()
                }
                ctx.contentResolver.notifyChange(CONTENT_URI, null)
                Bundle.EMPTY
            }

            METHOD_ADD_LANGUAGE -> {
                val langId = arg?.trim()
                if (!langId.isNullOrEmpty()) {
                    val prefs = KeyboardPrefs(ctx)
                    prefs.addLanguage(langId)
                    prefs.activeLanguageId = langId
                    ctx.contentResolver.notifyChange(CONTENT_URI, null)
                }
                Bundle.EMPTY
            }

            METHOD_REMOVE_LANGUAGE -> {
                val langId = arg?.trim()
                val removed = if (!langId.isNullOrEmpty()) {
                    val prefs = KeyboardPrefs(ctx)
                    val ok = prefs.removeLanguage(langId)
                    if (ok) {
                        ctx.contentResolver.notifyChange(CONTENT_URI, null)
                    }
                    ok
                } else {
                    false
                }
                Bundle().apply { putBoolean(EXTRA_REMOVED, removed) }
            }

            METHOD_IMPORT_DICT -> {
                val dictKey = arg?.trim()
                if (dictKey.isNullOrEmpty()) {
                    return Bundle().apply { putInt(EXTRA_COUNT, 0) }
                }
                val count = if (extras?.getBoolean(EXTRA_FILE_WRITTEN, false) == true) {
                    extras.getInt(EXTRA_COUNT, 0)
                } else {
                    val content = extras?.getString(EXTRA_CONTENT) ?: ""
                    SuggestionManager.importCustomDictionary(ctx, dictKey, content)
                }
                val prefs = KeyboardPrefs(ctx)
                prefs.bumpDictRevision()
                ctx.contentResolver.notifyChange(CONTENT_URI, null)
                Bundle().apply { putInt(EXTRA_COUNT, count) }
            }

            METHOD_CLEAR_USER_DATA -> {
                SuggestionManager(ctx).clearAllUserDictionaries()
                EmojiHistoryManager(ctx).clearHistory()
                val prefs = KeyboardPrefs(ctx)
                prefs.bumpDictRevision()
                prefs.bumpHistoryRevision()
                ctx.contentResolver.notifyChange(CONTENT_URI, null)
                Bundle.EMPTY
            }

            METHOD_HAS_CUSTOM_DICT -> {
                val dictKey = arg?.trim()
                val exists = !dictKey.isNullOrEmpty() && SuggestionManager.hasCustomDictionary(ctx, dictKey)
                Bundle().apply { putBoolean(EXTRA_EXISTS, exists) }
            }

            else -> null
        }
    }

    private fun putMemoryStats(bundle: Bundle) {
        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
        bundle.putInt(KEY_IME_PSS_KB, memInfo.totalPss)
        bundle.putInt(KEY_IME_PRIVATE_DIRTY_KB, memInfo.totalPrivateDirty)
        bundle.putInt(KEY_IME_DALVIK_KB, memInfo.dalvikPss)
        bundle.putInt(KEY_IME_NATIVE_KB, memInfo.nativePss)
        bundle.putInt(KEY_IME_PID, Process.myPid())
        bundle.putBoolean(KEY_IME_ACTIVE, com.truelight.ime.TrueLightIME.isKeyboardVisible)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
