package com.truelight.ime.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import com.truelight.ime.clipboard.ClipboardHistoryManager
import com.truelight.ime.config.KeyboardConfig
import com.truelight.ime.config.KeyboardPrefs
import com.truelight.ime.emoji.EmojiCanvasPanel
import com.truelight.ime.emoji.EmojiData
import com.truelight.ime.emoji.EmojiHistoryManager
import com.truelight.ime.emoji.EmojiSearchIndex
import com.truelight.ime.suggest.SuggestionManager
import com.truelight.ime.ui.theme.KeyboardTheme
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 100% Pure Android Canvas Custom View.
 * Features:
 *  - Multi-touch 4-pointer tracking with Key Rollover & Shift+Letter Chording
 *  - Spacebar horizontal cursor glide & Backspace swipe-to-delete-word gestures
 *  - Crisp DPI-independent Vector Canvas Path icons (Shift, Backspace, Contextual Enter, Emoji, Gear, Clipboard)
 *  - Idle utility strip & inline Canvas Clipboard Picker overlay (zero extra Views)
 *  - Lazy-loaded EmojiCanvasPanel (0 MB emoji RAM until opened)
 *  - 100% ZERO-ALLOCATION inside onDraw(Canvas)
 */
class KeyboardCanvasView(context: Context) : View(context) {

    interface OnKeyboardActionListener {
        fun onChar(c: Char)
        fun onSpace()
        fun onBackspace()
        fun onDeleteWord()
        fun onMoveCursor(delta: Int)
        fun onSelectCursor(delta: Int)
        fun onEnter()
        fun onInsertNewline()
        fun onEmoji(emoji: String)
        fun onLanguageToggle()
        fun onOneHandedModeChanged(mode: Int)
        fun onOpenSettings()
        fun onSuggestionSelected(word: String)
        fun onClipSelected(text: String)
        fun onImageClipSelected(uriString: String, mimeType: String)
    }

    companion object {
        private const val MAX_POINTERS = 4
        private const val STRIP_ACTION_NONE = 0
        private const val STRIP_ACTION_CLIPBOARD = 1
        private const val STRIP_ACTION_EMOJI = 2
        private const val STRIP_ACTION_LANG_PILL = 3
        private const val STRIP_ACTION_LANG_CENTER = 4
        private const val STRIP_ACTION_SETTINGS = 5
        private const val STRIP_ACTION_ONE_HANDED = 6

        private const val CLIP_TITLE = "Clipboard"
        private const val CLIP_BACK = "Back"
        private const val CLIP_CLEAR = "Clear"
        private const val CLIP_EMPTY = "Clipboard is empty"
        private const val CLIP_IMAGE_LABEL = "Image"
        private const val CLIP_IMAGE_SUB = "Tap to insert"
        private const val CLIP_IMAGE_PNG = "PNG image · Tap to insert"
        private const val CLIP_IMAGE_JPEG = "JPEG image · Tap to insert"
        private const val CLIP_IMAGE_WEBP = "WebP image · Tap to insert"
        private const val CLIP_IMAGE_GIF = "GIF image · Tap to insert"
        private const val SEARCH_PLACEHOLDER = "Search…"
        private const val SEARCH_NO_MATCH = "No match"
        private const val ELLIPSIS = "…"

        private const val MAX_CLIP_THUMBNAILS = 5
        private const val CLIP_THUMB_TARGET_PX = 120
        private const val MAX_EMOJI_SEARCH_LEN = 28

        private const val ANIM_BUFFER_SIZE = 8
        private const val ANIM_DURATION_MS = 95L
        private const val MAX_ACCENT_VARIANTS = 12
    }

    var actionListener: OnKeyboardActionListener? = null
    var suggestionManager: SuggestionManager? = null
    var clipboardManager: ClipboardHistoryManager? = null

    var currentImeAction: Int = EditorInfo.IME_ACTION_NONE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    val theme = KeyboardTheme(density)
    val layout = KeyboardLayout(theme)

    // Lazy-loaded emoji subsystem (0 MB until user opens Emoji panel)
    private var historyManager: EmojiHistoryManager? = null
    private var emojiPanel: EmojiCanvasPanel? = null

    var isEmojiMode = false
        private set

    var isEmojiSearchMode = false
        private set

    private var isClipboardOpen = false
    private var clipScrollY = 0f
    private var clipMaxScrollY = 0f
    private var clipTouchStartY = 0f
    private var clipLastTouchY = 0f
    private var clipDragging = false
    private var clipPressedIndex = -1
    private var clipPressedAction = 0 // 0 = card body, 1 = Pin, 2 = Delete
    private var clipLongPressTriggered = false
    private var clipHeaderBtnPressed = 0 // 1 = Back, 2 = Clear

    var keyboardHeightDp: Int = KeyboardPrefs.DEFAULT_HEIGHT_DP
    var oneHandedMode: Int = KeyboardPrefs.ONE_HANDED_OFF
    var hapticEnabled: Boolean = true
    var hapticStrength: Int = KeyboardPrefs.HAPTIC_MEDIUM
    var soundEnabled: Boolean = false
    var longPressSymbolsEnabled: Boolean = true
    var keyPopupEnabled: Boolean = true
    var keypressAnimationEnabled: Boolean = true
    var showTopUtilityBar: Boolean = true
    var emojiButtonMode: Int = KeyboardPrefs.EMOJI_BTN_TOP_BAR
    var showSettingsKey: Boolean = true
    var longPressEnterAction: Int = KeyboardPrefs.ENTER_LONG_PRESS_POPUP
    var longPressDelayMs: Long = 340L
    var spaceCursorGlideEnabled: Boolean = true
    var spaceLongPressLangEnabled: Boolean = true
    var swipeDeleteWordEnabled: Boolean = true

