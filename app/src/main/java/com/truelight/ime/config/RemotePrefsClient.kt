package com.truelight.ime.config

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.TransactionTooLargeException
import com.truelight.ime.suggest.SuggestionManager

typealias ImeMemoryStats = RemotePrefsClient.ImeMemoryStats

/**
 * Cross-process IPC client used by SettingsActivity (`:settings` process) to read/write
 * settings and query live IME process memory telemetry from [PrefsProvider] in the main
 * IME process (`com.truelight.ime`).
 *
 * Uses single-turn main-thread snapshot caching so bulk UI syncs perform a single Binder IPC
 * while always staying fresh across turns and immediately invalidating on every write.
 */
class RemotePrefsClient(context: Context) {

    data class ImeMemoryStats(
        val totalPssKb: Int,
        val privateDirtyKb: Int,
        val dalvikPssKb: Int,
        val nativePssKb: Int,
        val imePid: Int,
        val isKeyboardActive: Boolean = false
    )

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cachedSnapshot: Bundle? = null
    private var clearScheduled = false
    private val clearCacheRunnable = Runnable {
        cachedSnapshot = null
        clearScheduled = false
    }

    fun invalidateCache() {
        cachedSnapshot = null
    }

    private fun getSnapshot(): Bundle? {
        val isMainThread = Looper.myLooper() == Looper.getMainLooper()
        if (isMainThread) {
            cachedSnapshot?.let { return it }
        }
        val snapshot = callProvider(PrefsProvider.METHOD_GET_SNAPSHOT, null, null)
        if (isMainThread && snapshot != null) {
            cachedSnapshot = snapshot
            if (!clearScheduled) {
                clearScheduled = true
                mainHandler.post(clearCacheRunnable)
            }
        }
        return snapshot
    }

    private fun callProvider(method: String, arg: String?, extras: Bundle?): Bundle? {
        return try {
            resolver.call(PrefsProvider.CONTENT_URI, method, arg, extras)
        } catch (_: Exception) {
            null
        }
    }

    private fun putBool(key: String, value: Boolean) {
        invalidateCache()
        val extras = Bundle(1).apply { putBoolean(PrefsProvider.EXTRA_VALUE, value) }
        callProvider(PrefsProvider.METHOD_PUT_BOOL, key, extras)
    }

    private fun putInt(key: String, value: Int) {
        invalidateCache()
        val extras = Bundle(1).apply { putInt(PrefsProvider.EXTRA_VALUE, value) }
        callProvider(PrefsProvider.METHOD_PUT_INT, key, extras)
    }

    private fun putString(key: String, value: String) {
        invalidateCache()
        val extras = Bundle(1).apply { putString(PrefsProvider.EXTRA_VALUE, value) }
        callProvider(PrefsProvider.METHOD_PUT_STRING, key, extras)
    }

    fun getImeMemoryStats(): ImeMemoryStats {
        val bundle = callProvider(PrefsProvider.METHOD_GET_MEMORY, null, null)
        return ImeMemoryStats(
            totalPssKb = bundle?.getInt(PrefsProvider.KEY_IME_PSS_KB, 0) ?: 0,
            privateDirtyKb = bundle?.getInt(PrefsProvider.KEY_IME_PRIVATE_DIRTY_KB, 0) ?: 0,
            dalvikPssKb = bundle?.getInt(PrefsProvider.KEY_IME_DALVIK_KB, 0) ?: 0,
            nativePssKb = bundle?.getInt(PrefsProvider.KEY_IME_NATIVE_KB, 0) ?: 0,
            imePid = bundle?.getInt(PrefsProvider.KEY_IME_PID, -1) ?: -1,
            isKeyboardActive = bundle?.getBoolean(PrefsProvider.KEY_IME_ACTIVE, false) ?: false
        )
    }

    val isKeyboardActive: Boolean
        get() = getSnapshot()?.getBoolean(PrefsProvider.KEY_IME_ACTIVE, false) ?: false

