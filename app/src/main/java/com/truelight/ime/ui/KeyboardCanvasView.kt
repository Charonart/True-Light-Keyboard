package com.truelight.ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import com.truelight.ime.clipboard.ClipboardHistoryManager
import com.truelight.ime.config.KeyboardPrefs
import com.truelight.ime.emoji.EmojiCanvasPanel
import com.truelight.ime.emoji.EmojiHistoryManager
import com.truelight.ime.lang.LanguageSpec
import com.truelight.ime.suggest.SuggestionManager
import com.truelight.ime.ui.theme.KeyboardTheme
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
        fun onEnter()
        fun onEmoji(emoji: String)
        fun onLanguageToggle()
        fun onOpenSettings()
        fun onSuggestionSelected(word: String)
        fun onClipSelected(text: String)
    }

    companion object {
        private const val MAX_POINTERS = 4
        private const val STRIP_ACTION_NONE = 0
        private const val STRIP_ACTION_CLIPBOARD = 1
        private const val STRIP_ACTION_SETTINGS = 2

        private const val CLIP_TITLE = "Clipboard"
        private const val CLIP_BACK = "Back"
        private const val CLIP_CLEAR = "Clear"
        private const val CLIP_EMPTY = "Clipboard is empty"
        private const val ELLIPSIS = "…"
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

    private var isClipboardOpen = false
    private var clipScrollY = 0f
    private var clipMaxScrollY = 0f
    private var clipTouchStartY = 0f
    private var clipLastTouchY = 0f
    private var clipDragging = false
    private var clipPressedIndex = -1
    private var clipHeaderBtnPressed = 0 // 1 = Back, 2 = Clear

    var keyboardHeightDp: Int = KeyboardPrefs.DEFAULT_HEIGHT_DP
    var hapticEnabled: Boolean = true
    var longPressSymbolsEnabled: Boolean = true

    private val stripHeightPx: Float = 38f * density
    private val stripSideBtnWidthPx: Float = 44f * density
    private var currentStripHeight: Float = 0f
    private var pressedSuggestionIndex: Int = -1
    private var pressedStripAction: Int = STRIP_ACTION_NONE
    private var stripPointerId: Int = -1

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
    private var spaceLastStepX: Float = 0f

    // Shift tap / double-tap / chording state
    private var lastShiftPressTime = 0L
    private var shiftHoldSlot: Int = -1
    private var shiftWasActiveBeforeHold: Boolean = false
    private var shiftUsedAsChord: Boolean = false

    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            val slot = deleteSlot
            if (slot in 0 until MAX_POINTERS &&
                !deleteSwipeTriggered &&
                pointerKeys[slot]?.type == KeyModel.TYPE_DELETE
            ) {
                actionListener?.onBackspace()
                triggerHaptic()
                mainHandler.postDelayed(this, 50L)
            }
        }
    }

    private val longPressRunnable = object : Runnable {
        override fun run() {
            val slot = longPressSlot
            if (slot !in 0 until MAX_POINTERS) return
            val key = pointerKeys[slot] ?: return

            if (key.type == KeyModel.TYPE_SWITCH_LANG) {
                pointerLongPressConsumed[slot] = true
                longPressSlot = -1
                releaseKeyVisual(key, slot)
                pointerKeys[slot] = null
                triggerHaptic()
                invalidate()
                actionListener?.onOpenSettings()
                return
            }

            if (longPressSymbolsEnabled && key.type == KeyModel.TYPE_NORMAL) {
                val sub = key.subLabel
                if (!sub.isNullOrEmpty() && layout.currentMode == KeyboardLayout.Mode.ALPHA) {
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
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        resetAllTouches()
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
                actionListener?.onBackspace()
            },
            onRequestInvalidate = { postInvalidateOnAnimation() }
        )
        if (width > 0 && height > 0) {
            panel.measure(width, height)
        }
        emojiPanel = panel
        return panel
    }

    fun applyConfig(
        context: Context,
        themeMode: Int,
        customCssEnabled: Boolean,
        customCss: String,
        heightDp: Int,
        showNumberRow: Boolean,
        showSubLabels: Boolean,
        longPressSymbols: Boolean,
        haptic: Boolean,
        languageSpec: LanguageSpec,
        multipleLanguages: Boolean
    ) {
        theme.applyTheme(context, themeMode, customCssEnabled, customCss)
        emojiPanel?.syncThemeColors()
        keyboardHeightDp = heightDp
        hapticEnabled = haptic
        longPressSymbolsEnabled = longPressSymbols
        layout.setDisplayOptions(showNumberRow, showSubLabels)
        layout.setLanguage(languageSpec, multipleLanguages)
        requestLayout()
        if (width > 0 && height > 0) {
            remeasureInternal(width, height)
        }
        invalidate()
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

    fun shouldShowSuggestionStrip(): Boolean {
        if (isEmojiMode || isClipboardOpen) return false
        if (layout.currentMode == KeyboardLayout.Mode.NUMPAD) return false
        return suggestionManager?.isStripVisible() == true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            resources.displayMetrics.widthPixels
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }

        // Keep identical height across ALPHA, SYMBOLS, SYMBOLS_SHIFT, Emoji & Clipboard (no vertical jumping)
        val extraNumRowDp = if (layout.showNumberRow && layout.currentMode != KeyboardLayout.Mode.NUMPAD) 36 else 0
        val showStripSpace = layout.currentMode != KeyboardLayout.Mode.NUMPAD &&
            (suggestionManager?.isStripVisible() == true)
        val stripDp = if (showStripSpace) 38 else 0
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
        layout.measure(w, currentStripHeight, keyAreaH)
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

        // 3. Draw Keys with precision tactile lip & subtle hairline border
        val keys = layout.getActiveKeys()
        val radius = theme.keyCornerRadius
        val hasShadow = theme.keyShadowHeight > 0f && (theme.colorKeyShadow ushr 24) > 0
        val hasBorder = theme.keyBorderWidth > 0f && (theme.colorKeyBorder ushr 24) > 0

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
        }

        // 4. Draw Key Preview Popup above latest pressed normal key (zero-allocation)
        val activeKey = activePopupKey
        if (activeKey != null && activeKey.isPressed && activeKey.type == KeyModel.TYPE_NORMAL) {
            drawKeyPreviewPopup(canvas, activeKey, radius)
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
            }
            KeyModel.TYPE_EMOJI -> {
                drawEmojiSmileyIcon(canvas, b)
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
                    canvas.drawLine(leftX + chev, cy - chev, leftX, cy, theme.iconSecondaryPaint)
                    canvas.drawLine(leftX, cy, leftX + chev, cy + chev, theme.iconSecondaryPaint)
                    canvas.drawLine(rightX - chev, cy - chev, rightX, cy, theme.iconSecondaryPaint)
                    canvas.drawLine(rightX, cy, rightX - chev, cy + chev, theme.iconSecondaryPaint)
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
                // Crisp Return bent-arrow
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
        }
    }

    private fun drawEmojiSmileyIcon(canvas: Canvas, b: RectF) {
        val cx = b.centerX()
        val cy = b.centerY()
        val r = minOf(b.width(), b.height()) * 0.25f

        canvas.drawCircle(cx, cy, r, theme.iconPaint)
        val eyeOffsetX = r * 0.36f
        val eyeOffsetY = r * 0.24f
        val eyeR = (r * 0.13f).coerceAtLeast(1.3f * density)
        canvas.drawCircle(cx - eyeOffsetX, cy - eyeOffsetY, eyeR, theme.iconFillPaint)
        canvas.drawCircle(cx + eyeOffsetX, cy - eyeOffsetY, eyeR, theme.iconFillPaint)

        val arcR = r * 0.54f
        theme.reusableRectF.set(cx - arcR, cy - arcR * 0.72f, cx + arcR, cy + arcR * 1.02f)
        canvas.drawArc(theme.reusableRectF, 22f, 136f, false, theme.iconPaint)
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

    private fun drawSuggestionStrip(canvas: Canvas) {
        val w = width.toFloat()
        val h = currentStripHeight
        canvas.drawRect(0f, 0f, w, h, theme.stripBgPaint)
        canvas.drawRect(0f, h - density, w, h, theme.stripDividerPaint)

        val sm = suggestionManager ?: return
        val count = sm.activeSlotCount
        if (count <= 0) {
            // Idle minimal utility strip: Clipboard icon (left if clips exist), Language name (center), Settings gear (right)
            val clips = clipboardManager?.getClips()
            val hasClips = clips != null && clips.isNotEmpty()
            if (hasClips) {
                if (pressedStripAction == STRIP_ACTION_CLIPBOARD) {
                    canvas.drawRect(0f, 0f, stripSideBtnWidthPx, h - density, theme.keyPressedPaint)
                }
                drawClipboardIcon(canvas, stripSideBtnWidthPx * 0.5f, h * 0.5f, 9f * density, theme.iconSecondaryPaint)
            }

            // Center active language indicator
            val baseline = h * 0.5f - (theme.textSecondaryPaint.descent() + theme.textSecondaryPaint.ascent()) * 0.5f
            canvas.drawText(layout.activeLanguage.nativeName, w * 0.5f, baseline, theme.textSecondaryPaint)

            // Right Settings gear button
            if (pressedStripAction == STRIP_ACTION_SETTINGS) {
                canvas.drawRect(w - stripSideBtnWidthPx, 0f, w, h - density, theme.keyPressedPaint)
            }
            drawGearIcon(canvas, w - stripSideBtnWidthPx * 0.5f, h * 0.5f, 8.5f * density, theme.iconSecondaryPaint)
            return
        }

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

    // --- INLINE CANVAS CLIPBOARD PICKER OVERLAY (ZERO EXTRA VIEWS, ZERO ALLOCATIONS) ---

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

        val clips = clipboardManager?.getClips()
        if (clips == null || clips.isEmpty()) {
            val emptyY = (headerH + h) * 0.5f - (theme.textSpacePaint.descent() + theme.textSpacePaint.ascent()) * 0.5f
            canvas.drawText(CLIP_EMPTY, w * 0.5f, emptyY, theme.textSpacePaint)
            clipMaxScrollY = 0f
            return
        }

        val itemH = 44f * density
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
        val textPad = 12f * density
        val maxTextW = (w - padH * 2f - textPad * 2f).coerceAtLeast(24f * density)
        val ellipsisW = theme.clipTextPaint.measureText(ELLIPSIS)

        for (i in clips.indices) {
            val top = headerH + itemGap + i * stride - clipScrollY
            val bottom = top + itemH
            if (bottom < headerH || top > h) continue

            theme.reusableRectF.set(padH, top, w - padH, bottom)
            val cardPaint = if (i == clipPressedIndex) theme.keyPressedPaint else theme.keyBgPaint
            canvas.drawRoundRect(theme.reusableRectF, radius, radius, cardPaint)
            if (hasBorder) {
                canvas.drawRoundRect(theme.reusableRectF, radius, radius, theme.keyBorderPaint)
            }

            val clip = clips[i]
            // Clamp to first line cleanly without allocating new Strings
            var lineEnd = clip.indexOf('\n')
            val hasMoreLines = lineEnd >= 0
            if (lineEnd < 0) lineEnd = clip.length

            val baseline = theme.reusableRectF.centerY() -
                (theme.clipTextPaint.descent() + theme.clipTextPaint.ascent()) * 0.5f
            val textX = theme.reusableRectF.left + textPad

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

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clipTouchStartY = y
                clipLastTouchY = y
                clipDragging = false
                clipPressedIndex = -1
                clipHeaderBtnPressed = 0

                if (y < headerH) {
                    if (x < btnW) {
                        clipHeaderBtnPressed = 1
                    } else if (x > w - btnW) {
                        clipHeaderBtnPressed = 2
                    }
                } else {
                    clipPressedIndex = findClipIndexAt(y, headerH)
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
                    clipDragging = true
                    clipPressedIndex = -1
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
                if (clipHeaderBtnPressed == 1 && y < headerH && x < btnW) {
                    triggerHaptic()
                    closeClipboardOverlay()
                } else if (clipHeaderBtnPressed == 2 && y < headerH && x > w - btnW) {
                    triggerHaptic()
                    clipboardManager?.clear()
                    closeClipboardOverlay()
                } else if (!clipDragging && clipPressedIndex >= 0) {
                    val clips = clipboardManager?.getClips()
                    if (clips != null && clipPressedIndex in clips.indices) {
                        val selected = clips[clipPressedIndex]
                        triggerHaptic()
                        closeClipboardOverlay()
                        actionListener?.onClipSelected(selected)
                    } else {
                        closeClipboardOverlay()
                    }
                }
                clipHeaderBtnPressed = 0
                clipPressedIndex = -1
                clipDragging = false
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                clipHeaderBtnPressed = 0
                clipPressedIndex = -1
                clipDragging = false
                invalidate()
                return true
            }
        }
        return true
    }

    private fun findClipIndexAt(y: Float, headerH: Float): Int {
        val clips = clipboardManager?.getClips() ?: return -1
        if (clips.isEmpty()) return -1
        val itemH = 44f * density
        val itemGap = 6f * density
        val stride = itemH + itemGap
        val relY = y - headerH - itemGap + clipScrollY
        if (relY < 0f) return -1
        val idx = (relY / stride).toInt()
        val inItemY = relY - idx * stride
        return if (idx in clips.indices && inItemY <= itemH) idx else -1
    }

    private fun openClipboardOverlay() {
        resetAllTouches()
        isClipboardOpen = true
        clipScrollY = 0f
        clipPressedIndex = -1
        clipHeaderBtnPressed = 0
        invalidate()
    }

    private fun closeClipboardOverlay() {
        isClipboardOpen = false
        clipPressedIndex = -1
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
                    handleStripDownOrMove(px, py)
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
                        if (sy > currentStripHeight + touchSlop) {
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

                handlePointersMove(event)
                return true
            }

            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP -> {
                val actionIndex = event.actionIndex
                val pointerId = event.getPointerId(actionIndex)

                if (pointerId == stripPointerId) {
                    handleStripUp()
                    stripPointerId = -1
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

        when (key.type) {
            KeyModel.TYPE_DELETE -> {
                deleteSlot = slot
                deleteSwipeTriggered = false
                actionListener?.onBackspace()
                mainHandler.removeCallbacks(backspaceRepeatRunnable)
                mainHandler.postDelayed(backspaceRepeatRunnable, 350L)
            }

            KeyModel.TYPE_SPACE -> {
                spaceSlot = slot
                spaceGlideActive = false
                spaceLastStepX = px
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

            KeyModel.TYPE_SWITCH_LANG -> {
                mainHandler.removeCallbacks(longPressRunnable)
                longPressSlot = slot
                mainHandler.postDelayed(longPressRunnable, 360L)
            }

            KeyModel.TYPE_NORMAL -> {
                if (longPressSymbolsEnabled &&
                    !key.subLabel.isNullOrEmpty() &&
                    layout.currentMode == KeyboardLayout.Mode.ALPHA
                ) {
                    mainHandler.removeCallbacks(longPressRunnable)
                    longPressSlot = slot
                    mainHandler.postDelayed(longPressRunnable, 360L)
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

            // 1. Spacebar Horizontal Cursor Glide
            if (slot == spaceSlot && key.type == KeyModel.TYPE_SPACE) {
                if (!spaceGlideActive && abs(px - pointerDownX[slot]) > touchSlop) {
                    spaceGlideActive = true
                    spaceLastStepX = px
                    needsInvalidate = true
                }
                if (spaceGlideActive) {
                    val stepPx = 18f * density
                    val dx = px - spaceLastStepX
                    if (abs(dx) >= stepPx) {
                        val steps = (dx / stepPx).toInt()
                        spaceLastStepX += steps * stepPx
                        actionListener?.onMoveCursor(steps)
                        triggerHaptic()
                    }
                }
                continue
            }

            // 2. Backspace Left-Swipe to Delete Whole Word
            if (slot == deleteSlot && key.type == KeyModel.TYPE_DELETE) {
                if (!deleteSwipeTriggered && (pointerDownX[slot] - px) > 36f * density) {
                    deleteSwipeTriggered = true
                    mainHandler.removeCallbacks(backspaceRepeatRunnable)
                    triggerHaptic()
                    actionListener?.onDeleteWord()
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
        if (spaceSlot == slot) {
            spaceSlot = -1
            spaceGlideActive = false
        }

        val wasShiftHold = (shiftHoldSlot == slot)
        if (wasShiftHold) {
            shiftHoldSlot = -1
        }

        if (key != null) {
            if (!isKeyHeldByAnySlot(key)) {
                key.isPressed = false
            }
            if (activePopupKey === key) {
                activePopupKey = findLatestPressedNormalKey()
                popupLongPressTriggered = false
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
        if (y < currentStripHeight && width > 0) {
            val hasClips = clipboardManager?.getClips()?.isNotEmpty() == true
            pressedStripAction = when {
                hasClips && x <= stripSideBtnWidthPx -> STRIP_ACTION_CLIPBOARD
                x >= width - stripSideBtnWidthPx -> STRIP_ACTION_SETTINGS
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
                openClipboardOverlay()
            }
            STRIP_ACTION_SETTINGS -> {
                triggerHaptic()
                actionListener?.onOpenSettings()
            }
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
        key.isPressed = false
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
        if (hadAny) {
            cancelTimers()
            activePopupKey = null
            popupLongPressTriggered = false
            deleteSlot = -1
            deleteSwipeTriggered = false
            spaceSlot = -1
            spaceGlideActive = false
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
        pressedSuggestionIndex = -1
        pressedStripAction = STRIP_ACTION_NONE
        stripPointerId = -1
        deleteSlot = -1
        deleteSwipeTriggered = false
        spaceSlot = -1
        spaceGlideActive = false
        shiftHoldSlot = -1
        shiftUsedAsChord = false
        longPressSlot = -1
    }

    private fun triggerHaptic() {
        if (hapticEnabled) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun cancelTimers() {
        mainHandler.removeCallbacks(backspaceRepeatRunnable)
        mainHandler.removeCallbacks(longPressRunnable)
    }

    private fun dispatchKeyAction(key: KeyModel) {
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
                layout.currentMode = KeyboardLayout.Mode.SYMBOLS
                invalidate()
            }

            KeyModel.TYPE_SWITCH_ALPHA -> {
                resetAllTouches()
                layout.currentMode = KeyboardLayout.Mode.ALPHA
                invalidate()
            }

            KeyModel.TYPE_SWITCH_NUMPAD -> {
                resetAllTouches()
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
        resetAllTouches()
        isClipboardOpen = false
        isEmojiMode = enabled
        if (enabled) {
            getOrCreateEmojiPanel()
        }
        requestLayout()
        invalidate()
    }

    fun switchToNumpad() {
        resetAllTouches()
        isClipboardOpen = false
        layout.currentMode = KeyboardLayout.Mode.NUMPAD
        isEmojiMode = false
        requestLayout()
        invalidate()
    }

    fun switchToAlpha() {
        resetAllTouches()
        isClipboardOpen = false
        layout.currentMode = KeyboardLayout.Mode.ALPHA
        isEmojiMode = false
        requestLayout()
        invalidate()
    }
}