    // Cached system services & preallocated VibrationEffects (zero allocation per keypress)
    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Throwable) {
            null
        }
    }
    private val audioManager: AudioManager? by lazy {
        try {
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        } catch (_: Throwable) {
            null
        }
    }
    private val hapticEffectLight: VibrationEffect? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        VibrationEffect.createOneShot(8L, 50)
    } else {
        null
    }
    private val hapticEffectMedium: VibrationEffect? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        VibrationEffect.createOneShot(14L, 120)
    } else {
        null
    }
    private val hapticEffectStrong: VibrationEffect? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        VibrationEffect.createOneShot(22L, 220)
    } else {
        null
    }

    private val stripHeightPx: Float = 38f * density
    private val stripSideBtnWidthPx: Float = 42f * density
    private val stripLangBtnWidthPx: Float = 44f * density
    private var currentStripHeight: Float = 0f
    private var pressedSuggestionIndex: Int = -1
    private var pressedStripAction: Int = STRIP_ACTION_NONE
    private var stripPointerId: Int = -1

    // One-Handed Mode gutter geometry & touch state (preallocated -> 0 allocs in onDraw)
    private val oneHandedGutterRect = RectF()
    private val oneHandedExpandBtnRect = RectF()
    private val oneHandedFlipBtnRect = RectF()
    private var oneHandedGutterPointerId: Int = -1
    private var pressedGutterBtn: Int = 0 // 1 = Expand, 2 = Flip

    // Inline Emoji Search state & preallocated Paints (0 allocs in onDraw)
    private val emojiSearchQuery = StringBuilder(32)
    private val emojiSearchResults = ArrayList<String>(28)
    private var emojiSearchResultsScrollX = 0f
    private var emojiSearchResultsMaxScrollX = 0f
    private var emojiSearchTouchStartX = 0f
    private var emojiSearchLastTouchX = 0f
    private var emojiSearchDragging = false
    private var emojiSearchPressedResultIdx = -1
    private var emojiSearchClosePressed = false
    private val emojiSearchInputPillRect = RectF()
    private val emojiSearchCloseBtnRect = RectF()
    private val emojiSearchResultsRect = RectF()

    private val searchEmojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 22f * density
        val fontFile = File("/system/fonts/NotoColorEmoji.ttf")
        if (fontFile.exists() && fontFile.canRead()) {
            try {
                typeface = Typeface.createFromFile(fontFile)
            } catch (_: Exception) {
                typeface = Typeface.DEFAULT
            }
        }
    }
    private val searchQueryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        textSize = 13.5f * density
    }
    private val accentStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.9f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val accentTintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val accentBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = maxOf(1f, 1.15f * density)
    }

    // Lazy micro-thumbnail cache for clipboard image clips (max 5 entries, RGB_565 ~28 KB/img)
    // Recycled immediately whenever clipboard closes or keyboard hides
    private val clipThumbnails = HashMap<String, Bitmap>(6)
    private val clipFailedUris = HashSet<String>(6)
    private val clipThumbSrcRect = Rect()
    private val clipThumbDstRect = RectF()
    private val clipThumbPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // Preallocated zero-allocation 95ms keypress micro-animation ring buffer
    private val animKeys = arrayOfNulls<KeyModel>(ANIM_BUFFER_SIZE)
    private val animStartMs = LongArray(ANIM_BUFFER_SIZE)
    private var animWriteIndex: Int = 0

    // Enter long-press 2-option floating mini-popup state (0 = Emoji left, 1 = Newline right)
    private var isEnterPopupActive: Boolean = false
    private var enterPopupSlot: Int = -1
    private var enterPopupSelectedOption: Int = 0
    private var enterPopupCenterX: Float = 0f
    private val enterPopupRect = RectF()

    // Accented character long-press horizontal floating pill state (preallocated -> 0 allocs in onDraw)
    private val accentPopupChars = arrayOfNulls<String>(MAX_ACCENT_VARIANTS)
    private var accentPopupCount: Int = 0
    private var isAccentPopupActive: Boolean = false
    private var accentPopupSlot: Int = -1
    private var accentPopupSelectedIndex: Int = 0
    private var accentPopupCellWidth: Float = 0f
    private var accentPopupAnchorX: Float = 0f
    private var accentPopupDragged: Boolean = false
    private val accentPopupRect = RectF()

    // Preallocated multi-touch tracking arrays (zero allocations during typing)
    private val pointerIds = IntArray(MAX_POINTERS) { -1 }
    private val pointerKeys = arrayOfNulls<KeyModel>(MAX_POINTERS)
    private val pointerDownX = FloatArray(MAX_POINTERS)
    private val pointerDownY = FloatArray(MAX_POINTERS)
    private val pointerLongPressConsumed = BooleanArray(MAX_POINTERS)

    // Precomputed 8-tooth gear unit vectors for zero-alloc vector rendering
    private val gearCos = FloatArray(8) { cos(it * PI / 4.0).toFloat() }
    private val gearSin = FloatArray(8) { sin(it * PI / 4.0).toFloat() }

    private var activePopupKey: KeyModel? = null
    private var popupLongPressTriggered: Boolean = false

    // Gesture & timer ownership slots
    private val mainHandler = Handler(Looper.getMainLooper())
    private var longPressSlot: Int = -1
    private var deleteSlot: Int = -1
    private var deleteSwipeTriggered: Boolean = false
    private var spaceSlot: Int = -1
    private var spaceGlideActive: Boolean = false
    private var spaceGlideSelectionMode: Boolean = false
    private var spaceLastStepX: Float = 0f

    // Shift tap / double-tap / chording state
    private var lastShiftPressTime = 0L
    private var shiftHoldSlot: Int = -1
    private var shiftWasActiveBeforeHold: Boolean = false
    private var shiftUsedAsChord: Boolean = false

    private val clipLongPressRunnable = Runnable {
        if (isClipboardOpen && !clipDragging && clipPressedIndex >= 0 && clipPressedAction == 0) {
            clipLongPressTriggered = true
            val idx = clipPressedIndex
            clipPressedIndex = -1
            val cm = clipboardManager
            val clips = cm?.getClipItems()
            if (cm != null && clips != null && idx in clips.indices) {
                cm.togglePin(idx)
                triggerHaptic()
                invalidate()
            }
        }
    }

    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            val slot = deleteSlot
            if (slot in 0 until MAX_POINTERS &&
                !deleteSwipeTriggered &&
                pointerKeys[slot]?.type == KeyModel.TYPE_DELETE
            ) {
                if (isEmojiSearchMode) {
                    handleEmojiSearchBackspace()
                } else {
                    actionListener?.onBackspace()
                }
                triggerHaptic()
                playKeySound(KeyModel.TYPE_DELETE)
                mainHandler.postDelayed(this, 50L)
            }
        }
    }

    private val longPressRunnable = object : Runnable {
        override fun run() {
            val slot = longPressSlot
            if (slot !in 0 until MAX_POINTERS) return
            val key = pointerKeys[slot] ?: return

            if (key.type == KeyModel.TYPE_ENTER) {
                pointerLongPressConsumed[slot] = true
                longPressSlot = -1
                if (longPressEnterAction == KeyboardPrefs.ENTER_LONG_PRESS_POPUP &&
                    layout.currentMode != KeyboardLayout.Mode.NUMPAD
                ) {
                    activateEnterPopup(key, slot)
                    triggerHaptic()
                    invalidate()
                    return
                }
                releaseKeyVisual(key, slot)
                pointerKeys[slot] = null
                triggerHaptic()
                invalidate()
                when (longPressEnterAction) {
                    KeyboardPrefs.ENTER_LONG_PRESS_EMOJI -> setEmojiMode(true)
                    KeyboardPrefs.ENTER_LONG_PRESS_NEWLINE -> actionListener?.onInsertNewline()
                }
                return
            }

            if (key.type == KeyModel.TYPE_SPACE) {
                if (spaceLongPressLangEnabled && !spaceGlideActive && layout.hasMultipleLanguages) {
                    pointerLongPressConsumed[slot] = true
                    longPressSlot = -1
                    if (spaceSlot == slot) {
                        spaceSlot = -1
                        spaceGlideActive = false
                    }
                    releaseKeyVisual(key, slot)
                    pointerKeys[slot] = null
                    triggerHaptic()
                    invalidate()
                    actionListener?.onLanguageToggle()
                }
                return
            }

            if (key.type == KeyModel.TYPE_SWITCH_SYMBOLS || key.type == KeyModel.TYPE_SWITCH_LANG) {
                pointerLongPressConsumed[slot] = true
                longPressSlot = -1
                releaseKeyVisual(key, slot)
                pointerKeys[slot] = null
                triggerHaptic()
                invalidate()
                actionListener?.onOpenSettings()
                return
            }

            if (longPressSymbolsEnabled && key.type == KeyModel.TYPE_NORMAL && layout.currentMode == KeyboardLayout.Mode.ALPHA) {
                val variants = getAccentVariantsForKey(key)
                if (!variants.isNullOrEmpty()) {
                    pointerLongPressConsumed[slot] = true
                    longPressSlot = -1
                    activateAccentPopup(key, slot, variants, pointerDownX[slot])
                    triggerHaptic()
                    invalidate()
                    return
                }
                val sub = key.subLabel
                if (!sub.isNullOrEmpty()) {
                    pointerLongPressConsumed[slot] = true
                    popupLongPressTriggered = true
                    longPressSlot = -1
                    triggerHaptic()
                    actionListener?.onChar(sub[0])
                    invalidate()
                }
            }
        }
    }

    init {
        isClickable = true
        isFocusable = true
        syncAuxPaints()
    }

    private fun syncAuxPaints() {
        searchQueryPaint.color = theme.colorTextPrimary
        accentStrokePaint.color = theme.colorAccent
        accentTintPaint.color = theme.colorAccent
        accentTintPaint.alpha = 48
        accentBorderPaint.color = theme.colorAccent
        accentBorderPaint.alpha = 128
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mainHandler.removeCallbacks(clipLongPressRunnable)
        resetAllTouches()
        clearKeyAnimations()
        clearClipboardThumbnails()
        emojiPanel?.detach()
    }

    override fun computeScroll() {
        if (isEmojiMode && emojiPanel?.computeScrollOffset() == true) {
            postInvalidateOnAnimation()
        }
    }

    private fun getOrCreateEmojiPanel(): EmojiCanvasPanel {
        val existing = emojiPanel
        if (existing != null) return existing

        val hm = historyManager ?: EmojiHistoryManager(context).also { historyManager = it }
        val panel = EmojiCanvasPanel(
            context = context,
            theme = theme,
            historyManager = hm,
            touchSlop = touchSlop,
            onEmojiSelected = { emoji -> actionListener?.onEmoji(emoji) },
            onBackToKeyboard = { setEmojiMode(false) },
            onDeleteClicked = {
                triggerHaptic()
                playKeySound(KeyModel.TYPE_DELETE)
                actionListener?.onBackspace()
            },
            onSearchClicked = { enterEmojiSearchMode() },
            onRequestInvalidate = { postInvalidateOnAnimation() },
            onTriggerHaptic = { triggerHaptic() }
        )
        panel.longPressDelayMs = longPressDelayMs
        if (width > 0 && height > 0) {
            panel.measure(width, height)
        }
        emojiPanel = panel
        return panel
    }

    fun applyConfig(context: Context, config: KeyboardConfig) {
        theme.applyTheme(context, config.themeMode, config.customCssEnabled, config.customCss)
        syncAuxPaints()
        emojiPanel?.syncThemeColors()
        keyboardHeightDp = config.heightDp
        oneHandedMode = config.oneHandedMode
        hapticEnabled = config.hapticEnabled
        hapticStrength = config.hapticStrength
        soundEnabled = config.soundEnabled
        longPressSymbolsEnabled = config.longPressSymbols
        keyPopupEnabled = config.keyPopupEnabled
        keypressAnimationEnabled = config.keypressAnimation
        showTopUtilityBar = config.showTopUtilityBar
        emojiButtonMode = config.emojiButtonMode
        showSettingsKey = config.showSettingsKey
        longPressEnterAction = config.longPressEnterAction
        longPressDelayMs = config.longPressDelayMs
            .coerceIn(KeyboardPrefs.MIN_LONG_PRESS_MS, KeyboardPrefs.MAX_LONG_PRESS_MS)
            .toLong()
        emojiPanel?.longPressDelayMs = longPressDelayMs
        spaceCursorGlideEnabled = config.spaceCursorGlide
        spaceLongPressLangEnabled = config.spaceLongPressLang
        swipeDeleteWordEnabled = config.swipeDeleteWord

        if (!keypressAnimationEnabled) {
            clearKeyAnimations()
        }
        val showBottomEmoji = (config.emojiButtonMode == KeyboardPrefs.EMOJI_BTN_BOTTOM_ROW)
        layout.setDisplayOptions(
            numberRow = config.showNumberRow,
            subLabels = config.showSubLabels,
            emojiKey = showBottomEmoji,
            commaKey = config.showCommaKey,
            periodKey = config.showPeriodKey
        )
        layout.setLanguage(config.languageSpec, config.multipleLanguages)
        requestLayout()
        if (width > 0 && height > 0) {
            remeasureInternal(width, height)
        }
        invalidate()
    }

    /**
     * Releases lazy-loaded Emoji panel & clipboard micro-thumbnails immediately when the keyboard hides,
     * keeping background IME RAM minimal.
     */
    fun releaseTransientUiMemory() {
        clearClipboardThumbnails()
        clearKeyAnimations()
        if (!isEmojiMode) {
            emojiPanel?.detach()
            emojiPanel = null
        }
        if (!isEmojiSearchMode) {
            EmojiSearchIndex.release()
        }
    }

    /**
     * Aggressive standby memory trim: releases Emoji panel, recent history cache,
     * EmojiSearchIndex, clipboard thumbnails, and resets emoji search cleanly.
     */
    fun releaseStandbyMemory() {
        exitEmojiSearchMode(notifyInvalidate = false)
        clearClipboardThumbnails()
        clearKeyAnimations()
        emojiPanel?.detach()
        emojiPanel = null
        historyManager = null
        EmojiSearchIndex.release()
    }

    fun clearClipboardThumbnails() {
        if (clipThumbnails.isNotEmpty()) {
            for (bmp in clipThumbnails.values) {
                if (!bmp.isRecycled) {
                    bmp.recycle()
                }
            }
            clipThumbnails.clear()
        }
        clipFailedUris.clear()
    }

    private fun activateEnterPopup(enterKey: KeyModel, slot: Int) {
        val kb = enterKey.bounds
        val cellW = maxOf(kb.width() * 0.80f, 44f * density)
        val popW = cellW * 2f
        val popH = maxOf(kb.height() * 0.96f, 44f * density)
        val right = kb.right.coerceAtMost(width - 4f * density)
        val left = (right - popW).coerceAtLeast(4f * density)
        val bottom = kb.top - 8f * density
        val top = (bottom - popH).coerceAtLeast(2f * density)

        enterPopupRect.set(left, top, left + popW, top + popH)
        enterPopupCenterX = left + popW * 0.5f
        enterPopupSelectedOption = 0
        enterPopupSlot = slot
        isEnterPopupActive = true
    }

    private fun getAccentVariantsForKey(key: KeyModel): Array<String>? {
        if (key.code <= 0) return null
        val baseChar = key.code.toChar().lowercaseChar()
        val variants = layout.activeLanguage.accentPopupMap[baseChar]
        return if (variants != null && variants.isNotEmpty()) variants else null
    }

    private fun activateAccentPopup(key: KeyModel, slot: Int, variants: Array<String>, anchorX: Float) {
        val count = minOf(variants.size, MAX_ACCENT_VARIANTS)
        if (count <= 0) return
        val uppercase = layout.isShifted || layout.isCapsLock
        for (i in 0 until count) {
            val raw = variants[i]
            accentPopupChars[i] = if (uppercase) raw.uppercase() else raw
        }
        for (i in count until MAX_ACCENT_VARIANTS) {
            accentPopupChars[i] = null
        }

        val kb = key.bounds
        val rawCellW = maxOf(kb.width() * 0.98f, 36f * density)
        val maxPillW = (width - 8f * density).coerceAtLeast(36f * density)
        val cellW = minOf(rawCellW, maxPillW / count.toFloat())
        val popW = cellW * count.toFloat()
        val popH = maxOf(kb.height() * 1.04f, 44f * density)

        val maxLeft = (width - popW - 4f * density).coerceAtLeast(4f * density)
        val idealLeft = kb.centerX() - cellW * 0.5f
        val left = idealLeft.coerceIn(4f * density, maxLeft)
        val bottom = kb.top - 8f * density
        val top = (bottom - popH).coerceAtLeast(2f * density)

        accentPopupRect.set(left, top, left + popW, top + popH)
        accentPopupCellWidth = cellW
        accentPopupCount = count
        accentPopupSelectedIndex = 0
        accentPopupAnchorX = anchorX
        accentPopupDragged = false
        accentPopupSlot = slot
        isAccentPopupActive = true
    }

    private fun recordKeyReleaseAnim(key: KeyModel) {
        if (!keypressAnimationEnabled) return
        val t = key.type
        if (t != KeyModel.TYPE_NORMAL &&
            t != KeyModel.TYPE_SPACE &&
            t != KeyModel.TYPE_DELETE &&
            t != KeyModel.TYPE_ENTER
        ) {
            return
        }
        val now = SystemClock.uptimeMillis()
        for (i in 0 until ANIM_BUFFER_SIZE) {
            if (animKeys[i] === key) {
                animStartMs[i] = now
                postInvalidateOnAnimation()
                return
            }
        }
        animKeys[animWriteIndex] = key
        animStartMs[animWriteIndex] = now
        animWriteIndex = (animWriteIndex + 1) % ANIM_BUFFER_SIZE
        postInvalidateOnAnimation()
    }

    private fun clearKeyAnimations() {
        for (i in 0 until ANIM_BUFFER_SIZE) {
            animKeys[i] = null
            animStartMs[i] = 0L
        }
        animWriteIndex = 0
    }

    fun applyHeightOnly(heightDp: Int) {
        if (keyboardHeightDp != heightDp) {
            keyboardHeightDp = heightDp
            requestLayout()
            if (width > 0 && height > 0) {
                remeasureInternal(width, height)
            }
            invalidate()
        }
    }

    fun refreshTheme(
        context: Context,
        themeMode: Int,
        customCssEnabled: Boolean,
        customCss: String
    ) {
        theme.applyTheme(context, themeMode, customCssEnabled, customCss)
        syncAuxPaints()
        emojiPanel?.syncThemeColors()
        if (width > 0 && height > 0) {
            remeasureInternal(width, height)
        }
        invalidate()
    }

    fun setAutoShift(shouldShift: Boolean) {
        if (layout.currentMode != KeyboardLayout.Mode.ALPHA) return
        if (layout.isCapsLock) return
        if (shiftHoldSlot != -1) return
        if (layout.isShifted != shouldShift) {
            layout.updateShiftState(shifted = shouldShift, caps = false)
            invalidate()
        }
    }

    fun clearEmojiHistoryCache() {
        historyManager?.clearHistory()
        if (isEmojiMode) {
            invalidate()
        }
    }

    private fun hasStripSpace(): Boolean {
        if (isEmojiSearchMode) return true
        if (layout.currentMode == KeyboardLayout.Mode.NUMPAD) return false
        return (suggestionManager?.isStripVisible() == true) || showTopUtilityBar
    }

    fun shouldShowSuggestionStrip(): Boolean {
        if (isEmojiMode || isClipboardOpen) return false
        return hasStripSpace()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            resources.displayMetrics.widthPixels
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }

        // Keep identical height across ALPHA, SYMBOLS, SYMBOLS_SHIFT, Emoji & Clipboard (no vertical jumping)
        val extraNumRowDp = if (layout.showNumberRow && layout.currentMode != KeyboardLayout.Mode.NUMPAD) 36 else 0
        val stripDp = if (hasStripSpace()) 38 else 0
        val desiredHeight = ((keyboardHeightDp + extraNumRowDp + stripDp) * density).toInt()

        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            desiredHeight
        }

        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            remeasureInternal(w, h)
        }
    }

    private fun remeasureInternal(w: Int, h: Int) {
        currentStripHeight = if (shouldShowSuggestionStrip()) stripHeightPx else 0f
        val keyAreaH = (h - currentStripHeight).coerceAtLeast(1f)

        val wf = w.toFloat()
        val hf = h.toFloat()
        when (oneHandedMode) {
            KeyboardPrefs.ONE_HANDED_LEFT -> {
                val activeW = (wf * 0.82f).toInt().coerceAtLeast(1)
                layout.measure(w, currentStripHeight, keyAreaH, startX = 0f, activeWidth = activeW)
                oneHandedGutterRect.set(activeW.toFloat(), currentStripHeight, wf, hf)
            }
            KeyboardPrefs.ONE_HANDED_RIGHT -> {
                val activeW = (wf * 0.82f).toInt().coerceAtLeast(1)
                val startX = (w - activeW).toFloat()
                layout.measure(w, currentStripHeight, keyAreaH, startX = startX, activeWidth = activeW)
                oneHandedGutterRect.set(0f, currentStripHeight, startX, hf)
            }
            else -> {
                layout.measure(w, currentStripHeight, keyAreaH, startX = 0f, activeWidth = w)
                oneHandedGutterRect.setEmpty()
            }
        }

        // Precompute One-Handed gutter circular/squircle button bounds (0 allocs in onDraw)
        if (!oneHandedGutterRect.isEmpty) {
            val gw = oneHandedGutterRect.width()
            val gcx = oneHandedGutterRect.centerX()
            val gcy = oneHandedGutterRect.centerY()
            val btnSize = minOf(gw * 0.72f, 44f * density).coerceAtLeast(28f * density)
            val gap = 16f * density
            val halfBtn = btnSize * 0.5f
            val expandCy = gcy - halfBtn - gap * 0.5f
            val flipCy = gcy + halfBtn + gap * 0.5f
            oneHandedExpandBtnRect.set(gcx - halfBtn, expandCy - halfBtn, gcx + halfBtn, expandCy + halfBtn)
            oneHandedFlipBtnRect.set(gcx - halfBtn, flipCy - halfBtn, gcx + halfBtn, flipCy + halfBtn)
        } else {
            oneHandedExpandBtnRect.setEmpty()
            oneHandedFlipBtnRect.setEmpty()
        }

        // Precompute Inline Emoji Search strip geometry (0 allocs in onDraw)
        val sh = stripHeightPx
        val padH = 6f * density
        val padV = 4.5f * density
        val pillW = (wf * 0.37f).coerceIn(118f * density, 164f * density).coerceAtMost(wf * 0.55f)
        emojiSearchInputPillRect.set(padH, padV, padH + pillW, sh - padV)
        val closeW = 26f * density
        emojiSearchCloseBtnRect.set(
            emojiSearchInputPillRect.right - closeW,
            0f,
            emojiSearchInputPillRect.right + 2f * density,
            sh
        )
        emojiSearchResultsRect.set(emojiSearchInputPillRect.right + 4f * density, 0f, wf - 4f * density, sh - density)

        emojiPanel?.measure(w, h)
    }

    /**
     * 100% Zero-allocation onDraw implementation.
     */
    override fun onDraw(canvas: Canvas) {
        if (isEmojiMode) {
            emojiPanel?.draw(canvas)
            return
        }

        if (isClipboardOpen) {
            drawClipboardOverlay(canvas)
            return
        }

        // Ensure strip geometry stays in sync if privacy/toggle changed without size change
        val expectedStrip = if (shouldShowSuggestionStrip()) stripHeightPx else 0f
        if (expectedStrip != currentStripHeight && width > 0 && height > 0) {
            remeasureInternal(width, height)
        }

        // 1. Draw Keyboard Background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), theme.bgPaint)

        // 2. Draw Suggestion / Utility Strip (if enabled & visible)
        if (currentStripHeight > 0f) {
            drawSuggestionStrip(canvas)
        }

        // 3. Draw Keys with precision tactile lip, hairline border & zero-alloc 95ms micro-animation
        val keys = layout.getActiveKeys()
        val radius = theme.keyCornerRadius
        val hasShadow = theme.keyShadowHeight > 0f && (theme.colorKeyShadow ushr 24) > 0
        val hasBorder = theme.keyBorderWidth > 0f && (theme.colorKeyBorder ushr 24) > 0
        val animEnabled = keypressAnimationEnabled
        val nowMs = if (animEnabled) SystemClock.uptimeMillis() else 0L
        var needsNextFrame = false

        for (i in keys.indices) {
            val key = keys[i]
            val b = key.bounds

            val isShiftActive = key.type == KeyModel.TYPE_SHIFT &&
                layout.currentMode == KeyboardLayout.Mode.ALPHA &&
                (layout.isShifted || layout.isCapsLock)
            val isAccentKey = key.type == KeyModel.TYPE_ENTER || isShiftActive

            val bgPaint = when {
                key.isPressed -> theme.keyPressedPaint
                isAccentKey -> theme.accentPaint
                key.type != KeyModel.TYPE_NORMAL && key.type != KeyModel.TYPE_SPACE -> theme.keySpecialPaint
                else -> theme.keyBgPaint
            }

            // Zero-allocation 95ms ease-out release animation lookup
            var releaseProgress = -1f
            if (animEnabled && !key.isPressed) {
                for (a in 0 until ANIM_BUFFER_SIZE) {
                    if (animKeys[a] === key) {
                        val elapsed = nowMs - animStartMs[a]
                        if (elapsed in 0L until ANIM_DURATION_MS) {
                            val rawT = elapsed.toFloat() / ANIM_DURATION_MS.toFloat()
                            val inv = 1f - rawT
                            releaseProgress = 1f - inv * inv
                            needsNextFrame = true
                        } else {
                            animKeys[a] = null
                            animStartMs[a] = 0L
                        }
                        break
                    }
                }
            }

            val scale = when {
                animEnabled && key.isPressed -> 0.95f
                releaseProgress >= 0f -> 0.95f + 0.05f * releaseProgress
                else -> 1f
            }
            val applyScale = scale < 0.999f
            if (applyScale) {
                canvas.save()
                canvas.scale(scale, scale, b.centerX(), b.centerY())
            }

            // Bottom tactile shadow lip
            if (!key.isPressed && hasShadow) {
                theme.reusableRectF.set(
                    b.left,
                    b.top + theme.keyShadowHeight,
                    b.right,
                    b.bottom + theme.keyShadowHeight
                )
                canvas.drawRoundRect(theme.reusableRectF, radius, radius, theme.keyShadowPaint)
            }

            // Main keycap fill
            canvas.drawRoundRect(b, radius, radius, bgPaint)

            // Smooth fading pressed highlight during release animation (zero-allocation)
            if (releaseProgress >= 0f) {
                val baseAlpha = theme.colorBgKeyPressed ushr 24
                val fadeAlpha = ((1f - releaseProgress) * baseAlpha.toFloat()).toInt().coerceIn(0, 255)
                if (fadeAlpha > 0) {
                    val prevAlpha = theme.keyPressedPaint.alpha
                    theme.keyPressedPaint.alpha = fadeAlpha
                    canvas.drawRoundRect(b, radius, radius, theme.keyPressedPaint)
                    theme.keyPressedPaint.alpha = prevAlpha
                }
            }

            // Crisp hairline border
            if (hasBorder) {
                canvas.drawRoundRect(b, radius, radius, theme.keyBorderPaint)
            }

            // Draw key content (Vector icon or styled typography)
            drawKeyContent(canvas, key, isAccentKey)

            // Draw secondary/sub-label if enabled
            if (layout.showSubLabels && layout.currentMode == KeyboardLayout.Mode.ALPHA) {
                val sub = key.subLabel
                if (sub != null) {
                    val subPaint = theme.textSecondaryPaint
                    val subY = b.top + (b.height() * 0.28f)
                    val subX = b.right - (b.width() * 0.21f)
                    canvas.drawText(sub, subX, subY, subPaint)
                }
            }

            if (applyScale) {
                canvas.restore()
            }
        }

        // 3b. Draw One-Handed Mode Side Gutter controls (Expand & Flip) when active
        if (oneHandedMode != KeyboardPrefs.ONE_HANDED_OFF && !oneHandedGutterRect.isEmpty) {
            drawOneHandedGutter(canvas, hasShadow, hasBorder)
        }

        // 4. Draw Enter Popup, Accent Popup, OR Key Preview Popup (100% zero-allocation)
        if (isEnterPopupActive) {
            drawEnterPopup(canvas, hasShadow, hasBorder)
        } else if (isAccentPopupActive) {
            drawAccentPopup(canvas, hasShadow, hasBorder)
        } else if (keyPopupEnabled) {
            val activeKey = activePopupKey
            if (activeKey != null && activeKey.isPressed && activeKey.type == KeyModel.TYPE_NORMAL) {
                drawKeyPreviewPopup(canvas, activeKey, radius)
            }
        }

        if (needsNextFrame) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawKeyContent(canvas: Canvas, key: KeyModel, isAccentKey: Boolean) {
        val b = key.bounds
        when (key.type) {
            KeyModel.TYPE_SHIFT -> {
                if (layout.currentMode == KeyboardLayout.Mode.ALPHA) {
                    drawShiftIcon(canvas, b, key.isPressed)
                } else {
                    drawCenteredText(canvas, key.label, b, theme.textSpecialPaint)
                }
            }
            KeyModel.TYPE_DELETE -> {
                drawBackspaceIcon(canvas, b, theme.iconPaint)
            }
            KeyModel.TYPE_ENTER -> {
                val paint = if (key.isPressed) theme.iconPaint else theme.iconOnAccentPaint
                drawContextualEnterIcon(canvas, b, paint)
                val showEnterDot = (longPressEnterAction == KeyboardPrefs.ENTER_LONG_PRESS_EMOJI ||
                    longPressEnterAction == KeyboardPrefs.ENTER_LONG_PRESS_POPUP) &&
                    layout.currentMode != KeyboardLayout.Mode.NUMPAD
                if (showEnterDot) {
                    val dotPaint = if (key.isPressed) theme.iconFillPaint else theme.iconOnAccentFillPaint
                    val prevAlpha = dotPaint.alpha
                    dotPaint.alpha = 185
                    canvas.drawCircle(
                        b.right - 6.5f * density,
                        b.top + 6.5f * density,
                        2.0f * density,
                        dotPaint
                    )
                    dotPaint.alpha = prevAlpha
                }
            }
            KeyModel.TYPE_EMOJI -> {
                val r = minOf(b.width(), b.height()) * 0.25f
                drawEmojiSmileyIcon(canvas, b.centerX(), b.centerY(), r, theme.iconPaint, theme.iconFillPaint)
            }
            KeyModel.TYPE_SWITCH_LANG -> {
                if (key.label == "⚙") {
                    drawGearIcon(canvas, b.centerX(), b.centerY(), minOf(b.width(), b.height()) * 0.24f, theme.iconPaint)
                } else {
                    drawCenteredText(canvas, key.label, b, theme.textSpecialPaint)
                }
            }
            KeyModel.TYPE_SPACE -> {
                drawCenteredText(canvas, key.label, b, theme.textSpacePaint)
                if (spaceGlideActive) {
                    val cy = b.centerY()
                    val chev = 4f * density
                    val leftX = b.left + 14f * density
                    val rightX = b.right - 14f * density
                    val chevPaint = if (spaceGlideSelectionMode) accentStrokePaint else theme.iconSecondaryPaint
                    canvas.drawLine(leftX + chev, cy - chev, leftX, cy, chevPaint)
                    canvas.drawLine(leftX, cy, leftX + chev, cy + chev, chevPaint)
                    canvas.drawLine(rightX - chev, cy - chev, rightX, cy, chevPaint)
                    canvas.drawLine(rightX, cy, rightX - chev, cy + chev, chevPaint)
                }
            }
            KeyModel.TYPE_SWITCH_SYMBOLS,
            KeyModel.TYPE_SWITCH_ALPHA,
            KeyModel.TYPE_SWITCH_NUMPAD -> {
                drawCenteredText(canvas, key.label, b, theme.textSpecialPaint)
            }
            else -> {
                val textPaint = if (isAccentKey && !key.isPressed) theme.textOnAccentPaint else theme.textPrimaryPaint
                drawCenteredText(canvas, key.label, b, textPaint)
            }
        }
    }

    private fun drawCenteredText(canvas: Canvas, text: String, b: RectF, paint: Paint) {
        if (text.isEmpty()) return
        val baseline = b.centerY() - (paint.descent() + paint.ascent()) * 0.5f
        canvas.drawText(text, b.centerX(), baseline, paint)
    }

    // --- ZERO-ALLOCATION VECTOR CANVAS PATH ICONS ---

    private fun drawShiftIcon(canvas: Canvas, b: RectF, isPressed: Boolean) {
        val cx = b.centerX()
        val cy = b.centerY() - if (layout.isCapsLock || layout.isShifted) 1.5f * density else 0f
        val s = minOf(b.width(), b.height()) * 0.28f

        val path = theme.reusablePath
        path.reset()
        path.moveTo(cx, cy - s * 0.92f)            // top tip
        path.lineTo(cx + s * 0.88f, cy + s * 0.04f) // right wing
        path.lineTo(cx + s * 0.38f, cy + s * 0.04f) // inner right
        path.lineTo(cx + s * 0.38f, cy + s * 0.72f) // stem bottom right
        path.lineTo(cx - s * 0.38f, cy + s * 0.72f) // stem bottom left
        path.lineTo(cx - s * 0.38f, cy + s * 0.04f) // inner left
        path.lineTo(cx - s * 0.88f, cy + s * 0.04f) // left wing
        path.close()

        val active = layout.isShifted || layout.isCapsLock
        if (active) {
            val fillPaint = if (isPressed) theme.iconFillPaint else theme.iconOnAccentFillPaint
            canvas.drawPath(path, fillPaint)
            if (layout.isCapsLock) {
                // Crisp underline bar below arrow for Caps Lock
                val barY = cy + s * 1.05f
                val strokePaint = if (isPressed) theme.iconPaint else theme.iconOnAccentPaint
                canvas.drawLine(cx - s * 0.58f, barY, cx + s * 0.58f, barY, strokePaint)
            } else {
                // Subtle dot indicator below arrow for one-shot Shift
                canvas.drawCircle(cx, cy + s * 1.06f, 1.8f * density, fillPaint)
            }
        } else {
            canvas.drawPath(path, theme.iconPaint)
        }
    }

    private fun drawBackspaceIcon(canvas: Canvas, b: RectF, paint: Paint) {
        val cx = b.centerX()
        val cy = b.centerY()
        val s = minOf(b.width(), b.height()) * 0.28f

        val path = theme.reusablePath
        path.reset()
        path.moveTo(cx - s * 1.05f, cy)
        path.lineTo(cx - s * 0.42f, cy - s * 0.62f)
        path.lineTo(cx + s * 0.96f, cy - s * 0.62f)
        path.lineTo(cx + s * 0.96f, cy + s * 0.62f)
        path.lineTo(cx - s * 0.42f, cy + s * 0.62f)
        path.close()
        canvas.drawPath(path, paint)

        val xCx = cx + s * 0.24f
        val arm = s * 0.28f
        canvas.drawLine(xCx - arm, cy - arm, xCx + arm, cy + arm, paint)
        canvas.drawLine(xCx + arm, cy - arm, xCx - arm, cy + arm, paint)
    }

    private fun drawContextualEnterIcon(canvas: Canvas, b: RectF, paint: Paint) {
        val cx = b.centerX()
        val cy = b.centerY()
        val s = minOf(b.width(), b.height()) * 0.27f

        // Shift + Enter visual cue: always show Newline bent-arrow when Shift or CapsLock is active
        if (layout.isShifted || layout.isCapsLock) {
            drawNewlineIcon(canvas, cx, cy, s, paint)
            return
        }

        val action = currentImeAction and EditorInfo.IME_MASK_ACTION
        val path = theme.reusablePath
        path.reset()

        when (action) {
            EditorInfo.IME_ACTION_SEARCH -> {
                // Crisp magnifying glass
                val r = s * 0.58f
                val gx = cx - s * 0.16f
                val gy = cy - s * 0.16f
                canvas.drawCircle(gx, gy, r, paint)
                canvas.drawLine(
                    gx + r * 0.72f,
                    gy + r * 0.72f,
                    cx + s * 0.85f,
                    cy + s * 0.85f,
                    paint
                )
            }
            EditorInfo.IME_ACTION_SEND -> {
                // Crisp paper-plane Send icon
                path.moveTo(cx - s * 0.82f, cy - s * 0.72f)
                path.lineTo(cx + s * 0.92f, cy)
                path.lineTo(cx - s * 0.82f, cy + s * 0.72f)
                path.lineTo(cx - s * 0.46f, cy)
                path.close()
                canvas.drawPath(path, paint)
                canvas.drawLine(cx - s * 0.46f, cy, cx + s * 0.18f, cy, paint)
            }
            EditorInfo.IME_ACTION_DONE -> {
                // Crisp Checkmark
                path.moveTo(cx - s * 0.78f, cy + s * 0.04f)
                path.lineTo(cx - s * 0.20f, cy + s * 0.62f)
                path.lineTo(cx + s * 0.84f, cy - s * 0.58f)
                canvas.drawPath(path, paint)
            }
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_GO -> {
                // Crisp forward arrow ->
                canvas.drawLine(cx - s * 0.80f, cy, cx + s * 0.78f, cy, paint)
                path.moveTo(cx + s * 0.24f, cy - s * 0.54f)
                path.lineTo(cx + s * 0.82f, cy)
                path.lineTo(cx + s * 0.24f, cy + s * 0.54f)
                canvas.drawPath(path, paint)
            }
            else -> {
                drawNewlineIcon(canvas, cx, cy, s, paint)
            }
        }
    }

    private fun drawNewlineIcon(canvas: Canvas, cx: Float, cy: Float, s: Float, paint: Paint) {
        val path = theme.reusablePath
        path.reset()
        path.moveTo(cx + s * 0.76f, cy - s * 0.52f)
        path.lineTo(cx + s * 0.76f, cy + s * 0.18f)
        path.lineTo(cx - s * 0.74f, cy + s * 0.18f)
        canvas.drawPath(path, paint)

        path.reset()
        path.moveTo(cx - s * 0.28f, cy - s * 0.26f)
        path.lineTo(cx - s * 0.78f, cy + s * 0.18f)
        path.lineTo(cx - s * 0.28f, cy + s * 0.62f)
        canvas.drawPath(path, paint)
    }

    private fun drawEmojiSmileyIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        strokePaint: Paint,
        fillPaint: Paint
    ) {
        canvas.drawCircle(cx, cy, r, strokePaint)
        val eyeOffsetX = r * 0.36f
        val eyeOffsetY = r * 0.24f
        val eyeR = (r * 0.13f).coerceAtLeast(1.2f * density)
        canvas.drawCircle(cx - eyeOffsetX, cy - eyeOffsetY, eyeR, fillPaint)
        canvas.drawCircle(cx + eyeOffsetX, cy - eyeOffsetY, eyeR, fillPaint)

        val arcR = r * 0.54f
        theme.reusableRectF.set(cx - arcR, cy - arcR * 0.72f, cx + arcR, cy + arcR * 1.02f)
        canvas.drawArc(theme.reusableRectF, 22f, 136f, false, strokePaint)
    }

    private fun drawEnterPopup(canvas: Canvas, hasShadow: Boolean, hasBorder: Boolean) {
        val pillRadius = 12f * density
        if (hasShadow) {
            theme.reusableRectF.set(
                enterPopupRect.left,
                enterPopupRect.top + 2f * density,
                enterPopupRect.right,
                enterPopupRect.bottom + 2f * density
            )
            canvas.drawRoundRect(theme.reusableRectF, pillRadius, pillRadius, theme.keyShadowPaint)
        }

        // Outer rounded pill container
        canvas.drawRoundRect(enterPopupRect, pillRadius, pillRadius, theme.popupBgPaint)
        if (hasBorder) {
            canvas.drawRoundRect(enterPopupRect, pillRadius, pillRadius, theme.keyBorderPaint)
        }

        // Split into 2 equal cells: 0 = Emoji (left), 1 = Newline (right)
        val inset = 3.5f * density
        val innerRadius = (pillRadius - inset * 0.7f).coerceAtLeast(4f * density)
        val midX = enterPopupCenterX

        if (enterPopupSelectedOption == 0) {
            theme.reusableRectF.set(
                enterPopupRect.left + inset,
                enterPopupRect.top + inset,
                midX - inset * 0.5f,
                enterPopupRect.bottom - inset
            )
        } else {
            theme.reusableRectF.set(
                midX + inset * 0.5f,
                enterPopupRect.top + inset,
                enterPopupRect.right - inset,
                enterPopupRect.bottom - inset
            )
        }
        canvas.drawRoundRect(theme.reusableRectF, innerRadius, innerRadius, theme.accentPaint)

        val cellW = enterPopupRect.width() * 0.5f
        val cellH = enterPopupRect.height()
        val cy = enterPopupRect.centerY()

        // Left cell: Emoji smiley icon
        val leftCx = enterPopupRect.left + cellW * 0.5f
        val emojiR = minOf(cellW, cellH) * 0.23f
        val leftStroke = if (enterPopupSelectedOption == 0) theme.iconOnAccentPaint else theme.iconPaint
        val leftFill = if (enterPopupSelectedOption == 0) theme.iconOnAccentFillPaint else theme.iconFillPaint
        drawEmojiSmileyIcon(canvas, leftCx, cy, emojiR, leftStroke, leftFill)

        // Right cell: Newline bent-arrow icon
        val rightCx = midX + cellW * 0.5f
        val newlineS = minOf(cellW, cellH) * 0.24f
        val rightStroke = if (enterPopupSelectedOption == 1) theme.iconOnAccentPaint else theme.iconPaint
        drawNewlineIcon(canvas, rightCx, cy, newlineS, rightStroke)
    }

    private fun drawAccentPopup(canvas: Canvas, hasShadow: Boolean, hasBorder: Boolean) {
        val count = accentPopupCount
        if (count <= 0) return

        val pillRadius = 12f * density
        if (hasShadow) {
            theme.reusableRectF.set(
                accentPopupRect.left,
                accentPopupRect.top + 2f * density,
                accentPopupRect.right,
                accentPopupRect.bottom + 2f * density
            )
            canvas.drawRoundRect(theme.reusableRectF, pillRadius, pillRadius, theme.keyShadowPaint)
        }

        // Outer rounded pill container
        canvas.drawRoundRect(accentPopupRect, pillRadius, pillRadius, theme.popupBgPaint)
        if (hasBorder) {
            canvas.drawRoundRect(accentPopupRect, pillRadius, pillRadius, theme.keyBorderPaint)
        }

        // Active cell highlight pill
        val inset = 3.5f * density
        val innerRadius = (pillRadius - inset * 0.7f).coerceAtLeast(4f * density)
        val cellW = accentPopupCellWidth
        val selIdx = accentPopupSelectedIndex.coerceIn(0, count - 1)
        val selLeft = accentPopupRect.left + selIdx * cellW + inset * 0.6f
        val selRight = accentPopupRect.left + (selIdx + 1) * cellW - inset * 0.6f
        theme.reusableRectF.set(
            selLeft,
            accentPopupRect.top + inset,
            selRight,
            accentPopupRect.bottom - inset
        )
        canvas.drawRoundRect(theme.reusableRectF, innerRadius, innerRadius, theme.accentPaint)

        // Draw each accented character cleanly (zero allocations)
        val cy = accentPopupRect.centerY()
        for (i in 0 until count) {
            val ch = accentPopupChars[i] ?: continue
            val cellCx = accentPopupRect.left + cellW * (i + 0.5f)
            val paint = if (i == selIdx) theme.textOnAccentPaint else theme.textPrimaryPaint
            val baseline = cy - (paint.descent() + paint.ascent()) * 0.5f
            canvas.drawText(ch, cellCx, baseline, paint)
        }
    }

    private fun drawImagePlaceholderIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, paint: Paint) {
        theme.reusableRectF.set(cx - r * 0.9f, cy - r * 0.72f, cx + r * 0.9f, cy + r * 0.72f)
        canvas.drawRoundRect(theme.reusableRectF, 2f * density, 2f * density, paint)
        canvas.drawCircle(cx - r * 0.35f, cy - r * 0.25f, r * 0.18f, paint)
        val path = theme.reusablePath
        path.reset()
        path.moveTo(cx - r * 0.75f, cy + r * 0.55f)
        path.lineTo(cx - r * 0.15f, cy + r * 0.02f)
        path.lineTo(cx + r * 0.20f, cy + r * 0.32f)
        path.lineTo(cx + r * 0.48f, cy + r * 0.08f)
        path.lineTo(cx + r * 0.78f, cy + r * 0.55f)
        canvas.drawPath(path, paint)
    }

    private fun drawGearIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, paint: Paint) {
        val innerR = r * 0.42f
        val ringR = r * 0.78f
        val toothOuter = r * 1.06f
        canvas.drawCircle(cx, cy, innerR, paint)
        canvas.drawCircle(cx, cy, ringR, paint)
        for (i in 0 until 8) {
            val c = gearCos[i]
            val s = gearSin[i]
            canvas.drawLine(
                cx + c * ringR,
                cy + s * ringR,
                cx + c * toothOuter,
                cy + s * toothOuter,
                paint
            )
        }
    }

    private fun drawClipboardIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, paint: Paint) {
        // Outer clipboard body
        theme.reusableRectF.set(cx - r * 0.72f, cy - r * 0.72f, cx + r * 0.72f, cy + r * 0.92f)
        canvas.drawRoundRect(theme.reusableRectF, 2f * density, 2f * density, paint)
        // Top clip clasp
        theme.reusableRectF.set(cx - r * 0.36f, cy - r * 0.98f, cx + r * 0.36f, cy - r * 0.48f)
        canvas.drawRoundRect(theme.reusableRectF, 1.5f * density, 1.5f * density, paint)
        // Two subtle lines inside
        canvas.drawLine(cx - r * 0.34f, cy + r * 0.04f, cx + r * 0.34f, cy + r * 0.04f, paint)
        canvas.drawLine(cx - r * 0.34f, cy + r * 0.42f, cx + r * 0.18f, cy + r * 0.42f, paint)
    }

    private fun drawOneHandedIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, active: Boolean) {
        val strokePaint = if (active) accentStrokePaint else theme.iconSecondaryPaint
        val hw = r * 0.72f
        val hh = r * 0.96f
        // Sleek phone frame outline
        theme.reusableRectF.set(cx - hw, cy - hh, cx + hw, cy + hh)
        canvas.drawRoundRect(theme.reusableRectF, 2.4f * density, 2.4f * density, strokePaint)

        // Docked miniature keyboard block inside bottom corner (left when ONE_HANDED_LEFT, right otherwise)
        val kbW = hw * 1.18f
        val kbH = hh * 0.68f
        val kbBottom = cy + hh - 2.0f * density
        val kbTop = kbBottom - kbH
        val kbLeft = if (oneHandedMode == KeyboardPrefs.ONE_HANDED_LEFT) {
            cx - hw + 2.0f * density
        } else {
            cx + hw - 2.0f * density - kbW
        }
        theme.reusableRectF.set(kbLeft, kbTop, kbLeft + kbW, kbBottom)
        if (active) {
            canvas.drawRoundRect(theme.reusableRectF, 1.4f * density, 1.4f * density, accentTintPaint)
        }
        canvas.drawRoundRect(theme.reusableRectF, 1.4f * density, 1.4f * density, strokePaint)

        // Tiny directional arrow above docked keyboard
        val arrowY = cy - hh * 0.34f
        val arrowW = hw * 0.42f
        val dir = if (oneHandedMode == KeyboardPrefs.ONE_HANDED_LEFT) -1f else 1f
        canvas.drawLine(cx - arrowW * dir, arrowY, cx + arrowW * dir, arrowY, strokePaint)
        canvas.drawLine(cx + arrowW * dir, arrowY, cx + (arrowW * 0.25f) * dir, arrowY - arrowW * 0.55f, strokePaint)
        canvas.drawLine(cx + arrowW * dir, arrowY, cx + (arrowW * 0.25f) * dir, arrowY + arrowW * 0.55f, strokePaint)
    }

    private fun drawSearchGlassIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, paint: Paint) {
        val gx = cx - r * 0.22f
        val gy = cy - r * 0.22f
        val glassR = r * 0.74f
        canvas.drawCircle(gx, gy, glassR, paint)
        canvas.drawLine(
            gx + glassR * 0.72f,
            gy + glassR * 0.72f,
            cx + r * 0.92f,
            cy + r * 0.92f,
            paint
        )
    }

    private fun drawCloseXIcon(canvas: Canvas, cx: Float, cy: Float, arm: Float, paint: Paint) {
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, paint)
        canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, paint)
    }

    private fun drawPushpinIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, pinned: Boolean) {
        val strokePaint = if (pinned) accentStrokePaint else theme.iconSecondaryPaint
        val path = theme.reusablePath
        // Diagonal 45-degree modern vector pushpin (head top-right, needle bottom-left)
        path.reset()
        path.moveTo(cx + r * 0.08f, cy - r * 0.88f)
        path.lineTo(cx + r * 0.88f, cy - r * 0.08f)
        path.lineTo(cx + r * 0.50f, cy + r * 0.16f)
        path.lineTo(cx + r * 0.24f, cy + r * 0.54f)
        path.lineTo(cx - r * 0.54f, cy - r * 0.24f)
        path.lineTo(cx - r * 0.16f, cy - r * 0.50f)
        path.close()

        if (pinned) {
            canvas.drawPath(path, theme.accentPaint)
        }
        canvas.drawPath(path, strokePaint)
        // Needle pointing bottom-left
        canvas.drawLine(cx - r * 0.18f, cy + r * 0.18f, cx - r * 0.82f, cy + r * 0.82f, strokePaint)
    }

    private fun drawExpandArrowsIcon(canvas: Canvas, cx: Float, cy: Float, s: Float, paint: Paint) {
        // Opposite diagonal expand arrows (top-right & bottom-left)
        canvas.drawLine(cx - s * 0.78f, cy + s * 0.78f, cx + s * 0.78f, cy - s * 0.78f, paint)
        val head = s * 0.52f
        // Top-right arrowhead
        canvas.drawLine(cx + s * 0.78f, cy - s * 0.78f, cx + s * 0.78f - head, cy - s * 0.78f, paint)
        canvas.drawLine(cx + s * 0.78f, cy - s * 0.78f, cx + s * 0.78f, cy - s * 0.78f + head, paint)
        // Bottom-left arrowhead
        canvas.drawLine(cx - s * 0.78f, cy + s * 0.78f, cx - s * 0.78f + head, cy + s * 0.78f, paint)
        canvas.drawLine(cx - s * 0.78f, cy + s * 0.78f, cx - s * 0.78f, cy + s * 0.78f - head, paint)
    }

    private fun drawFlipArrowIcon(canvas: Canvas, cx: Float, cy: Float, s: Float, pointRight: Boolean, paint: Paint) {
        val dir = if (pointRight) 1f else -1f
        // Horizontal arrow pointing toward gutter side to pull keyboard across
        canvas.drawLine(cx - s * 0.76f * dir, cy, cx + s * 0.76f * dir, cy, paint)
        canvas.drawLine(cx + s * 0.76f * dir, cy, cx + s * 0.20f * dir, cy - s * 0.56f, paint)
        canvas.drawLine(cx + s * 0.76f * dir, cy, cx + s * 0.20f * dir, cy + s * 0.56f, paint)
        // Subtle vertical docking bar at destination edge
        canvas.drawLine(cx + s * 0.96f * dir, cy - s * 0.58f, cx + s * 0.96f * dir, cy + s * 0.58f, paint)
    }

    private fun drawOneHandedGutter(canvas: Canvas, hasShadow: Boolean, hasBorder: Boolean) {
        // Subtle vertical hairline separating active keyboard area from gutter
        val divX = if (oneHandedMode == KeyboardPrefs.ONE_HANDED_LEFT) {
            oneHandedGutterRect.left
        } else {
            oneHandedGutterRect.right - density
        }
        canvas.drawRect(
            divX,
            oneHandedGutterRect.top + 8f * density,
            divX + density,
            oneHandedGutterRect.bottom - 8f * density,
            theme.stripDividerPaint
        )

        val btnR = oneHandedExpandBtnRect.height() * 0.5f

        // 1. Expand-to-full-width button (top)
        if (pressedGutterBtn != 1 && hasShadow) {
            theme.reusableRectF.set(
                oneHandedExpandBtnRect.left,
                oneHandedExpandBtnRect.top + theme.keyShadowHeight,
                oneHandedExpandBtnRect.right,
                oneHandedExpandBtnRect.bottom + theme.keyShadowHeight
            )
            canvas.drawRoundRect(theme.reusableRectF, btnR, btnR, theme.keyShadowPaint)
        }
        val expandBg = if (pressedGutterBtn == 1) theme.keyPressedPaint else theme.keySpecialPaint
        canvas.drawRoundRect(oneHandedExpandBtnRect, btnR, btnR, expandBg)
        if (hasBorder) {
            canvas.drawRoundRect(oneHandedExpandBtnRect, btnR, btnR, theme.keyBorderPaint)
        }
        drawExpandArrowsIcon(
            canvas,
            oneHandedExpandBtnRect.centerX(),
            oneHandedExpandBtnRect.centerY(),
            oneHandedExpandBtnRect.width() * 0.24f,
            theme.iconPaint
        )

        // 2. Flip-side button (bottom)
        if (pressedGutterBtn != 2 && hasShadow) {
            theme.reusableRectF.set(
                oneHandedFlipBtnRect.left,
                oneHandedFlipBtnRect.top + theme.keyShadowHeight,
                oneHandedFlipBtnRect.right,
                oneHandedFlipBtnRect.bottom + theme.keyShadowHeight
            )
            canvas.drawRoundRect(theme.reusableRectF, btnR, btnR, theme.keyShadowPaint)
        }
        val flipBg = if (pressedGutterBtn == 2) theme.keyPressedPaint else theme.keySpecialPaint
        canvas.drawRoundRect(oneHandedFlipBtnRect, btnR, btnR, flipBg)
        if (hasBorder) {
            canvas.drawRoundRect(oneHandedFlipBtnRect, btnR, btnR, theme.keyBorderPaint)
        }
        // If keyboard is LEFT, gutter is RIGHT -> arrow points RIGHT to flip keyboard to RIGHT
        val pointRight = (oneHandedMode == KeyboardPrefs.ONE_HANDED_LEFT)
        drawFlipArrowIcon(
            canvas,
            oneHandedFlipBtnRect.centerX(),
            oneHandedFlipBtnRect.centerY(),
            oneHandedFlipBtnRect.width() * 0.25f,
            pointRight,
            theme.iconPaint
        )
    }

    private fun drawKeyPreviewPopup(canvas: Canvas, activeKey: KeyModel, radius: Float) {
        val kb = activeKey.bounds
        val desiredPopH = kb.height() * 1.18f
        val rawTop = kb.top - desiredPopH - 6f * density
        val clampedTopRow = rawTop < 2f
        val popW = if (clampedTopRow) kb.width() * 1.42f else kb.width() * 1.28f
        val popH = if (clampedTopRow) kb.height() * 1.26f else desiredPopH
        val left = (kb.centerX() - popW * 0.5f).coerceIn(2f, width - popW - 2f)
        val top = rawTop.coerceAtLeast(2f)

        theme.reusableRectF.set(left, top, left + popW, top + popH)
        canvas.drawRoundRect(theme.reusableRectF, radius * 1.35f, radius * 1.35f, theme.popupBgPaint)
        if (theme.keyBorderWidth > 0f && (theme.colorKeyBorder ushr 24) > 0) {
            canvas.drawRoundRect(theme.reusableRectF, radius * 1.35f, radius * 1.35f, theme.keyBorderPaint)
        }

        val sub = activeKey.subLabel
        val showSub = sub != null && layout.showSubLabels && layout.currentMode == KeyboardLayout.Mode.ALPHA

        // When long-press triggered on a key with subLabel, display the subLabel as primary popup text
        val mainText = if (popupLongPressTriggered && showSub) sub else activeKey.label
        val textCenterY = if (clampedTopRow) {
            theme.reusableRectF.top + popH * 0.42f
        } else {
            theme.reusableRectF.centerY()
        }
        val pBaseline = textCenterY - (theme.popupTextPaint.descent() + theme.popupTextPaint.ascent()) * 0.5f
        canvas.drawText(mainText, theme.reusableRectF.centerX(), pBaseline, theme.popupTextPaint)

        // Subtle sub-label hint in top-right of popup when long-press is available
        if (showSub && !popupLongPressTriggered) {
            val subX = theme.reusableRectF.right - popW * 0.22f
            val subY = theme.reusableRectF.top + popH * 0.27f
            canvas.drawText(sub, subX, subY, theme.popupSubTextPaint)
        }
    }

    private fun drawEmojiSearchStrip(canvas: Canvas) {
        val w = width.toFloat()
        val h = currentStripHeight
        canvas.drawRect(0f, 0f, w, h, theme.stripBgPaint)
        canvas.drawRect(0f, h - density, w, h, theme.stripDividerPaint)

        // 1. Left Search Input Pill: [vector magnifying glass] query [vector X]
        val pillR = emojiSearchInputPillRect.height() * 0.5f
        canvas.drawRoundRect(emojiSearchInputPillRect, pillR, pillR, theme.keyBgPaint)
        canvas.drawRoundRect(emojiSearchInputPillRect, pillR, pillR, accentBorderPaint)

        val pillCy = emojiSearchInputPillRect.centerY()
        val glassCx = emojiSearchInputPillRect.left + 13f * density
        drawSearchGlassIcon(canvas, glassCx, pillCy, 5.4f * density, theme.iconSecondaryPaint)

        // Close [X] vector button on right edge of search pill
        val closeCx = emojiSearchInputPillRect.right - 13f * density
        if (emojiSearchClosePressed) {
            canvas.drawCircle(closeCx, pillCy, 10f * density, theme.keyPressedPaint)
        }
        drawCloseXIcon(canvas, closeCx, pillCy, 4.2f * density, theme.iconSecondaryPaint)

        // Query text or placeholder (100% zero-allocation using StringBuilder overload!)
        val textLeft = glassCx + 9.5f * density
        val textRight = closeCx - 9.5f * density
        val availTextW = (textRight - textLeft).coerceAtLeast(8f * density)

        canvas.save()
        canvas.clipRect(textLeft, emojiSearchInputPillRect.top, textRight, emojiSearchInputPillRect.bottom)
        if (emojiSearchQuery.isEmpty()) {
            val phPaint = theme.textSecondaryPaint
            val prevAlign = phPaint.textAlign
            val prevSize = phPaint.textSize
            phPaint.textAlign = Paint.Align.LEFT
            phPaint.textSize = 12.5f * density
            val baseline = pillCy - (phPaint.descent() + phPaint.ascent()) * 0.5f
            canvas.drawText(SEARCH_PLACEHOLDER, textLeft, baseline, phPaint)
            phPaint.textAlign = prevAlign
            phPaint.textSize = prevSize
        } else {
            val qLen = emojiSearchQuery.length
            val qW = searchQueryPaint.measureText(emojiSearchQuery, 0, qLen)
            val drawX = if (qW > availTextW - 3f * density) {
                textRight - 3f * density - qW
            } else {
                textLeft
            }
            val baseline = pillCy - (searchQueryPaint.descent() + searchQueryPaint.ascent()) * 0.5f
            canvas.drawText(emojiSearchQuery, 0, qLen, drawX, baseline, searchQueryPaint)
            // Crisp accent caret at end of query
            val caretX = (drawX + qW + 1.5f * density).coerceAtMost(textRight - 1.5f * density)
            val caretHalfH = 7f * density
            canvas.drawRect(caretX, pillCy - caretHalfH, caretX + 1.5f * density, pillCy + caretHalfH, theme.accentPaint)
        }
        canvas.restore()

        // 2. Right Scrollable Emoji Results Strip
        val resRect = emojiSearchResultsRect
        canvas.save()
        canvas.clipRect(resRect)
        val count = emojiSearchResults.size
        if (count == 0) {
            val baseline = resRect.centerY() - (theme.textSecondaryPaint.descent() + theme.textSecondaryPaint.ascent()) * 0.5f
            canvas.drawText(SEARCH_NO_MATCH, resRect.centerX(), baseline, theme.textSecondaryPaint)
            emojiSearchResultsMaxScrollX = 0f
        } else {
            val cellW = 38f * density
            emojiSearchResultsMaxScrollX = (count * cellW - resRect.width()).coerceAtLeast(0f)
            emojiSearchResultsScrollX = emojiSearchResultsScrollX.coerceIn(0f, emojiSearchResultsMaxScrollX)

            val baseline = resRect.centerY() - (searchEmojiPaint.descent() + searchEmojiPaint.ascent()) * 0.5f
            val pad = 3f * density
            val cellR = 7f * density
            for (i in 0 until count) {
                val cellLeft = resRect.left + i * cellW - emojiSearchResultsScrollX
                val cellRight = cellLeft + cellW
                if (cellRight < resRect.left || cellLeft > resRect.right) continue

                if (i == emojiSearchPressedResultIdx) {
                    theme.reusableRectF.set(cellLeft + pad, pad, cellRight - pad, resRect.bottom - pad)
                    canvas.drawRoundRect(theme.reusableRectF, cellR, cellR, theme.keyPressedPaint)
                }
                canvas.drawText(emojiSearchResults[i], (cellLeft + cellRight) * 0.5f, baseline, searchEmojiPaint)
            }
        }
        canvas.restore()
    }

    private fun drawSuggestionStrip(canvas: Canvas) {
        if (isEmojiSearchMode) {
            drawEmojiSearchStrip(canvas)
            return
        }

        val w = width.toFloat()
        val h = currentStripHeight
        canvas.drawRect(0f, 0f, w, h, theme.stripBgPaint)
        canvas.drawRect(0f, h - density, w, h, theme.stripDividerPaint)

        val sm = suggestionManager
        val count = sm?.activeSlotCount ?: 0
        if (count <= 0) {
            if (!showTopUtilityBar) {
                // Ultra-clean minimal blank strip when idle (prevents height jumping while typing)
                return
            }
            // Idle utility bar (100% configurable):
            // [Clipboard] [optional Emoji] | Center: Language | [optional EN/VI pill] [One-Handed] [optional Settings Gear]
            val btnW = stripSideBtnWidthPx
            val cy = h * 0.5f
            val hasBorder = theme.keyBorderWidth > 0f && (theme.colorKeyBorder ushr 24) > 0
            val showTopEmoji = (emojiButtonMode == KeyboardPrefs.EMOJI_BTN_TOP_BAR)
            val leftReserved = if (showTopEmoji) btnW * 2f else btnW
            val settingsW = if (showSettingsKey) btnW else 0f
            val oneHandedW = btnW
            val langPillW = if (layout.hasMultipleLanguages) stripLangBtnWidthPx else 0f
            val rightReserved = settingsW + oneHandedW + langPillW

            // 1. Far-left slot: Clipboard icon
            if (pressedStripAction == STRIP_ACTION_CLIPBOARD) {
                canvas.drawRect(0f, 0f, btnW, h - density, theme.keyPressedPaint)
            }
            drawClipboardIcon(canvas, btnW * 0.5f, cy, 8.2f * density, theme.iconSecondaryPaint)
            if (clipboardManager?.getClipItems()?.isNotEmpty() == true) {
                canvas.drawCircle(btnW * 0.5f + 7.2f * density, cy - 7.2f * density, 2.2f * density, theme.accentPaint)
            }

            // 2. Optional second-left slot: Monochrome Emoji vector smiley
            if (showTopEmoji) {
                if (pressedStripAction == STRIP_ACTION_EMOJI) {
                    canvas.drawRect(btnW, 0f, btnW * 2f, h - density, theme.keyPressedPaint)
                }
                val prevFillColor = theme.iconFillPaint.color
                theme.iconFillPaint.color = theme.colorTextSecondary
                drawEmojiSmileyIcon(canvas, btnW * 1.5f, cy, 8.2f * density, theme.iconSecondaryPaint, theme.iconFillPaint)
                theme.iconFillPaint.color = prevFillColor
            }

            // 3. Optional far-right slot: Settings gear
            if (showSettingsKey) {
                if (pressedStripAction == STRIP_ACTION_SETTINGS) {
                    canvas.drawRect(w - btnW, 0f, w, h - density, theme.keyPressedPaint)
                }
                drawGearIcon(canvas, w - btnW * 0.5f, cy, 8.2f * density, theme.iconSecondaryPaint)
            }

            // 3b. One-Handed Mode 1-tap toggle vector button (left of Settings gear)
            val ohRight = w - settingsW
            val ohLeft = ohRight - oneHandedW
            val ohActive = (oneHandedMode != KeyboardPrefs.ONE_HANDED_OFF)
            if (pressedStripAction == STRIP_ACTION_ONE_HANDED) {
                canvas.drawRect(ohLeft, 0f, ohRight, h - density, theme.keyPressedPaint)
            } else if (ohActive) {
                val pillPadH = 5f * density
                val pillPadV = 5.5f * density
                theme.reusableRectF.set(ohLeft + pillPadH, pillPadV, ohRight - pillPadH, h - pillPadV)
                val pillR = theme.reusableRectF.height() * 0.5f
                canvas.drawRoundRect(theme.reusableRectF, pillR, pillR, accentTintPaint)
            }
            drawOneHandedIcon(canvas, (ohLeft + ohRight) * 0.5f, cy, 8.2f * density, ohActive)

            // 4. Optional language pill (only when multiple languages enabled)
            if (layout.hasMultipleLanguages) {
                val pillSlotLeft = w - rightReserved
                val pillSlotRight = ohLeft
                val pillCx = (pillSlotLeft + pillSlotRight) * 0.5f
                val pillW = 34f * density
                val pillH = 22f * density
                val pillR = pillH * 0.5f
                theme.reusableRectF.set(pillCx - pillW * 0.5f, cy - pillH * 0.5f, pillCx + pillW * 0.5f, cy + pillH * 0.5f)
                val pillBgPaint = if (pressedStripAction == STRIP_ACTION_LANG_PILL) theme.keyPressedPaint else theme.keySpecialPaint
                canvas.drawRoundRect(theme.reusableRectF, pillR, pillR, pillBgPaint)
                if (hasBorder) {
                    canvas.drawRoundRect(theme.reusableRectF, pillR, pillR, theme.keyBorderPaint)
                }
                val codePaint = theme.textSecondaryPaint
                val prevCodeBold = codePaint.isFakeBoldText
                codePaint.isFakeBoldText = true
                val codeBase = cy - (codePaint.descent() + codePaint.ascent()) * 0.5f
                canvas.drawText(layout.activeLanguage.shortCode, pillCx, codeBase, codePaint)
                codePaint.isFakeBoldText = prevCodeBold
            }

            // 5. Center: Active language name (tappable to cycle when multiple languages enabled)
            val centerLeft = leftReserved
            val centerRight = w - rightReserved
            if (centerRight > centerLeft + 24f * density) {
                if (pressedStripAction == STRIP_ACTION_LANG_CENTER && layout.hasMultipleLanguages) {
                    canvas.drawRect(centerLeft, 0f, centerRight, h - density, theme.keyPressedPaint)
                }
                val centerCx = (centerLeft + centerRight) * 0.5f
                val availW = (centerRight - centerLeft - 10f * density).coerceAtLeast(16f * density)
                val langName = layout.activeLanguage.nativeName
                val paint = theme.textSecondaryPaint
                val baseline = cy - (paint.descent() + paint.ascent()) * 0.5f
                val measured = paint.measureText(langName)
                if (measured <= availW) {
                    canvas.drawText(langName, centerCx, baseline, paint)
                } else {
                    val ellipsisW = paint.measureText(ELLIPSIS)
                    val fitCount = paint.breakText(langName, 0, langName.length, true, (availW - ellipsisW).coerceAtLeast(4f), null)
                    val fitW = paint.measureText(langName, 0, fitCount)
                    val startX = centerCx - (fitW + ellipsisW) * 0.5f
                    val prevAlign = paint.textAlign
                    paint.textAlign = Paint.Align.LEFT
                    canvas.drawText(langName, 0, fitCount, startX, baseline, paint)
                    canvas.drawText(ELLIPSIS, startX + fitW, baseline, paint)
                    paint.textAlign = prevAlign
                }
            }
            return
        }
        if (sm == null) return

        val slotW = w / 3f
        val padV = 7f * density
        val maxTextW = (slotW - 14f * density).coerceAtLeast(16f * density)
        val ellipsisW = theme.suggestionTextPaint.measureText(ELLIPSIS)

        for (i in 0 until count) {
            val left = i * slotW
            val right = left + slotW

            if (i == pressedSuggestionIndex) {
                canvas.drawRect(left, 0f, right, h - density, theme.keyPressedPaint)
            }

            if (i > 0) {
                canvas.drawRect(left, padV, left + density, h - padV, theme.stripDividerPaint)
            }

            val word = sm.slots[i]
            if (word.isEmpty()) continue
            val paint = if (i == sm.autoCorrectSlotIndex) theme.suggestionBoldPaint else theme.suggestionTextPaint
            val baseline = h * 0.5f - (paint.descent() + paint.ascent()) * 0.5f
            val measured = paint.measureText(word)

            if (measured <= maxTextW) {
                canvas.drawText(word, left + slotW * 0.5f, baseline, paint)
            } else {
                // Zero-allocation width clamping with crisp ellipsis
                val fitCount = paint.breakText(word, 0, word.length, true, (maxTextW - ellipsisW).coerceAtLeast(4f), null)
                val fitW = paint.measureText(word, 0, fitCount)
                val totalW = fitW + ellipsisW
                val startX = left + (slotW - totalW) * 0.5f
                val prevAlign = paint.textAlign
                paint.textAlign = Paint.Align.LEFT
                canvas.drawText(word, 0, fitCount, startX, baseline, paint)
                canvas.drawText(ELLIPSIS, startX + fitW, baseline, paint)
                paint.textAlign = prevAlign
            }
        }
    }

    // --- INLINE CANVAS CLIPBOARD PICKER OVERLAY (ZERO EXTRA VIEWS, LAZY MICRO-THUMBNAILS) ---

    private fun getOrDecodeClipThumbnail(uriString: String): Bitmap? {
        val cached = clipThumbnails[uriString]
        if (cached != null && !cached.isRecycled) return cached
        if (uriString in clipFailedUris) return null

        // Enforce strict max-5 thumbnail cap (~28 KB each in RGB_565)
        if (clipThumbnails.size >= MAX_CLIP_THUMBNAILS) {
            val iter = clipThumbnails.entries.iterator()
            if (iter.hasNext()) {
                val oldest = iter.next()
                if (!oldest.value.isRecycled) {
                    oldest.value.recycle()
                }
                iter.remove()
            }
        }

        return try {
            val uri = Uri.parse(uriString)
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOpts)
            }
            val srcW = boundsOpts.outWidth
            val srcH = boundsOpts.outHeight
            if (srcW <= 0 || srcH <= 0) {
                clipFailedUris.add(uriString)
                return null
            }

            var sampleSize = 1
            while ((srcW / (sampleSize * 2)) >= CLIP_THUMB_TARGET_PX &&
                (srcH / (sampleSize * 2)) >= CLIP_THUMB_TARGET_PX
            ) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            }
            if (decoded == null) {
                clipFailedUris.add(uriString)
                return null
            }

            val finalBmp = if (decoded.width > 140 || decoded.height > 140) {
                val scale = CLIP_THUMB_TARGET_PX.toFloat() / maxOf(decoded.width, decoded.height).toFloat()
                val dstW = (decoded.width * scale).toInt().coerceAtLeast(1)
                val dstH = (decoded.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(decoded, dstW, dstH, true)
                if (scaled !== decoded) {
                    decoded.recycle()
                }
                scaled
            } else {
                decoded
            }

            clipThumbnails[uriString] = finalBmp
            finalBmp
        } catch (_: Throwable) {
            clipFailedUris.add(uriString)
            null
        }
    }

    private fun imageMimeSubtitle(mimeType: String?): String {
        return when {
            mimeType == null -> CLIP_IMAGE_SUB
            mimeType.contains("png", ignoreCase = true) -> CLIP_IMAGE_PNG
            mimeType.contains("jpeg", ignoreCase = true) || mimeType.contains("jpg", ignoreCase = true) -> CLIP_IMAGE_JPEG
            mimeType.contains("webp", ignoreCase = true) -> CLIP_IMAGE_WEBP
            mimeType.contains("gif", ignoreCase = true) -> CLIP_IMAGE_GIF
            else -> CLIP_IMAGE_SUB
        }
    }

    private fun drawClipboardOverlay(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val headerH = stripHeightPx

        canvas.drawRect(0f, 0f, w, h, theme.bgPaint)
        canvas.drawRect(0f, 0f, w, headerH, theme.stripBgPaint)
        canvas.drawRect(0f, headerH - density, w, headerH, theme.stripDividerPaint)

        // Header Back button (left)
        val btnW = 68f * density
        if (clipHeaderBtnPressed == 1) {
            canvas.drawRect(0f, 0f, btnW, headerH - density, theme.keyPressedPaint)
        }
        val hdrBaseline = headerH * 0.5f - (theme.textSpecialPaint.descent() + theme.textSpecialPaint.ascent()) * 0.5f
        canvas.drawText(CLIP_BACK, btnW * 0.5f, hdrBaseline, theme.suggestionBoldPaint)

        // Header Title (center)
        canvas.drawText(CLIP_TITLE, w * 0.5f, hdrBaseline, theme.textSpecialPaint)

        // Header Clear button (right)
        if (clipHeaderBtnPressed == 2) {
            canvas.drawRect(w - btnW, 0f, w, headerH - density, theme.keyPressedPaint)
        }
        canvas.drawText(CLIP_CLEAR, w - btnW * 0.5f, hdrBaseline, theme.textSpacePaint)

        val clips = clipboardManager?.getClipItems()
        if (clips == null || clips.isEmpty()) {
            val emptyY = (headerH + h) * 0.5f - (theme.textSpacePaint.descent() + theme.textSpacePaint.ascent()) * 0.5f
            canvas.drawText(CLIP_EMPTY, w * 0.5f, emptyY, theme.textSpacePaint)
            clipMaxScrollY = 0f
            return
        }

        val itemH = 54f * density
        val itemGap = 6f * density
        val padH = 10f * density
        val stride = itemH + itemGap
        val listHeight = (h - headerH).coerceAtLeast(1f)
        clipMaxScrollY = (clips.size * stride + itemGap - listHeight).coerceAtLeast(0f)
        clipScrollY = clipScrollY.coerceIn(0f, clipMaxScrollY)

        canvas.save()
        canvas.clipRect(0f, headerH, w, h)

        val radius = theme.keyCornerRadius
        val hasBorder = theme.keyBorderWidth > 0f && (theme.colorKeyBorder ushr 24) > 0
        val actionBtnW = 36f * density
        val cardLeft = padH
        val cardRight = w - padH
        val deleteBtnRight = cardRight - 2f * density
        val deleteBtnLeft = deleteBtnRight - actionBtnW
        val pinBtnRight = deleteBtnLeft
        val pinBtnLeft = pinBtnRight - actionBtnW
        val contentRight = (pinBtnLeft - 6f * density).coerceAtLeast(cardLeft + 32f * density)

        val textPad = 12f * density
        val ellipsisW = theme.clipTextPaint.measureText(ELLIPSIS)
        val thumbSize = 40f * density
        val thumbPad = 7f * density
        val thumbRadius = 6f * density

        for (i in clips.indices) {
            val top = headerH + itemGap + i * stride - clipScrollY
            val bottom = top + itemH
            if (bottom < headerH || top > h) continue

            val item = clips[i]
            val isPinned = item.isPinned
            val isBodyPressed = (i == clipPressedIndex && clipPressedAction == 0)
            val isPinPressed = (i == clipPressedIndex && clipPressedAction == 1)
            val isDeletePressed = (i == clipPressedIndex && clipPressedAction == 2)

            theme.reusableRectF.set(cardLeft, top, cardRight, bottom)
            val cardPaint = if (isBodyPressed) theme.keyPressedPaint else theme.keyBgPaint
            canvas.drawRoundRect(theme.reusableRectF, radius, radius, cardPaint)

            if (isPinned) {
                // Crisp left accent indicator bar + subtle tinted accent border for pinned clips
                val barW = 3.2f * density
                theme.reusableRectF.set(cardLeft + 2f * density, top + 6f * density, cardLeft + 2f * density + barW, bottom - 6f * density)
                canvas.drawRoundRect(theme.reusableRectF, barW * 0.5f, barW * 0.5f, theme.accentPaint)
                theme.reusableRectF.set(cardLeft, top, cardRight, bottom)
                canvas.drawRoundRect(theme.reusableRectF, radius, radius, accentBorderPaint)
            } else if (hasBorder) {
                canvas.drawRoundRect(theme.reusableRectF, radius, radius, theme.keyBorderPaint)
            }

            // Right-edge action buttons: [Pin/Unpin vector pushpin] [Delete vector X]
            val cy = (top + bottom) * 0.5f
            val pinCx = (pinBtnLeft + pinBtnRight) * 0.5f
            val delCx = (deleteBtnLeft + deleteBtnRight) * 0.5f
            val actionCircleR = 14.5f * density

            if (isPinPressed) {
                canvas.drawCircle(pinCx, cy, actionCircleR, theme.keyPressedPaint)
            } else if (isPinned) {
                canvas.drawCircle(pinCx, cy, actionCircleR, accentTintPaint)
            }
            drawPushpinIcon(canvas, pinCx, cy, 6.4f * density, isPinned)

            if (isDeletePressed) {
                canvas.drawCircle(delCx, cy, actionCircleR, theme.keyPressedPaint)
            }
            drawCloseXIcon(canvas, delCx, cy, 4.6f * density, theme.iconSecondaryPaint)

            // Content area (left of action buttons)
            val leftInset = if (isPinned) 5f * density else 0f
            if (item.isImage) {
                val thumbLeft = cardLeft + thumbPad + leftInset
                val thumbTop = top + (itemH - thumbSize) * 0.5f
                clipThumbDstRect.set(thumbLeft, thumbTop, thumbLeft + thumbSize, thumbTop + thumbSize)

                val uriStr = item.imageUri
                val bmp = if (!uriStr.isNullOrEmpty()) getOrDecodeClipThumbnail(uriStr) else null
                if (bmp != null && !bmp.isRecycled) {
                    val bw = bmp.width
                    val bh = bmp.height
                    val side = minOf(bw, bh)
                    val sx = (bw - side) / 2
                    val sy = (bh - side) / 2
                    clipThumbSrcRect.set(sx, sy, sx + side, sy + side)

                    theme.reusablePath.reset()
                    theme.reusablePath.addRoundRect(clipThumbDstRect, thumbRadius, thumbRadius, Path.Direction.CW)
                    canvas.save()
                    canvas.clipPath(theme.reusablePath)
                    canvas.drawBitmap(bmp, clipThumbSrcRect, clipThumbDstRect, clipThumbPaint)
                    canvas.restore()
                    if (hasBorder) {
                        canvas.drawRoundRect(clipThumbDstRect, thumbRadius, thumbRadius, theme.keyBorderPaint)
                    }
                } else {
                    canvas.drawRoundRect(clipThumbDstRect, thumbRadius, thumbRadius, theme.keySpecialPaint)
                    drawImagePlaceholderIcon(
                        canvas,
                        clipThumbDstRect.centerX(),
                        clipThumbDstRect.centerY(),
                        thumbSize * 0.28f,
                        theme.iconSecondaryPaint
                    )
                }

                val labelX = clipThumbDstRect.right + 10f * density
                canvas.save()
                canvas.clipRect(labelX, top, contentRight, bottom)
                val titleY = top + itemH * 0.40f -
                    (theme.clipTextPaint.descent() + theme.clipTextPaint.ascent()) * 0.5f
                canvas.drawText(CLIP_IMAGE_LABEL, labelX, titleY, theme.clipTextPaint)

                val subPaint = theme.textSecondaryPaint
                val prevAlign = subPaint.textAlign
                subPaint.textAlign = Paint.Align.LEFT
                val subY = top + itemH * 0.69f -
                    (subPaint.descent() + subPaint.ascent()) * 0.5f
                canvas.drawText(imageMimeSubtitle(item.mimeType), labelX, subY, subPaint)
                subPaint.textAlign = prevAlign
                canvas.restore()
                continue
            }

            val clip = item.text ?: ""
            // Clamp to first line cleanly without allocating new Strings
            var lineEnd = clip.indexOf('\n')
            val hasMoreLines = lineEnd >= 0
            if (lineEnd < 0) lineEnd = clip.length

            val baseline = cy - (theme.clipTextPaint.descent() + theme.clipTextPaint.ascent()) * 0.5f
            val textX = cardLeft + textPad + leftInset
            val maxTextW = (contentRight - textX).coerceAtLeast(16f * density)

            val measured = theme.clipTextPaint.measureText(clip, 0, lineEnd)
            if (!hasMoreLines && measured <= maxTextW) {
                canvas.drawText(clip, 0, lineEnd, textX, baseline, theme.clipTextPaint)
            } else {
                val fitCount = theme.clipTextPaint.breakText(
                    clip,
                    0,
                    lineEnd,
                    true,
                    (maxTextW - ellipsisW).coerceAtLeast(8f),
                    null
                )
                canvas.drawText(clip, 0, fitCount, textX, baseline, theme.clipTextPaint)
                val fitW = theme.clipTextPaint.measureText(clip, 0, fitCount)
                canvas.drawText(ELLIPSIS, textX + fitW, baseline, theme.clipTextPaint)
            }
        }

        canvas.restore()
    }

    private fun handleClipboardTouch(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val headerH = stripHeightPx
        val btnW = 68f * density
        val w = width.toFloat()
        val padH = 10f * density
        val cardRight = w - padH
        val actionBtnW = 36f * density
        val deleteBtnLeft = cardRight - 2f * density - actionBtnW
        val pinBtnLeft = deleteBtnLeft - actionBtnW

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mainHandler.removeCallbacks(clipLongPressRunnable)
                clipTouchStartY = y
                clipLastTouchY = y
                clipDragging = false
                clipPressedIndex = -1
                clipPressedAction = 0
                clipLongPressTriggered = false
                clipHeaderBtnPressed = 0

                if (y < headerH) {
                    if (x < btnW) {
                        clipHeaderBtnPressed = 1
                    } else if (x > w - btnW) {
                        clipHeaderBtnPressed = 2
                    }
                } else {
                    val idx = findClipIndexAt(y, headerH)
                    clipPressedIndex = idx
                    if (idx >= 0) {
                        clipPressedAction = when {
                            x >= deleteBtnLeft && x <= cardRight + 4f * density -> 2 // Delete
                            x >= pinBtnLeft && x < deleteBtnLeft -> 1 // Pin
                            else -> 0 // Card body
                        }
                        if (clipPressedAction == 0) {
                            mainHandler.postDelayed(clipLongPressRunnable, longPressDelayMs)
                        }
                    }
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (clipHeaderBtnPressed != 0) {
                    if (y >= headerH) {
                        clipHeaderBtnPressed = 0
                        invalidate()
                    }
                    return true
                }
                if (!clipDragging && abs(y - clipTouchStartY) > touchSlop) {
                    mainHandler.removeCallbacks(clipLongPressRunnable)
                    clipDragging = true
                    clipPressedIndex = -1
                    clipPressedAction = 0
                }
                if (clipDragging) {
                    val dy = clipLastTouchY - y
                    clipScrollY = (clipScrollY + dy).coerceIn(0f, clipMaxScrollY)
                    clipLastTouchY = y
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(clipLongPressRunnable)
                if (clipLongPressTriggered) {
                    clipLongPressTriggered = false
                    clipPressedIndex = -1
                    clipPressedAction = 0
                    clipDragging = false
                    invalidate()
                    return true
                }
                if (clipHeaderBtnPressed == 1 && y < headerH && x < btnW) {
                    triggerHaptic()
                    closeClipboardOverlay()
                } else if (clipHeaderBtnPressed == 2 && y < headerH && x > w - btnW) {
                    triggerHaptic()
                    clipboardManager?.clear(includePinned = false)
                    clearClipboardThumbnails()
                    if (clipboardManager?.getClipItems()?.isEmpty() != false) {
                        closeClipboardOverlay()
                    }
                } else if (!clipDragging && clipPressedIndex >= 0) {
                    val idx = clipPressedIndex
                    val action = clipPressedAction
                    when (action) {
                        1 -> {
                            // Pin / Unpin clip in place (togglePin returns new pinned state, so always haptic + redraw)
                            val cm = clipboardManager
                            val clips = cm?.getClipItems()
                            if (cm != null && clips != null && idx in clips.indices) {
                                cm.togglePin(idx)
                                triggerHaptic()
                            }
                        }
                        2 -> {
                            // Delete single clip
                            if (clipboardManager?.removeAt(idx) == true) {
                                triggerHaptic()
                                clearClipboardThumbnails()
                            }
                        }
                        else -> {
                            val clips = clipboardManager?.getClipItems()
                            if (clips != null && idx in clips.indices) {
                                val selected = clips[idx]
                                triggerHaptic()
                                closeClipboardOverlay()
                                if (selected.isImage) {
                                    val uriStr = selected.imageUri
                                    if (!uriStr.isNullOrEmpty()) {
                                        actionListener?.onImageClipSelected(uriStr, selected.mimeType ?: "image/*")
                                    }
                                } else {
                                    val text = selected.text
                                    if (!text.isNullOrEmpty()) {
                                        actionListener?.onClipSelected(text)
                                    }
                                }
                            } else {
                                closeClipboardOverlay()
                            }
                        }
                    }
                }
                clipHeaderBtnPressed = 0
                clipPressedIndex = -1
                clipPressedAction = 0
                clipDragging = false
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(clipLongPressRunnable)
                clipHeaderBtnPressed = 0
                clipPressedIndex = -1
                clipPressedAction = 0
                clipLongPressTriggered = false
                clipDragging = false
                invalidate()
                return true
            }
        }
        return true
    }

    private fun findClipIndexAt(y: Float, headerH: Float): Int {
        val clips = clipboardManager?.getClipItems() ?: return -1
        if (clips.isEmpty()) return -1
        val itemH = 54f * density
        val itemGap = 6f * density
        val stride = itemH + itemGap
        val relY = y - headerH - itemGap + clipScrollY
        if (relY < 0f) return -1
        val idx = (relY / stride).toInt()
        val inItemY = relY - idx * stride
        return if (idx in clips.indices && inItemY <= itemH) idx else -1
    }

    private fun openClipboardOverlay() {
        exitEmojiSearchMode(notifyInvalidate = false)
        resetAllTouches()
        clearKeyAnimations()
        isClipboardOpen = true
        clipScrollY = 0f
        clipPressedIndex = -1
        clipPressedAction = 0
        clipLongPressTriggered = false
        clipHeaderBtnPressed = 0
        invalidate()
    }

    private fun closeClipboardOverlay() {
        mainHandler.removeCallbacks(clipLongPressRunnable)
        isClipboardOpen = false
        clearClipboardThumbnails()
        clipPressedIndex = -1
        clipPressedAction = 0
        clipLongPressTriggered = false
        clipHeaderBtnPressed = 0
        invalidate()
    }

    // --- MULTI-TOUCH & GESTURE INPUT HANDLING ---

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isEmojiMode) {
            val panel = getOrCreateEmojiPanel()
            val x = event.x
            val y = event.y
            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    panel.handleTouchDown(x, y)
                    invalidate()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (panel.handleTouchMove(x, y)) {
                        invalidate()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    panel.handleTouchUp(x, y)
                    invalidate()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    panel.handleTouchCancel()
                    invalidate()
                    true
                }
                else -> super.onTouchEvent(event)
            }
        }

        if (isClipboardOpen) {
            return handleClipboardTouch(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val actionIndex = event.actionIndex
                val pointerId = event.getPointerId(actionIndex)
                val px = event.getX(actionIndex)
                val py = event.getY(actionIndex)

                if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                    currentStripHeight > 0f &&
                    py < currentStripHeight &&
                    !hasAnyActiveKeyPointer()
                ) {
                    stripPointerId = pointerId
                    if (isEmojiSearchMode) {
                        handleEmojiSearchStripDown(px, py)
                    } else {
                        handleStripDownOrMove(px, py)
                    }
                    invalidate()
                    return true
                }

                // One-Handed Mode Side Gutter controls (Expand / Flip)
                if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                    oneHandedMode != KeyboardPrefs.ONE_HANDED_OFF &&
                    !oneHandedGutterRect.isEmpty &&
                    oneHandedGutterRect.contains(px, py) &&
                    !hasAnyActiveKeyPointer()
                ) {
                    oneHandedGutterPointerId = pointerId
                    pressedGutterBtn = hitTestOneHandedGutter(px, py)
                    invalidate()
                    return true
                }

                handlePointerDown(pointerId, px, py)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (stripPointerId != -1) {
                    val idx = event.findPointerIndex(stripPointerId)
                    if (idx >= 0) {
                        val sx = event.getX(idx)
                        val sy = event.getY(idx)
                        if (isEmojiSearchMode) {
                            handleEmojiSearchStripMove(sx, sy)
                        } else if (sy > currentStripHeight + touchSlop) {
                            // Dragged vertically out of suggestion strip -> cancel selection cleanly
                            pressedSuggestionIndex = -1
                            pressedStripAction = STRIP_ACTION_NONE
                            stripPointerId = -1
                        } else {
                            handleStripDownOrMove(sx, sy)
                        }
                        invalidate()
                    }
                    return true
                }

                if (oneHandedGutterPointerId != -1) {
                    val idx = event.findPointerIndex(oneHandedGutterPointerId)
                    if (idx >= 0) {
                        val gx = event.getX(idx)
                        val gy = event.getY(idx)
                        val nextHit = if (oneHandedGutterRect.contains(gx, gy)) {
                            hitTestOneHandedGutter(gx, gy)
                        } else {
                            0
                        }
                        if (nextHit != pressedGutterBtn) {
                            pressedGutterBtn = nextHit
                            invalidate()
                        }
                    }
                    return true
                }

                handlePointersMove(event)
                return true
            }

            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP -> {
                val actionIndex = event.actionIndex
                val pointerId = event.getPointerId(actionIndex)

                if (pointerId == stripPointerId) {
                    val sx = event.getX(actionIndex)
                    val sy = event.getY(actionIndex)
                    if (isEmojiSearchMode) {
                        handleEmojiSearchStripUp(sx, sy)
                    } else {
                        handleStripUp()
                    }
                    stripPointerId = -1
                    invalidate()
                    return true
                }

                if (pointerId == oneHandedGutterPointerId) {
                    handleOneHandedGutterUp()
                    oneHandedGutterPointerId = -1
                    invalidate()
                    return true
                }

                handlePointerUp(pointerId)

                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    // Safety net: ensure no lingering pressed states after final finger lifts
                    cleanupAnyStrayPointers()
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                resetAllTouches()
                invalidate()
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    private fun handlePointerDown(pointerId: Int, px: Float, py: Float) {
        val key = layout.findKeyAt(px, py) ?: return

        // KEY ROLLOVER: If this new pointer hits a normal key while another finger is still holding
        // a different normal key, immediately commit the earlier key in press order (zero drops!)
        if (key.type == KeyModel.TYPE_NORMAL) {
            for (s in 0 until MAX_POINTERS) {
                if (pointerIds[s] != -1) {
                    val prevKey = pointerKeys[s]
                    if (prevKey != null &&
                        prevKey !== key &&
                        prevKey.type == KeyModel.TYPE_NORMAL &&
                        !pointerLongPressConsumed[s]
                    ) {
                        if (shiftHoldSlot != -1) {
                            shiftUsedAsChord = true
                        }
                        dispatchKeyAction(prevKey)
                        releaseKeyVisual(prevKey, s)
                        pointerKeys[s] = null
                        if (longPressSlot == s) {
                            mainHandler.removeCallbacks(longPressRunnable)
                            longPressSlot = -1
                        }
                    }
                }
            }
        }

        val slot = findFreeSlot()
        if (slot < 0) return

        pointerIds[slot] = pointerId
        pointerKeys[slot] = key
        pointerDownX[slot] = px
        pointerDownY[slot] = py
        pointerLongPressConsumed[slot] = false

        key.isPressed = true
        popupLongPressTriggered = false
        if (key.type == KeyModel.TYPE_NORMAL) {
            activePopupKey = key
        }
        triggerHaptic()
        playKeySound(key.type)

        when (key.type) {
            KeyModel.TYPE_DELETE -> {
                deleteSlot = slot
                deleteSwipeTriggered = false
                if (isEmojiSearchMode) {
                    handleEmojiSearchBackspace()
                } else {
                    actionListener?.onBackspace()
                }
                mainHandler.removeCallbacks(backspaceRepeatRunnable)
                mainHandler.postDelayed(backspaceRepeatRunnable, 350L)
            }

            KeyModel.TYPE_SPACE -> {
                spaceSlot = slot
                spaceGlideActive = false
                spaceGlideSelectionMode = false
                spaceLastStepX = px
                if (!isEmojiSearchMode && spaceLongPressLangEnabled && layout.hasMultipleLanguages) {
                    mainHandler.removeCallbacks(longPressRunnable)
                    longPressSlot = slot
                    mainHandler.postDelayed(longPressRunnable, longPressDelayMs)
                }
            }

            KeyModel.TYPE_SHIFT -> {
                if (layout.currentMode == KeyboardLayout.Mode.ALPHA) {
                    shiftHoldSlot = slot
                    shiftWasActiveBeforeHold = layout.isShifted || layout.isCapsLock
                    shiftUsedAsChord = false
                    if (!shiftWasActiveBeforeHold) {
                        layout.updateShiftState(shifted = true, caps = false)
                    }
                }
            }

            KeyModel.TYPE_ENTER -> {
                if (!isEmojiSearchMode && longPressEnterAction != KeyboardPrefs.ENTER_LONG_PRESS_NONE) {
                    mainHandler.removeCallbacks(longPressRunnable)
                    longPressSlot = slot
                    mainHandler.postDelayed(longPressRunnable, longPressDelayMs)
                }
            }

            KeyModel.TYPE_SWITCH_SYMBOLS,
            KeyModel.TYPE_SWITCH_LANG -> {
                if (!isEmojiSearchMode) {
                    mainHandler.removeCallbacks(longPressRunnable)
                    longPressSlot = slot
                    mainHandler.postDelayed(longPressRunnable, longPressDelayMs)
                }
            }

            KeyModel.TYPE_NORMAL -> {
                if (!isEmojiSearchMode && longPressSymbolsEnabled && layout.currentMode == KeyboardLayout.Mode.ALPHA) {
                    val hasAccents = getAccentVariantsForKey(key) != null
                    val hasSub = !key.subLabel.isNullOrEmpty()
                    if (hasAccents || hasSub) {
                        mainHandler.removeCallbacks(longPressRunnable)
                        longPressSlot = slot
                        mainHandler.postDelayed(longPressRunnable, longPressDelayMs)
                    }
                }
            }
        }

        invalidate()
    }

    private fun handlePointersMove(event: MotionEvent) {
        var needsInvalidate = false
        val count = event.pointerCount

        for (i in 0 until count) {
            val pid = event.getPointerId(i)
            val slot = findSlotByPointerId(pid)
            if (slot < 0) continue

            val key = pointerKeys[slot] ?: continue
            val px = event.getX(i)
            val py = event.getY(i)

            // 0. Enter Long-Press 2-Option Floating Mini-Popup horizontal selector
            if (isEnterPopupActive && slot == enterPopupSlot) {
                val nextOption = if (px < enterPopupCenterX) 0 else 1
                if (nextOption != enterPopupSelectedOption) {
                    enterPopupSelectedOption = nextOption
                    triggerHaptic()
                    needsInvalidate = true
                }
                continue
            }

            // 0b. Accented Character Long-Press Floating Pill horizontal selector
            if (isAccentPopupActive && slot == accentPopupSlot && accentPopupCount > 0) {
                if (!accentPopupDragged && abs(px - accentPopupAnchorX) > 6f * density) {
                    accentPopupDragged = true
                }
                if (accentPopupDragged && accentPopupCellWidth > 0f) {
                    val nextIdx = ((px - accentPopupRect.left) / accentPopupCellWidth)
                        .toInt()
                        .coerceIn(0, accentPopupCount - 1)
                    if (nextIdx != accentPopupSelectedIndex) {
                        accentPopupSelectedIndex = nextIdx
                        triggerHaptic()
                        needsInvalidate = true
                    }
                }
                continue
            }

            // 1. Spacebar Horizontal Cursor Glide / Shift+Spacebar Drag Selection
            if (slot == spaceSlot && key.type == KeyModel.TYPE_SPACE) {
                if (!isEmojiSearchMode && spaceCursorGlideEnabled) {
                    if (!spaceGlideActive && abs(px - pointerDownX[slot]) > touchSlop) {
                        spaceGlideActive = true
                        spaceLastStepX = px
                        val isSelectChord = (shiftHoldSlot != -1) || layout.isShifted || layout.isCapsLock
                        spaceGlideSelectionMode = isSelectChord
                        if (isSelectChord && shiftHoldSlot != -1) {
                            shiftUsedAsChord = true
                        }
                        if (longPressSlot == slot) {
                            mainHandler.removeCallbacks(longPressRunnable)
                            longPressSlot = -1
                        }
                        needsInvalidate = true
                    }
                    if (spaceGlideActive) {
                        val isSelectChord = (shiftHoldSlot != -1) || layout.isShifted || layout.isCapsLock
                        if (isSelectChord) {
                            if (!spaceGlideSelectionMode) {
                                spaceGlideSelectionMode = true
                                needsInvalidate = true
                            }
                            if (shiftHoldSlot != -1) {
                                shiftUsedAsChord = true
                            }
                        } else if (spaceGlideSelectionMode) {
                            spaceGlideSelectionMode = false
                            needsInvalidate = true
                        }
                        val stepPx = 18f * density
                        val dx = px - spaceLastStepX
                        if (abs(dx) >= stepPx) {
                            val steps = (dx / stepPx).toInt()
                            spaceLastStepX += steps * stepPx
                            if (spaceGlideSelectionMode) {
                                actionListener?.onSelectCursor(steps)
                            } else {
                                actionListener?.onMoveCursor(steps)
                            }
                            triggerHaptic()
                        }
                    }
                }
                continue
            }

            // 2. Backspace Left-Swipe to Delete Whole Word (or clear Emoji Search query)
            if (slot == deleteSlot && key.type == KeyModel.TYPE_DELETE) {
                if (swipeDeleteWordEnabled && !deleteSwipeTriggered && (pointerDownX[slot] - px) > 36f * density) {
                    deleteSwipeTriggered = true
                    mainHandler.removeCallbacks(backspaceRepeatRunnable)
                    triggerHaptic()
                    playKeySound(KeyModel.TYPE_DELETE)
                    if (isEmojiSearchMode) {
                        if (emojiSearchQuery.isNotEmpty()) {
                            emojiSearchQuery.setLength(0)
                            refreshEmojiSearchResults()
                        } else {
                            exitEmojiSearchMode()
                        }
                    } else {
                        actionListener?.onDeleteWord()
                    }
                    needsInvalidate = true
                }
                continue
            }

            // 3. Keep Shift locked while chording
            if (slot == shiftHoldSlot && key.type == KeyModel.TYPE_SHIFT) {
                continue
            }

            // 4. Slide to adjacent key (if long press hasn't already fired)
            if (!pointerLongPressConsumed[slot] && !key.contains(px, py)) {
                val newKey = layout.findKeyAt(px, py)
                if (newKey !== key) {
                    releaseKeyVisual(key, slot)
                    if (longPressSlot == slot) {
                        mainHandler.removeCallbacks(longPressRunnable)
                        longPressSlot = -1
                    }
                    pointerKeys[slot] = newKey
                    if (newKey != null) {
                        newKey.isPressed = true
                        if (newKey.type == KeyModel.TYPE_NORMAL) {
                            activePopupKey = newKey
                            popupLongPressTriggered = false
                        }
                    } else if (activePopupKey === key) {
                        activePopupKey = findLatestPressedNormalKey()
                    }
                    needsInvalidate = true
                }
            }
        }

        if (needsInvalidate) {
            invalidate()
        }
    }

    private fun handlePointerUp(pointerId: Int) {
        val slot = findSlotByPointerId(pointerId)
        if (slot < 0) return

        val key = pointerKeys[slot]
        val wasLongConsumed = pointerLongPressConsumed[slot]

        // Clear slot first so helper checks reflect remaining active pointers
        pointerIds[slot] = -1
        pointerKeys[slot] = null
        pointerLongPressConsumed[slot] = false

        if (longPressSlot == slot) {
            mainHandler.removeCallbacks(longPressRunnable)
            longPressSlot = -1
        }
        if (deleteSlot == slot) {
            mainHandler.removeCallbacks(backspaceRepeatRunnable)
            deleteSlot = -1
            deleteSwipeTriggered = false
        }

        val wasSpaceGlide = (spaceSlot == slot && spaceGlideActive)
        val wasSpaceGlideSelection = (wasSpaceGlide && spaceGlideSelectionMode)
        if (spaceSlot == slot) {
            spaceSlot = -1
            spaceGlideActive = false
            spaceGlideSelectionMode = false
        }
        if (wasSpaceGlideSelection && layout.isShifted && !layout.isCapsLock && shiftHoldSlot == -1) {
            // Release one-shot Shift cleanly after finishing Shift+Spacebar drag selection
            layout.updateShiftState(shifted = false, caps = false)
        }

        val wasEnterPopup = (isEnterPopupActive && enterPopupSlot == slot)
        val chosenEnterOption = enterPopupSelectedOption
        if (wasEnterPopup) {
            isEnterPopupActive = false
            enterPopupSlot = -1
        }

        val wasAccentPopup = (isAccentPopupActive && accentPopupSlot == slot)
        val chosenAccent: String? = if (wasAccentPopup && accentPopupSelectedIndex in 0 until accentPopupCount) {
            accentPopupChars[accentPopupSelectedIndex]
        } else {
            null
        }
        if (wasAccentPopup) {
            isAccentPopupActive = false
            accentPopupSlot = -1
            accentPopupCount = 0
            accentPopupDragged = false
        }

        val wasShiftHold = (shiftHoldSlot == slot)
        if (wasShiftHold) {
            shiftHoldSlot = -1
        }

        if (key != null) {
            if (!isKeyHeldByAnySlot(key)) {
                val wasPressed = key.isPressed
                key.isPressed = false
                if (wasPressed) {
                    recordKeyReleaseAnim(key)
                }
            }
            if (activePopupKey === key) {
                activePopupKey = findLatestPressedNormalKey()
                popupLongPressTriggered = false
            }

            if (wasEnterPopup) {
                if (chosenEnterOption == 0) {
                    setEmojiMode(true)
                } else {
                    actionListener?.onInsertNewline()
                }
                return
            }

            if (wasAccentPopup) {
                if (!chosenAccent.isNullOrEmpty()) {
                    for (c in chosenAccent) {
                        actionListener?.onChar(c)
                    }
                    if (layout.isShifted && !layout.isCapsLock && shiftHoldSlot == -1) {
                        layout.updateShiftState(shifted = false, caps = false)
                    }
                }
                return
            }

            if (!wasLongConsumed && !wasSpaceGlide) {
                if (wasShiftHold && key.type == KeyModel.TYPE_SHIFT && layout.currentMode == KeyboardLayout.Mode.ALPHA) {
                    handleAlphaShiftUp()
                } else {
                    if (key.type == KeyModel.TYPE_NORMAL && shiftHoldSlot != -1) {
                        shiftUsedAsChord = true
                    }
                    dispatchKeyAction(key)
                }
            }
        }
    }

    private fun handleAlphaShiftUp() {
        val now = System.currentTimeMillis()
        if (shiftUsedAsChord) {
            // Released after chording Shift + letter(s) -> return to lowercase cleanly
            shiftUsedAsChord = false
            layout.updateShiftState(shifted = false, caps = false)
            return
        }

        if (!shiftWasActiveBeforeHold) {
            // Single tap activated one-shot Shift on DOWN; keep it active
            lastShiftPressTime = now
        } else {
            // Shift or Caps Lock was already active before this press
            if (layout.isShifted && !layout.isCapsLock && (now - lastShiftPressTime < 400L)) {
                layout.updateShiftState(shifted = false, caps = true)
            } else {
                layout.updateShiftState(shifted = false, caps = false)
            }
            lastShiftPressTime = now
        }
    }

    // --- SUGGESTION / UTILITY STRIP TOUCH HANDLING ---

    private fun handleStripDownOrMove(x: Float, y: Float) {
        val sm = suggestionManager
        val count = sm?.activeSlotCount ?: 0
        if (count > 0 && width > 0) {
            pressedStripAction = STRIP_ACTION_NONE
            val slotW = width / 3f
            val idx = (x / slotW).toInt().coerceIn(0, 2)
            pressedSuggestionIndex = if (idx < count) idx else -1
            return
        }

        pressedSuggestionIndex = -1
        if (!showTopUtilityBar) {
            pressedStripAction = STRIP_ACTION_NONE
            return
        }
        if (y < currentStripHeight && width > 0) {
            val btnW = stripSideBtnWidthPx
            val w = width.toFloat()
            val showTopEmoji = (emojiButtonMode == KeyboardPrefs.EMOJI_BTN_TOP_BAR)
            val leftReserved = if (showTopEmoji) btnW * 2f else btnW
            val settingsW = if (showSettingsKey) btnW else 0f
            val oneHandedW = btnW
            val multiLang = layout.hasMultipleLanguages
            val langPillW = if (multiLang) stripLangBtnWidthPx else 0f
            val rightReserved = settingsW + oneHandedW + langPillW
            val ohLeft = w - settingsW - oneHandedW
            pressedStripAction = when {
                x < btnW -> STRIP_ACTION_CLIPBOARD
                showTopEmoji && x < btnW * 2f -> STRIP_ACTION_EMOJI
                showSettingsKey && x >= w - settingsW -> STRIP_ACTION_SETTINGS
                x >= ohLeft && x < w - settingsW -> STRIP_ACTION_ONE_HANDED
                multiLang && x >= w - rightReserved && x < ohLeft -> STRIP_ACTION_LANG_PILL
                multiLang && x >= leftReserved && x < w - rightReserved -> STRIP_ACTION_LANG_CENTER
                else -> STRIP_ACTION_NONE
            }
        } else {
            pressedStripAction = STRIP_ACTION_NONE
        }
    }

    private fun handleStripUp() {
        val sm = suggestionManager
        if (pressedSuggestionIndex >= 0 && sm != null) {
            val idx = pressedSuggestionIndex
            pressedSuggestionIndex = -1
            if (idx in 0 until sm.activeSlotCount) {
                val word = sm.slots[idx]
                if (word.isNotEmpty()) {
                    triggerHaptic()
                    playKeySound(KeyModel.TYPE_NORMAL)
                    actionListener?.onSuggestionSelected(word)
                }
            }
            return
        }

        val action = pressedStripAction
        pressedStripAction = STRIP_ACTION_NONE
        when (action) {
            STRIP_ACTION_CLIPBOARD -> {
                triggerHaptic()
                playKeySound(KeyModel.TYPE_NORMAL)
                openClipboardOverlay()
            }
            STRIP_ACTION_EMOJI -> {
                triggerHaptic()
                playKeySound(KeyModel.TYPE_NORMAL)
                setEmojiMode(true)
            }
            STRIP_ACTION_ONE_HANDED -> {
                triggerHaptic()
                playKeySound(KeyModel.TYPE_NORMAL)
                val nextMode = if (oneHandedMode == KeyboardPrefs.ONE_HANDED_OFF) {
                    KeyboardPrefs.ONE_HANDED_RIGHT
                } else {
                    KeyboardPrefs.ONE_HANDED_OFF
                }
                applyOneHandedModeFromUi(nextMode)
            }
            STRIP_ACTION_LANG_PILL,
            STRIP_ACTION_LANG_CENTER -> {
                if (layout.hasMultipleLanguages) {
                    triggerHaptic()
                    playKeySound(KeyModel.TYPE_NORMAL)
                    actionListener?.onLanguageToggle()
                }
            }
            STRIP_ACTION_SETTINGS -> {
                triggerHaptic()
                playKeySound(KeyModel.TYPE_NORMAL)
                actionListener?.onOpenSettings()
            }
        }
    }

    // --- ONE-HANDED MODE GUTTER TOUCH HANDLING ---

    private fun hitTestOneHandedGutter(x: Float, y: Float): Int {
        if (oneHandedGutterRect.isEmpty) return 0
        val pad = 10f * density
        if (x >= oneHandedExpandBtnRect.left - pad &&
            x <= oneHandedExpandBtnRect.right + pad &&
            y >= oneHandedExpandBtnRect.top - pad &&
            y <= oneHandedExpandBtnRect.bottom + pad
        ) {
            return 1
        }
        if (x >= oneHandedFlipBtnRect.left - pad &&
            x <= oneHandedFlipBtnRect.right + pad &&
            y >= oneHandedFlipBtnRect.top - pad &&
            y <= oneHandedFlipBtnRect.bottom + pad
        ) {
            return 2
        }
        return 0
    }

    private fun handleOneHandedGutterUp() {
        val btn = pressedGutterBtn
        pressedGutterBtn = 0
        when (btn) {
            1 -> {
                // Expand back to full width
                triggerHaptic()
                playKeySound(KeyModel.TYPE_NORMAL)
                applyOneHandedModeFromUi(KeyboardPrefs.ONE_HANDED_OFF)
            }
            2 -> {
                // Flip LEFT <-> RIGHT
                triggerHaptic()
                playKeySound(KeyModel.TYPE_NORMAL)
                val nextMode = if (oneHandedMode == KeyboardPrefs.ONE_HANDED_LEFT) {
                    KeyboardPrefs.ONE_HANDED_RIGHT
                } else {
                    KeyboardPrefs.ONE_HANDED_LEFT
                }
                applyOneHandedModeFromUi(nextMode)
            }
        }
    }

    private fun applyOneHandedModeFromUi(mode: Int) {
        if (oneHandedMode == mode) return
        oneHandedMode = mode
        if (width > 0 && height > 0) {
            remeasureInternal(width, height)
        }
        actionListener?.onOneHandedModeChanged(mode)
        invalidate()
    }

    // --- INLINE ENGLISH EMOJI SEARCH HANDLING ---

    fun enterEmojiSearchMode() {
        resetAllTouches()
        clearKeyAnimations()
        if (isClipboardOpen) {
            isClipboardOpen = false
            clearClipboardThumbnails()
        }
        isEmojiMode = false
        isEmojiSearchMode = true
        layout.currentMode = KeyboardLayout.Mode.ALPHA
        layout.updateShiftState(shifted = false, caps = false)
        emojiSearchQuery.setLength(0)
        emojiSearchResultsScrollX = 0f
        emojiSearchDragging = false
        emojiSearchPressedResultIdx = -1
        emojiSearchClosePressed = false
        refreshEmojiSearchResults()
        requestLayout()
        if (width > 0 && height > 0) {
            remeasureInternal(width, height)
        }
        invalidate()
    }

    fun exitEmojiSearchMode(notifyInvalidate: Boolean = true) {
        if (!isEmojiSearchMode) return
        isEmojiSearchMode = false
        emojiSearchQuery.setLength(0)
        emojiSearchResults.clear()
        emojiSearchResultsScrollX = 0f
        emojiSearchResultsMaxScrollX = 0f
        emojiSearchDragging = false
        emojiSearchPressedResultIdx = -1
        emojiSearchClosePressed = false
        EmojiSearchIndex.release()
        if (notifyInvalidate) {
            requestLayout()
            if (width > 0 && height > 0) {
                remeasureInternal(width, height)
            }
            invalidate()
        }
    }

    private fun refreshEmojiSearchResults() {
        emojiSearchResults.clear()
        emojiSearchResultsScrollX = 0f
        if (emojiSearchQuery.isEmpty()) {
            // Show recent emojis first, topped up with popular smileys for instant 1-tap picks
            val recents = historyManager?.getRecentList()
            if (recents != null) {
                for (i in recents.indices) {
                    if (emojiSearchResults.size >= 16) break
                    emojiSearchResults.add(recents[i])
                }
            }
            val defaults = EmojiData.SMILEYS
            for (i in defaults.indices) {
                if (emojiSearchResults.size >= 16) break
                val e = defaults[i]
                if (!emojiSearchResults.contains(e)) {
                    emojiSearchResults.add(e)
                }
            }
        } else {
            EmojiSearchIndex.search(emojiSearchQuery.toString(), emojiSearchResults, 28)
        }
    }

    private fun handleEmojiSearchChar(c: Char) {
        if (emojiSearchQuery.length < MAX_EMOJI_SEARCH_LEN) {
            emojiSearchQuery.append(c.lowercaseChar())
            refreshEmojiSearchResults()
        }
    }

    private fun handleEmojiSearchSpace() {
        if (emojiSearchQuery.isNotEmpty() &&
            emojiSearchQuery.length < MAX_EMOJI_SEARCH_LEN &&
            emojiSearchQuery[emojiSearchQuery.length - 1] != ' '
        ) {
            emojiSearchQuery.append(' ')
            refreshEmojiSearchResults()
        }
    }

    private fun handleEmojiSearchBackspace() {
        if (emojiSearchQuery.isNotEmpty()) {
            emojiSearchQuery.setLength(emojiSearchQuery.length - 1)
            refreshEmojiSearchResults()
        } else {
            exitEmojiSearchMode()
        }
    }

    private fun handleEmojiSearchStripDown(x: Float, y: Float) {
        emojiSearchTouchStartX = x
        emojiSearchLastTouchX = x
        emojiSearchDragging = false
        emojiSearchPressedResultIdx = -1
        emojiSearchClosePressed = false

        if (emojiSearchCloseBtnRect.contains(x, y)) {
            emojiSearchClosePressed = true
            return
        }
        if (emojiSearchResultsRect.contains(x, y) && emojiSearchResults.isNotEmpty()) {
            val cellW = 38f * density
            val idx = ((x - emojiSearchResultsRect.left + emojiSearchResultsScrollX) / cellW).toInt()
            if (idx in emojiSearchResults.indices) {
                emojiSearchPressedResultIdx = idx
            }
        }
    }

    private fun handleEmojiSearchStripMove(x: Float, y: Float) {
        if (emojiSearchClosePressed) {
            if (!emojiSearchCloseBtnRect.contains(x, y)) {
                emojiSearchClosePressed = false
            }
            return
        }
        if (!emojiSearchDragging && emojiSearchResultsMaxScrollX > 0f && abs(x - emojiSearchTouchStartX) > touchSlop) {
            emojiSearchDragging = true
            emojiSearchPressedResultIdx = -1
        }
        if (emojiSearchDragging) {
            val dx = emojiSearchLastTouchX - x
            emojiSearchResultsScrollX = (emojiSearchResultsScrollX + dx).coerceIn(0f, emojiSearchResultsMaxScrollX)
            emojiSearchLastTouchX = x
        } else if (y > currentStripHeight + touchSlop) {
            emojiSearchPressedResultIdx = -1
        }
    }

    private fun handleEmojiSearchStripUp(x: Float, y: Float) {
        if (emojiSearchClosePressed) {
            emojiSearchClosePressed = false
            if (emojiSearchCloseBtnRect.contains(x, y)) {
                triggerHaptic()
                playKeySound(KeyModel.TYPE_NORMAL)
                exitEmojiSearchMode()
            }
            return
        }
        val wasDragging = emojiSearchDragging
        val pressedIdx = emojiSearchPressedResultIdx
        emojiSearchDragging = false
        emojiSearchPressedResultIdx = -1

        if (!wasDragging && pressedIdx in emojiSearchResults.indices) {
            val emoji = emojiSearchResults[pressedIdx]
            triggerHaptic()
            playKeySound(KeyModel.TYPE_NORMAL)
            val hm = historyManager ?: EmojiHistoryManager(context).also { historyManager = it }
            hm.addRecent(emoji)
            actionListener?.onEmoji(emoji)
        }
    }

    // --- POINTER SLOT HELPERS ---

    private fun findSlotByPointerId(pointerId: Int): Int {
        for (i in 0 until MAX_POINTERS) {
            if (pointerIds[i] == pointerId) return i
        }
        return -1
    }

    private fun findFreeSlot(): Int {
        for (i in 0 until MAX_POINTERS) {
            if (pointerIds[i] == -1) return i
        }
        return -1
    }

    private fun hasAnyActiveKeyPointer(): Boolean {
        for (i in 0 until MAX_POINTERS) {
            if (pointerIds[i] != -1) return true
        }
        return false
    }

    private fun isKeyHeldByAnySlot(key: KeyModel): Boolean {
        for (i in 0 until MAX_POINTERS) {
            if (pointerIds[i] != -1 && pointerKeys[i] === key) return true
        }
        return false
    }

    private fun releaseKeyVisual(key: KeyModel, excludeSlot: Int) {
        for (i in 0 until MAX_POINTERS) {
            if (i != excludeSlot && pointerIds[i] != -1 && pointerKeys[i] === key) {
                return
            }
        }
        val wasPressed = key.isPressed
        key.isPressed = false
        if (wasPressed) {
            recordKeyReleaseAnim(key)
        }
    }

    private fun findLatestPressedNormalKey(): KeyModel? {
        for (i in MAX_POINTERS - 1 downTo 0) {
            val k = pointerKeys[i]
            if (pointerIds[i] != -1 && k != null && k.isPressed && k.type == KeyModel.TYPE_NORMAL) {
                return k
            }
        }
        return null
    }

    private fun cleanupAnyStrayPointers() {
        var hadAny = false
        for (i in 0 until MAX_POINTERS) {
            if (pointerIds[i] != -1) {
                pointerKeys[i]?.isPressed = false
                pointerKeys[i] = null
                pointerIds[i] = -1
                pointerLongPressConsumed[i] = false
                hadAny = true
            }
        }
        if (hadAny || isEnterPopupActive || isAccentPopupActive) {
            cancelTimers()
            activePopupKey = null
            popupLongPressTriggered = false
            isEnterPopupActive = false
            enterPopupSlot = -1
            isAccentPopupActive = false
            accentPopupSlot = -1
            accentPopupCount = 0
            accentPopupDragged = false
            deleteSlot = -1
            deleteSwipeTriggered = false
            spaceSlot = -1
            spaceGlideActive = false
            spaceGlideSelectionMode = false
            shiftHoldSlot = -1
            longPressSlot = -1
        }
    }

    private fun resetAllTouches() {
        cancelTimers()
        for (i in 0 until MAX_POINTERS) {
            pointerKeys[i]?.isPressed = false
            pointerKeys[i] = null
            pointerIds[i] = -1
            pointerLongPressConsumed[i] = false
        }
        activePopupKey = null
        popupLongPressTriggered = false
        isEnterPopupActive = false
        enterPopupSlot = -1
        isAccentPopupActive = false
        accentPopupSlot = -1
        accentPopupCount = 0
        accentPopupDragged = false
        pressedSuggestionIndex = -1
        pressedStripAction = STRIP_ACTION_NONE
        stripPointerId = -1
        oneHandedGutterPointerId = -1
        pressedGutterBtn = 0
        emojiSearchDragging = false
        emojiSearchPressedResultIdx = -1
        emojiSearchClosePressed = false
        deleteSlot = -1
        deleteSwipeTriggered = false
        spaceSlot = -1
        spaceGlideActive = false
        spaceGlideSelectionMode = false
        shiftHoldSlot = -1
        shiftUsedAsChord = false
        longPressSlot = -1
    }

    private fun triggerHaptic() {
        if (!hapticEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val vib = vibrator
            val effect = when (hapticStrength) {
                KeyboardPrefs.HAPTIC_LIGHT -> hapticEffectLight
                KeyboardPrefs.HAPTIC_STRONG -> hapticEffectStrong
                else -> hapticEffectMedium
            }
            if (vib != null && effect != null && vib.hasVibrator()) {
                try {
                    vib.vibrate(effect)
                    return
                } catch (_: Throwable) {
                    // Fallback below
                }
            }
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun playKeySound(keyType: Int) {
        if (!soundEnabled) return
        val fx = when (keyType) {
            KeyModel.TYPE_DELETE -> AudioManager.FX_KEYPRESS_DELETE
            KeyModel.TYPE_SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
            KeyModel.TYPE_ENTER -> AudioManager.FX_KEYPRESS_RETURN
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        try {
            audioManager?.playSoundEffect(fx)
        } catch (_: Throwable) {
            // Ignore audio playback errors
        }
    }

    private fun cancelTimers() {
        mainHandler.removeCallbacks(backspaceRepeatRunnable)
        mainHandler.removeCallbacks(longPressRunnable)
    }

    private fun dispatchKeyAction(key: KeyModel) {
        if (isEmojiSearchMode) {
            when (key.type) {
                KeyModel.TYPE_NORMAL -> {
                    val label = key.label
                    if (label.isNotEmpty()) {
                        handleEmojiSearchChar(label[0])
                    }
                    if (layout.isShifted && !layout.isCapsLock && shiftHoldSlot == -1) {
                        layout.updateShiftState(shifted = false, caps = false)
                    }
                    return
                }
                KeyModel.TYPE_SPACE -> {
                    handleEmojiSearchSpace()
                    return
                }
                KeyModel.TYPE_DELETE -> {
                    return
                }
                KeyModel.TYPE_ENTER -> {
                    exitEmojiSearchMode()
                    return
                }
                KeyModel.TYPE_EMOJI -> {
                    exitEmojiSearchMode(notifyInvalidate = false)
                    setEmojiMode(true)
                    return
                }
            }
        }

        when (key.type) {
            KeyModel.TYPE_NORMAL -> {
                val label = key.label
                if (label.isNotEmpty()) {
                    actionListener?.onChar(label[0])
                }
                // Release one-shot shift unless user is currently holding Shift for chording
                if (layout.isShifted && !layout.isCapsLock && shiftHoldSlot == -1) {
                    layout.updateShiftState(shifted = false, caps = false)
                }
            }

            KeyModel.TYPE_SPACE -> {
                actionListener?.onSpace()
            }

            KeyModel.TYPE_DELETE -> {
                // Already dispatched on ACTION_DOWN / repeat / swipe-word
            }

            KeyModel.TYPE_ENTER -> {
                actionListener?.onEnter()
            }

            KeyModel.TYPE_SHIFT -> {
                when (layout.currentMode) {
                    KeyboardLayout.Mode.SYMBOLS -> {
                        layout.currentMode = KeyboardLayout.Mode.SYMBOLS_SHIFT
                    }
                    KeyboardLayout.Mode.SYMBOLS_SHIFT -> {
                        layout.currentMode = KeyboardLayout.Mode.SYMBOLS
                    }
                    else -> {
                        // Handled in handleAlphaShiftUp()
                    }
                }
            }

            KeyModel.TYPE_SWITCH_SYMBOLS -> {
                resetAllTouches()
                clearKeyAnimations()
                layout.currentMode = KeyboardLayout.Mode.SYMBOLS
                invalidate()
            }

            KeyModel.TYPE_SWITCH_ALPHA -> {
                resetAllTouches()
                clearKeyAnimations()
                layout.currentMode = KeyboardLayout.Mode.ALPHA
                invalidate()
            }

            KeyModel.TYPE_SWITCH_NUMPAD -> {
                exitEmojiSearchMode(notifyInvalidate = false)
                resetAllTouches()
                clearKeyAnimations()
                layout.currentMode = KeyboardLayout.Mode.NUMPAD
                requestLayout()
                invalidate()
            }

            KeyModel.TYPE_EMOJI -> {
                setEmojiMode(true)
            }

            KeyModel.TYPE_SWITCH_LANG -> {
                if (key.label == "⚙") {
                    actionListener?.onOpenSettings()
                } else {
                    actionListener?.onLanguageToggle()
                }
            }
        }
    }

    fun setEmojiMode(enabled: Boolean) {
        exitEmojiSearchMode(notifyInvalidate = false)
        resetAllTouches()
        clearKeyAnimations()
        if (isClipboardOpen) {
            isClipboardOpen = false
            clearClipboardThumbnails()
        }
        isEmojiMode = enabled
        if (enabled) {
            getOrCreateEmojiPanel()
        }
        requestLayout()
        invalidate()
    }

    fun switchToNumpad() {
        exitEmojiSearchMode(notifyInvalidate = false)
        resetAllTouches()
        clearKeyAnimations()
        if (isClipboardOpen) {
            isClipboardOpen = false
            clearClipboardThumbnails()
        }
        layout.currentMode = KeyboardLayout.Mode.NUMPAD
        isEmojiMode = false
        requestLayout()
        invalidate()
    }

    fun switchToAlpha() {
        exitEmojiSearchMode(notifyInvalidate = false)
        resetAllTouches()
        clearKeyAnimations()
        if (isClipboardOpen) {
            isClipboardOpen = false
            clearClipboardThumbnails()
        }
        layout.currentMode = KeyboardLayout.Mode.ALPHA
        isEmojiMode = false
        requestLayout()
        invalidate()
    }
}