    var enabledLanguages: List<String>
        get() {
            val raw = getSnapshot()?.getString(
                KeyboardPrefs.KEY_ENABLED_LANGUAGES,
                KeyboardPrefs.DEFAULT_LANGUAGE
            ) ?: KeyboardPrefs.DEFAULT_LANGUAGE
            val list = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            return if (list.isEmpty()) listOf(KeyboardPrefs.DEFAULT_LANGUAGE) else list
        }
        set(value) {
            val clean = value.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val finalList = if (clean.isEmpty()) listOf(KeyboardPrefs.DEFAULT_LANGUAGE) else clean
            putString(KeyboardPrefs.KEY_ENABLED_LANGUAGES, finalList.joinToString(","))
        }

    var activeLanguageId: String
        get() {
            val snapshot = getSnapshot()
            val active = snapshot?.getString(
                KeyboardPrefs.KEY_ACTIVE_LANGUAGE,
                KeyboardPrefs.DEFAULT_LANGUAGE
            ) ?: KeyboardPrefs.DEFAULT_LANGUAGE
            val enabled = enabledLanguages
            return if (active in enabled) active else enabled.first()
        }
        set(value) = putString(KeyboardPrefs.KEY_ACTIVE_LANGUAGE, value)

    var vietnameseModernTone: Boolean
        get() = getSnapshot()?.getBoolean(KeyboardPrefs.KEY_VI_MODERN_TONE, false) ?: false
        set(value) = putBool(KeyboardPrefs.KEY_VI_MODERN_TONE, value)

    var themeMode: Int
        get() = getSnapshot()?.getInt(KeyboardPrefs.KEY_THEME_MODE, KeyboardPrefs.THEME_SYSTEM)
            ?: KeyboardPrefs.THEME_SYSTEM
        set(value) = putInt(KeyboardPrefs.KEY_THEME_MODE, value)

    var keyboardHeightDp: Int
        get() = (getSnapshot()?.getInt(KeyboardPrefs.KEY_KEYBOARD_HEIGHT_DP, KeyboardPrefs.DEFAULT_HEIGHT_DP)
            ?: KeyboardPrefs.DEFAULT_HEIGHT_DP)
            .coerceIn(KeyboardPrefs.MIN_HEIGHT_DP, KeyboardPrefs.MAX_HEIGHT_DP)
        set(value) = putInt(
            KeyboardPrefs.KEY_KEYBOARD_HEIGHT_DP,
            value.coerceIn(KeyboardPrefs.MIN_HEIGHT_DP, KeyboardPrefs.MAX_HEIGHT_DP)
        )

    var showNumberRow: Boolean
        get() = getSnapshot()?.getBoolean(KeyboardPrefs.KEY_SHOW_NUMBER_ROW, false) ?: false
        set(value) = putBool(KeyboardPrefs.KEY_SHOW_NUMBER_ROW, value)

    var showSubLabels: Boolean
        get() = getSnapshot()?.getBoolean(KeyboardPrefs.KEY_SHOW_SUB_LABELS, true) ?: true
        set(value) = putBool(KeyboardPrefs.KEY_SHOW_SUB_LABELS, value)

    var longPressSymbols: Boolean
        get() = getSnapshot()?.getBoolean(KeyboardPrefs.KEY_LONG_PRESS_SYMBOLS, true) ?: true
        set(value) = putBool(KeyboardPrefs.KEY_LONG_PRESS_SYMBOLS, value)

    var hapticEnabled: Boolean
        get() = getSnapshot()?.getBoolean(KeyboardPrefs.KEY_HAPTIC_ENABLED, true) ?: true
        set(value) = putBool(KeyboardPrefs.KEY_HAPTIC_ENABLED, value)

    var suggestionsEnabled: Boolean
        get() = getSnapshot()?.getBoolean(KeyboardPrefs.KEY_SUGGESTIONS_ENABLED, true) ?: true
        set(value) = putBool(KeyboardPrefs.KEY_SUGGESTIONS_ENABLED, value)

    var autoCorrectEnabled: Boolean
        get() {
            val snapshot = getSnapshot()
            val suggestionsOn = snapshot?.getBoolean(KeyboardPrefs.KEY_SUGGESTIONS_ENABLED, true) ?: true
            return suggestionsOn && (snapshot?.getBoolean(KeyboardPrefs.KEY_AUTO_CORRECT_ENABLED, false) ?: false)
        }
        set(value) = putBool(KeyboardPrefs.KEY_AUTO_CORRECT_ENABLED, value)

