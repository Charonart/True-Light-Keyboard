package com.truelight.ime

import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import com.truelight.ime.bridge.InputConnectionBroker
import com.truelight.ime.clipboard.ClipboardHistoryManager
import com.truelight.ime.config.KeyboardConfig
import com.truelight.ime.config.KeyboardPrefs
import com.truelight.ime.config.PrefsProvider
import com.truelight.ime.emoji.EmojiSearchIndex
import com.truelight.ime.engine.InputEngine
import com.truelight.ime.engine.SimpleWordEngine
import com.truelight.ime.lang.LanguageRegistry
import com.truelight.ime.lang.LanguageSpec
import com.truelight.ime.suggest.SuggestionManager
import com.truelight.ime.ui.KeyboardCanvasView

/**
 * True Light Keyboard - Ultra-lightweight, zero-allocation, global Android IME.
 *
 * Defaults to English (US); additional Group 1 languages (Vietnamese Telex/VNI,
 * French, German, Spanish, Russian, Korean) and offline dictionaries are managed in Settings.
 */
class TrueLightIME : InputMethodService(),
    KeyboardCanvasView.OnKeyboardActionListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        @Volatile
        var isKeyboardVisible: Boolean = false

        private const val STANDBY_TRIM_DELAY_MS = 8_000L
    }

    private var keyboardView: KeyboardCanvasView? = null
    private lateinit var prefs: KeyboardPrefs
    private lateinit var suggestionManager: SuggestionManager
    private lateinit var clipboardManager: ClipboardHistoryManager
    private val broker = InputConnectionBroker()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeLanguage: LanguageSpec = LanguageRegistry.EN_US
    private var activeEngine: InputEngine = SimpleWordEngine()

    private val standbyTrimRunnable = Runnable {
        if (!isKeyboardVisible) {
            keyboardView?.releaseStandbyMemory()
            EmojiSearchIndex.release()
            if (::clipboardManager.isInitialized) {
                clipboardManager.pruneExpired()
            }
            if (::suggestionManager.isInitialized) {
                suggestionManager.clearSuggestions()
                suggestionManager.clearUndo()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = KeyboardPrefs(this)
        suggestionManager = SuggestionManager(this)
        clipboardManager = ClipboardHistoryManager(this)
        clipboardManager.setEnabled(prefs.clipboardEnabled)
        clipboardManager.setAutoClearMinutes(prefs.clipboardAutoClearMinutes)
        prefs.prefs.registerOnSharedPreferenceChangeListener(this)
        loadLanguageAndSuggestions()
    }

    private fun setKeyboardVisibility(visible: Boolean) {
        if (isKeyboardVisible != visible) {
            isKeyboardVisible = visible
            try {
                contentResolver.notifyChange(PrefsProvider.CONTENT_URI, null)
            } catch (_: Exception) {
                // Ignore if resolver is unavailable during teardown
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(standbyTrimRunnable)
        EmojiSearchIndex.release()
        setKeyboardVisibility(false)
        prefs.prefs.unregisterOnSharedPreferenceChangeListener(this)
        clipboardManager.setEnabled(false)
        suggestionManager.configure(activeLanguage.dictKey, suggestionsOn = false, autoCorrectOn = false)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val view = KeyboardCanvasView(this)
        view.actionListener = this
        view.suggestionManager = suggestionManager
        view.clipboardManager = clipboardManager
        keyboardView = view
        applySettingsToView()
        return view
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        mainHandler.removeCallbacks(standbyTrimRunnable)
        setKeyboardVisibility(true)

        activeEngine.reset()
        broker.updateTarget(currentInputConnection, info)
        suggestionManager.updateEditorPrivacy(info)
        clipboardManager.setAutoClearMinutes(prefs.clipboardAutoClearMinutes)
        loadLanguageAndSuggestions()

        val view = keyboardView ?: return
        view.currentImeAction = info.imeOptions and EditorInfo.IME_MASK_ACTION
        applySettingsToView()

        if (broker.shouldUseNumpad()) {
            view.switchToNumpad()
        } else {
            view.switchToAlpha()
        }
        updateAutoCaps()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        mainHandler.removeCallbacks(standbyTrimRunnable)
        setKeyboardVisibility(true)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        setKeyboardVisibility(false)
        broker.finishComposingText()
        activeEngine.reset()
        suggestionManager.clearSuggestions()
        suggestionManager.clearUndo()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        setKeyboardVisibility(false)
        broker.finishComposingText()
        activeEngine.reset()
        suggestionManager.clearSuggestions()
        suggestionManager.clearUndo()
        keyboardView?.releaseTransientUiMemory()
        if (::clipboardManager.isInitialized) {
            clipboardManager.pruneExpired()
        }
        mainHandler.removeCallbacks(standbyTrimRunnable)
        if (::prefs.isInitialized && prefs.aggressiveStandbyTrimEnabled) {
            mainHandler.postDelayed(standbyTrimRunnable, STANDBY_TRIM_DELAY_MS)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            keyboardView?.releaseTransientUiMemory()
            keyboardView?.releaseStandbyMemory()
            EmojiSearchIndex.release()
            if (::prefs.isInitialized && !prefs.suggestionsEnabled && ::suggestionManager.isInitialized) {
                suggestionManager.configure(
                    dictKey = activeLanguage.dictKey,
                    suggestionsOn = false,
                    autoCorrectOn = false
                )
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        keyboardView?.refreshTheme(this, prefs.themeMode, prefs.customCssEnabled, prefs.customCss)
        updateNavigationBarColor()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            KeyboardPrefs.KEY_KEYBOARD_HEIGHT_DP -> {
                keyboardView?.applyHeightOnly(prefs.keyboardHeightDp)
            }
            KeyboardPrefs.KEY_DICT_REVISION -> {
                suggestionManager.forceReload()
                keyboardView?.invalidate()
            }
            KeyboardPrefs.KEY_HISTORY_REVISION -> {
                keyboardView?.clearEmojiHistoryCache()
                clipboardManager.clear(includePinned = true)
                keyboardView?.invalidate()
            }
            KeyboardPrefs.KEY_THEME_MODE,
            KeyboardPrefs.KEY_CUSTOM_CSS,
            KeyboardPrefs.KEY_CUSTOM_CSS_ENABLED -> {
                keyboardView?.refreshTheme(this, prefs.themeMode, prefs.customCssEnabled, prefs.customCss)
                updateNavigationBarColor()
            }
            KeyboardPrefs.KEY_CLIPBOARD_ENABLED -> {
                clipboardManager.setEnabled(prefs.clipboardEnabled)
                keyboardView?.invalidate()
            }
            KeyboardPrefs.KEY_CLIPBOARD_AUTO_CLEAR_MIN -> {
                clipboardManager.setAutoClearMinutes(prefs.clipboardAutoClearMinutes)
                keyboardView?.invalidate()
            }
            KeyboardPrefs.KEY_ONE_HANDED_MODE -> {
                applySettingsToView()
            }
            KeyboardPrefs.KEY_AGGRESSIVE_STANDBY_TRIM -> {
                if (!prefs.aggressiveStandbyTrimEnabled) {
                    mainHandler.removeCallbacks(standbyTrimRunnable)
                }
            }
            KeyboardPrefs.KEY_AUTO_CAPS -> {
                updateAutoCaps()
            }
            KeyboardPrefs.KEY_VI_MODERN_TONE,
            KeyboardPrefs.KEY_VI_AUTO_RESTORE -> {
                loadLanguageAndSuggestions()
            }
            KeyboardPrefs.KEY_DOUBLE_SPACE_PERIOD,
            KeyboardPrefs.KEY_SMART_PUNCTUATION -> {
                // Read dynamically on key press; no layout rebuild needed
            }
            else -> {
                loadLanguageAndSuggestions()
                applySettingsToView()
            }
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)

        if (activeEngine.length() > 0) {
            val composingCleared = candidatesStart < 0 && candidatesEnd < 0
            val cursorMovedOutside = newSelStart != candidatesEnd || newSelEnd != candidatesEnd
            if (composingCleared || cursorMovedOutside) {
                broker.finishComposingText()
                activeEngine.reset()
                suggestionManager.clearSuggestions()
                keyboardView?.invalidate()
            }
        }
        updateAutoCaps()
    }

    private fun loadLanguageAndSuggestions() {
        val langSpec = LanguageRegistry.getById(prefs.activeLanguageId)
        val modernTone = prefs.vietnameseModernTone
        val viAutoRestore = prefs.viAutoRestoreEnabled

        if (langSpec.id != activeLanguage.id) {
            activeLanguage = langSpec
            activeEngine = langSpec.createEngine(modernTone, viAutoRestore)
        } else {
            // Update Vietnamese tone placement & auto-restore dynamically if toggled in Settings
            when (val eng = activeEngine) {
                is com.truelight.ime.engine.TelexFsmEngine -> {
                    eng.modernTonePlacement = modernTone
                    eng.autoRestoreEnabled = viAutoRestore
                }
                is com.truelight.ime.engine.VniFsmEngine -> {
                    eng.modernTonePlacement = modernTone
                    eng.autoRestoreEnabled = viAutoRestore
                }
            }
        }

        suggestionManager.configure(
            dictKey = activeLanguage.dictKey,
            suggestionsOn = prefs.suggestionsEnabled,
            autoCorrectOn = prefs.autoCorrectEnabled
        )
    }

    private fun applySettingsToView() {
        val view = keyboardView ?: return
        val config = KeyboardConfig(
            themeMode = prefs.themeMode,
            customCssEnabled = prefs.customCssEnabled,
            customCss = prefs.customCss,
            heightDp = prefs.keyboardHeightDp,
            showNumberRow = prefs.showNumberRow,
            showSubLabels = prefs.showSubLabels,
            longPressSymbols = prefs.longPressSymbols,
            keyPopupEnabled = prefs.keyPopupEnabled,
            keypressAnimation = prefs.keypressAnimationEnabled,
            showTopUtilityBar = prefs.showTopUtilityBar,
            emojiButtonMode = prefs.emojiButtonMode,
            showSettingsKey = prefs.showSettingsKey,
            showCommaKey = prefs.showCommaKey,
            showPeriodKey = prefs.showPeriodKey,
            longPressEnterAction = prefs.longPressEnterAction,
            longPressDelayMs = prefs.longPressDelayMs,
            spaceCursorGlide = prefs.spaceCursorGlideEnabled,
            spaceLongPressLang = prefs.spaceLongPressLangEnabled,
            swipeDeleteWord = prefs.swipeDeleteWordEnabled,
            hapticEnabled = prefs.hapticEnabled,
            hapticStrength = prefs.hapticStrength,
            soundEnabled = prefs.soundEnabled,
            languageSpec = activeLanguage,
            multipleLanguages = prefs.enabledLanguages.size > 1,
            oneHandedMode = prefs.oneHandedMode
        )
        view.applyConfig(this, config)
        updateNavigationBarColor()
    }

    private fun updateAutoCaps() {
        val view = keyboardView ?: return
        if (!prefs.autoCapsEnabled) {
            view.setAutoShift(false)
            return
        }
        val info = currentInputEditorInfo
        val ic = currentInputConnection
        if (info == null || ic == null || info.inputType == InputType.TYPE_NULL) {
            view.setAutoShift(false)
            return
        }
        val capsMode = ic.getCursorCapsMode(info.inputType)
        view.setAutoShift(capsMode != 0)
    }

    @Suppress("DEPRECATION")
    private fun updateNavigationBarColor() {
        val view = keyboardView ?: return
        val win = window?.window ?: return
        val bgColor = view.theme.colorBgKeyboard
        win.navigationBarColor = bgColor

        val luminance = (0.2126 * Color.red(bgColor) +
            0.7152 * Color.green(bgColor) +
            0.0722 * Color.blue(bgColor)) / 255.0
        val isLight = luminance > 0.5

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = win.insetsController
            if (controller != null) {
                val mask = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                controller.setSystemBarsAppearance(if (isLight) mask else 0, mask)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val decor = win.decorView
            val flags = decor.systemUiVisibility
            decor.systemUiVisibility = if (isLight) {
                flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        }
    }

    // --- OnKeyboardActionListener Implementation ---

    override fun onChar(c: Char) {
        suggestionManager.clearUndo()
        val canProcess = !broker.isDirectDispatch() &&
            (c.isLetter() || (activeEngine.acceptsDigits && c.isDigit() && activeEngine.length() > 0) || c == '\'')

        if (canProcess) {
            val result = activeEngine.processChar(c)
            if (result.isWordTerminated) {
                if (result.text.isNotEmpty()) {
                    broker.finishComposingText()
                    suggestionManager.learnCommittedWord(result.text)
                }
                suggestionManager.clearSuggestions()

                // If word terminated due to MAX_WORD_LENGTH overflow on a valid word char,
                // start a fresh composing token with the incoming character.
                val retry = if (c.isLetter() || c == '\'') activeEngine.processChar(c) else null
                if (retry != null && !retry.isWordTerminated) {
                    val composingActive = broker.setComposingText(retry.text, 1)
                    if (!composingActive) {
                        activeEngine.reset()
                        suggestionManager.clearSuggestions()
                    } else {
                        suggestionManager.updateForWord(retry.text)
                    }
                } else {
                    broker.commitCharWithSmartPunctuation(c, prefs.smartPunctuationEnabled)
                }
            } else {
                val composingActive = broker.setComposingText(result.text, 1)
                if (!composingActive) {
                    activeEngine.reset()
                    suggestionManager.clearSuggestions()
                } else {
                    suggestionManager.updateForWord(result.text)
                }
            }
        } else {
            if (activeEngine.length() > 0) {
                val typed = activeEngine.getComposingText()
                broker.finishComposingText()
                activeEngine.reset()
                suggestionManager.learnCommittedWord(typed)
            }
            suggestionManager.clearSuggestions()
            broker.commitCharWithSmartPunctuation(c, prefs.smartPunctuationEnabled)
        }
        keyboardView?.invalidate()
    }

    override fun onSpace() {
        broker.handleSpace(activeEngine, suggestionManager, prefs.doubleSpacePeriodEnabled)
        updateAutoCaps()
        keyboardView?.invalidate()
    }

    override fun onBackspace() {
        broker.handleBackspace(activeEngine, suggestionManager)
        updateAutoCaps()
        keyboardView?.invalidate()
    }

    override fun onDeleteWord() {
        broker.handleDeleteWord(activeEngine, suggestionManager)
        updateAutoCaps()
        keyboardView?.invalidate()
    }

    override fun onMoveCursor(delta: Int) {
        broker.moveCursor(delta, activeEngine, suggestionManager)
        updateAutoCaps()
        keyboardView?.invalidate()
    }

    override fun onSelectCursor(delta: Int) {
        if (activeEngine.length() > 0) {
            broker.finishComposingText()
            activeEngine.reset()
            suggestionManager.clearSuggestions()
        }
        suggestionManager.clearUndo()
        broker.selectCursor(delta)
        keyboardView?.invalidate()
    }

    override fun onOneHandedModeChanged(mode: Int) {
        prefs.oneHandedMode = mode
        try {
            contentResolver.notifyChange(PrefsProvider.CONTENT_URI, null)
        } catch (_: Exception) {
            // Ignore if resolver is unavailable
        }
    }

    override fun onEnter() {
        val isShiftPressed = keyboardView?.layout?.isShifted == true
        broker.handleEnter(isShiftPressed, activeEngine, suggestionManager)
        updateAutoCaps()
        keyboardView?.invalidate()
    }

    override fun onInsertNewline() {
        broker.handleInsertNewline(activeEngine, suggestionManager)
        updateAutoCaps()
        keyboardView?.invalidate()
    }

    override fun onEmoji(emoji: String) {
        if (activeEngine.length() > 0) {
            broker.finishComposingText()
            activeEngine.reset()
            suggestionManager.clearSuggestions()
        }
        suggestionManager.clearUndo()
        broker.commitText(emoji, 1)
        keyboardView?.invalidate()
    }

    override fun onLanguageToggle() {
        if (activeEngine.length() > 0) {
            broker.finishComposingText()
            activeEngine.reset()
            suggestionManager.clearSuggestions()
        }
        prefs.cycleNextLanguage()
        contentResolver.notifyChange(PrefsProvider.CONTENT_URI, null)
        loadLanguageAndSuggestions()
        applySettingsToView()
    }

    override fun onOpenSettings() {
        val intent = Intent().apply {
            setClassName(this@TrueLightIME, "com.truelight.ime.settings.SettingsActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    override fun onSuggestionSelected(word: String) {
        broker.commitSuggestion(word, activeEngine, suggestionManager)
        updateAutoCaps()
        keyboardView?.invalidate()
    }

    override fun onClipSelected(text: String) {
        if (activeEngine.length() > 0) {
            broker.finishComposingText()
            activeEngine.reset()
            suggestionManager.clearSuggestions()
        }
        suggestionManager.clearUndo()
        broker.commitText(text, 1)
        updateAutoCaps()
        keyboardView?.invalidate()
    }

    override fun onImageClipSelected(uriString: String, mimeType: String) {
        if (activeEngine.length() > 0) {
            broker.finishComposingText()
            activeEngine.reset()
            suggestionManager.clearSuggestions()
        }
        suggestionManager.clearUndo()
        broker.commitImageClip(uriString, mimeType)
        keyboardView?.invalidate()
    }
}