    var autoCapsEnabled: Boolean
        get() = getSnapshot()?.getBoolean(KeyboardPrefs.KEY_AUTO_CAPS, true) ?: true
        set(value) = putBool(KeyboardPrefs.KEY_AUTO_CAPS, value)

    var doubleSpacePeriodEnabled: Boolean
        get() = getSnapshot()?.getBoolean(KeyboardPrefs.KEY_DOUBLE_SPACE_PERIOD, true) ?: true
        set(value) = putBool(KeyboardPrefs.KEY_DOUBLE_SPACE_PERIOD, value)

    var clipboardEnabled: Boolean
        get() = getSnapshot()?.getBoolean(KeyboardPrefs.KEY_CLIPBOARD_ENABLED, true) ?: true
        set(value) = putBool(KeyboardPrefs.KEY_CLIPBOARD_ENABLED, value)

    var customCssEnabled: Boolean
        get() = getSnapshot()?.getBoolean(KeyboardPrefs.KEY_CUSTOM_CSS_ENABLED, false) ?: false
        set(value) = putBool(KeyboardPrefs.KEY_CUSTOM_CSS_ENABLED, value)

    var customCss: String
        get() = getSnapshot()?.getString(KeyboardPrefs.KEY_CUSTOM_CSS, "") ?: ""
        set(value) = putString(KeyboardPrefs.KEY_CUSTOM_CSS, value)

    var longPressEnterAction: Int
        get() = (getSnapshot()?.getInt(
            KeyboardPrefs.KEY_LONG_PRESS_ENTER_ACTION,
            KeyboardPrefs.ENTER_LONG_PRESS_EMOJI
        ) ?: KeyboardPrefs.ENTER_LONG_PRESS_EMOJI)
            .coerceIn(KeyboardPrefs.ENTER_LONG_PRESS_NONE, KeyboardPrefs.ENTER_LONG_PRESS_NEWLINE)
        set(value) = putInt(
            KeyboardPrefs.KEY_LONG_PRESS_ENTER_ACTION,
            value.coerceIn(KeyboardPrefs.ENTER_LONG_PRESS_NONE, KeyboardPrefs.ENTER_LONG_PRESS_NEWLINE)
        )

    var showEmojiKey: Boolean
        get() = getSnapshot()?.getBoolean(KeyboardPrefs.KEY_SHOW_EMOJI_KEY, true) ?: true
        set(value) = putBool(KeyboardPrefs.KEY_SHOW_EMOJI_KEY, value)

    fun addLanguage(langId: String) {
        invalidateCache()
        callProvider(PrefsProvider.METHOD_ADD_LANGUAGE, langId, null)
    }

    fun removeLanguage(langId: String): Boolean {
        invalidateCache()
        val res = callProvider(PrefsProvider.METHOD_REMOVE_LANGUAGE, langId, null)
        return res?.getBoolean(PrefsProvider.EXTRA_REMOVED, false) ?: false
    }

    fun importDictionary(dictKey: String, content: String): Int {
        invalidateCache()
        return try {
            val extras = Bundle(1).apply { putString(PrefsProvider.EXTRA_CONTENT, content) }
            val res = resolver.call(
                PrefsProvider.CONTENT_URI,
                PrefsProvider.METHOD_IMPORT_DICT,
                dictKey,
                extras
            )
            res?.getInt(PrefsProvider.EXTRA_COUNT, 0) ?: 0
        } catch (_: TransactionTooLargeException) {
            // Large offline wordlist fallback: write directly to shared app filesDir, then notify IME to reload
            val count = SuggestionManager.importCustomDictionary(appContext, dictKey, content)
            val extras = Bundle(2).apply {
                putBoolean(PrefsProvider.EXTRA_FILE_WRITTEN, true)
                putInt(PrefsProvider.EXTRA_COUNT, count)
            }
            callProvider(PrefsProvider.METHOD_IMPORT_DICT, dictKey, extras)
            count
        } catch (_: Exception) {
            0
        }
    }

    fun clearAllUserData() {
        invalidateCache()
        callProvider(PrefsProvider.METHOD_CLEAR_USER_DATA, null, null)
    }

    fun hasCustomDictionary(dictKey: String): Boolean {
        val res = callProvider(PrefsProvider.METHOD_HAS_CUSTOM_DICT, dictKey, null)
        return res?.getBoolean(PrefsProvider.EXTRA_EXISTS, false) ?: false
    }
}
