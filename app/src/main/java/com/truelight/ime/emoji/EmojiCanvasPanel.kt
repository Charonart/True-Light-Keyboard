package com.truelight.ime.emoji

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.truelight.ime.ui.theme.KeyboardTheme
import java.io.File
import kotlin.math.abs

/**
 * High-performance, zero-allocation native 12-Category Emoji, Kaomoji & Math/Tech Canvas Renderer.
 * Directly utilizes system font /system/fonts/NotoColorEmoji.ttf for emoji glyphs and crisp native
 * vector/sans typography for UI chrome (vector search glass, vector recent clock, vector backspace),
 * Kaomoji (3 cols, auto-fit), and Math & Tech Symbols (6 cols).
 * Supports airy 7-column emoji grid, M3 pill tab indicator, subtle scrollbar, long-press skin-tone
 * floating pill, horizontal scrollable tab bar, vertical fling scrolling (OverScroller), and hold-to-repeat backspace.
 */
class EmojiCanvasPanel(
    context: Context,
    private val theme: KeyboardTheme,
    private val historyManager: EmojiHistoryManager,
    private val touchSlop: Float = ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
    private val onEmojiSelected: (String) -> Unit,
    private val onBackToKeyboard: () -> Unit,
    private val onDeleteClicked: () -> Unit,
    private val onSearchClicked: () -> Unit = {},
    private val onRequestInvalidate: () -> Unit = {},
    private val onTriggerHaptic: () -> Unit = {}
) {

    companion object {
        private const val EMPTY_RECENTS_TEXT = "No recent items yet"
        private const val BACK_LABEL = "ABC"
        private const val COLS_EMOJI = 7
        private const val COLS_MATH = 6
        private const val COLS_KAOMOJI = 3
    }

    var longPressDelayMs: Long = 340L

    private var panelWidth = 0
    private var panelHeight = 0

    private val scroller = OverScroller(context)
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private val mainHandler = Handler(Looper.getMainLooper())

    // System color emoji font
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 30f * theme.density
        val fontFile = File("/system/fonts/NotoColorEmoji.ttf")
        if (fontFile.exists() && fontFile.canRead()) {
            try {
                typeface = Typeface.createFromFile(fontFile)
            } catch (_: Exception) {
                typeface = Typeface.DEFAULT
            }
        } else {
            typeface = Typeface.DEFAULT
        }
    }

    private val tabBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.colorStripBg
        style = Paint.Style.FILL
    }

    private val tabSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.colorAccent
        style = Paint.Style.FILL
    }

    private val tabPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.colorAccent
        alpha = 56
        style = Paint.Style.FILL
    }

    private val tabTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 18f * theme.density
        typeface = emojiPaint.typeface
    }

    private val tabSymbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 14f * theme.density
        typeface = Typeface.DEFAULT_BOLD
        color = theme.colorTextSecondary
    }

    private val vectorStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.85f * theme.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = theme.colorTextSecondary
    }

    private val scrollbarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = theme.colorTextSecondary
        alpha = 90
    }

    private val skinToneHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = theme.colorAccent
        alpha = 68
    }

    private val skinToneBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * theme.density
        color = theme.colorAccent
    }

    private val kaomojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 13.5f * theme.density
        typeface = Typeface.DEFAULT
        color = theme.colorTextPrimary
    }

    private val mathSymbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 20f * theme.density
        typeface = Typeface.DEFAULT
        color = theme.colorTextPrimary
    }

    private val actionKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.colorBgKey
        style = Paint.Style.FILL
    }

    // Geometry caches (preallocated for zero-allocation draw)
    private val bounds = RectF()
    private val searchBtnBounds = RectF()
    private val bottomBarBounds = RectF()
    private val backKeyBounds = RectF()
    private val delKeyBounds = RectF()
    private val skinTonePopupRect = RectF()

    private var tabHeight = 0f
    private var tabsStartX = 0f
    private var tabsViewportW = 0f
    private var tabWidth = 0f
    private var tabScrollX = 0f
    private var maxTabScrollX = 0f

    private var selectedCategory = EmojiData.CAT_SMILEYS
    private var scrollOffsetY = 0f
    private var maxScrollY = 0f

    private var cellHeight = 0f
    private var kaomojiBaseTextSize = 13.5f * theme.density

    // Touch & fling tracking (zero-allocation velocity estimation)
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastMoveTimeMs = 0L
    private var velocityYPxPerSec = 0f
    private var isDragging = false
    private var isSearchPressed = false
    private var isTabTouch = false
    private var isTabDragging = false
    private var isDeletePressed = false
    private var isBackPressed = false
    private var pressedCellIndex = -1

    // Skin-tone floating pill state (preallocated -> zero allocs in draw)
    private var isSkinTonePopupActive = false
    private var skinToneVariants: Array<String>? = null
    private var skinToneSelectedIndex = 0
    private var skinToneCellWidth = 0f
    private var skinToneAnchorX = 0f
    private var skinToneDragged = false
    private var pendingLongPressIndex = -1

    private val deleteRepeatRunnable = object : Runnable {
        override fun run() {
            if (isDeletePressed) {
                onDeleteClicked()
                mainHandler.postDelayed(this, 55L)
            }
        }
    }

    private val skinToneLongPressRunnable = Runnable {
        val idx = pendingLongPressIndex
        if (idx < 0 || isDragging || isTabTouch || isSearchPressed || isDeletePressed || isBackPressed) {
            return@Runnable
        }
        val item = getItemAt(idx) ?: return@Runnable
        val variants = EmojiSearchIndex.getSkinToneVariants(item)
        if (variants == null || variants.isEmpty()) return@Runnable

        activateSkinTonePopup(idx, variants, lastTouchX)
        onTriggerHaptic()
        onRequestInvalidate()
    }

    fun syncThemeColors() {
        tabBgPaint.color = theme.colorStripBg
        tabSelectedPaint.color = theme.colorAccent
        tabPillPaint.color = theme.colorAccent
        tabPillPaint.alpha = 56
        actionKeyPaint.color = theme.colorBgKey
        tabSymbolPaint.color = theme.colorTextSecondary
        vectorStrokePaint.color = theme.colorTextSecondary
        scrollbarPaint.color = theme.colorTextSecondary
        scrollbarPaint.alpha = 90
        skinToneHighlightPaint.color = theme.colorAccent
        skinToneHighlightPaint.alpha = 68
        skinToneBorderPaint.color = theme.colorAccent
        kaomojiPaint.color = theme.colorTextPrimary
        mathSymbolPaint.color = theme.colorTextPrimary
    }

    fun detach() {
        mainHandler.removeCallbacks(deleteRepeatRunnable)
        mainHandler.removeCallbacks(skinToneLongPressRunnable)
        scroller.forceFinished(true)
        isDeletePressed = false
        isBackPressed = false
        isSearchPressed = false
        isDragging = false
        isTabTouch = false
        isTabDragging = false
        pressedCellIndex = -1
        clearSkinTonePopup()
    }

    private fun clearSkinTonePopup() {
        isSkinTonePopupActive = false
        skinToneVariants = null
        skinToneSelectedIndex = 0
        skinToneDragged = false
        pendingLongPressIndex = -1
    }

    fun measure(width: Int, height: Int) {
        this.panelWidth = width
        this.panelHeight = height
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())

        tabHeight = (height * 0.155f).coerceIn(38f * theme.density, 46f * theme.density)
        val bottomHeight = (height * 0.16f).coerceIn(38f * theme.density, 50f * theme.density)
        val gridHeight = (height - tabHeight - bottomHeight).coerceAtLeast(1f)

        // Pinned pure-Canvas vector Search button on far left of top category bar
        val searchW = (46f * theme.density).coerceAtMost(width * 0.18f)
        searchBtnBounds.set(0f, 0f, searchW, tabHeight)
        tabsStartX = searchW
        tabsViewportW = (width.toFloat() - tabsStartX).coerceAtLeast(1f)

        // Measure 12 scrollable category tabs
        val minTabW = 42f * theme.density
        tabWidth = maxOf(tabsViewportW / EmojiData.CATEGORY_COUNT, minTabW)
        maxTabScrollX = (tabWidth * EmojiData.CATEGORY_COUNT - tabsViewportW).coerceAtLeast(0f)
        tabScrollX = tabScrollX.coerceIn(0f, maxTabScrollX)

        tabTextPaint.textSize = (tabHeight * 0.44f).coerceIn(15f * theme.density, 20f * theme.density)
        tabSymbolPaint.textSize = (tabHeight * 0.33f).coerceIn(11.5f * theme.density, 15.5f * theme.density)

        // Measure bottom bar
        bottomBarBounds.set(0f, height - bottomHeight, width.toFloat(), height.toFloat())
        val actionW = (width * 0.22f).coerceAtLeast(64f * theme.density)
        backKeyBounds.set(
            theme.keyMarginH * 2f,
            height - bottomHeight + theme.keyMarginV,
            actionW,
            height - theme.keyMarginV
        )
        delKeyBounds.set(
            width - actionW,
            height - bottomHeight + theme.keyMarginV,
            width - theme.keyMarginH * 2f,
            height - theme.keyMarginV
        )

        // Measure airy grid cell heights & font sizes
        cellHeight = (gridHeight / 3.45f).coerceAtLeast(42f * theme.density)
        emojiPaint.textSize = minOf(cellHeight * 0.56f, 30f * theme.density)
        mathSymbolPaint.textSize = (cellHeight * 0.46f).coerceIn(16f * theme.density, 24f * theme.density)
        kaomojiBaseTextSize = (cellHeight * 0.29f).coerceIn(11f * theme.density, 15f * theme.density)
        kaomojiPaint.textSize = kaomojiBaseTextSize

        recalcMaxScroll()
    }

    private fun getActiveColumns(): Int {
        return when (selectedCategory) {
            EmojiData.CAT_KAOMOJI -> COLS_KAOMOJI
            EmojiData.CAT_MATH_TECH -> COLS_MATH
            else -> COLS_EMOJI
        }
    }

    private fun getActiveCellWidth(): Float {
        val cols = getActiveColumns()
        return if (cols > 0) panelWidth.toFloat() / cols else panelWidth.toFloat()
    }

    private fun recalcMaxScroll() {
        val count = getActiveCategoryCount()
        val cols = getActiveColumns()
        val totalRows = (count + cols - 1) / cols
        val visibleGridHeight = (bottomBarBounds.top - tabHeight).coerceAtLeast(1f)
        val contentHeight = totalRows * cellHeight
        maxScrollY = (contentHeight - visibleGridHeight).coerceAtLeast(0f)
        scrollOffsetY = scrollOffsetY.coerceIn(0f, maxScrollY)
    }

    private fun getActiveCategoryCount(): Int {
        return if (selectedCategory == EmojiData.CAT_RECENTS) {
            historyManager.getRecentList().size
        } else {
            EmojiData.ALL_CATEGORIES[selectedCategory].size
        }
    }

    private fun getItemAt(index: Int): String? {
        return if (selectedCategory == EmojiData.CAT_RECENTS) {
            val list = historyManager.getRecentList()
            if (index in list.indices) list[index] else null
        } else {
            val arr = EmojiData.ALL_CATEGORIES[selectedCategory]
            if (index in arr.indices) arr[index] else null
        }
    }

    private fun activateSkinTonePopup(gridIndex: Int, variants: Array<String>, anchorX: Float) {
        val cols = getActiveColumns()
        val cellW = getActiveCellWidth()
        if (cols <= 0 || cellW <= 0f || cellHeight <= 0f) return

        val row = gridIndex / cols
        val col = gridIndex % cols
        val cellLeft = col * cellW
        val cellTop = tabHeight + row * cellHeight - scrollOffsetY
        val cellCx = cellLeft + cellW * 0.5f

        val count = variants.size
        val maxPillW = (panelWidth - 12f * theme.density).coerceAtLeast(48f * theme.density)
        val rawCellW = maxOf(cellW * 0.96f, 40f * theme.density)
        val popCellW = minOf(rawCellW, maxPillW / count.toFloat())
        val popW = popCellW * count.toFloat()
        val popH = maxOf(cellHeight * 0.96f, 46f * theme.density)

        val minLeft = 6f * theme.density
        val maxLeft = (panelWidth - popW - 6f * theme.density).coerceAtLeast(minLeft)
        val left = (cellCx - popW * 0.5f).coerceIn(minLeft, maxLeft)
        val desiredBottom = cellTop - 6f * theme.density
        val top = if (desiredBottom - popH >= 4f * theme.density) {
            desiredBottom - popH
        } else {
            // If top row leaves little room above, place cleanly just below the cell
            (cellTop + cellHeight + 6f * theme.density).coerceAtMost(panelHeight - popH - 4f * theme.density)
        }

        skinTonePopupRect.set(left, top, left + popW, top + popH)
        skinToneVariants = variants
        skinToneCellWidth = popCellW
        skinToneSelectedIndex = 0
        skinToneAnchorX = anchorX
        skinToneDragged = false
        isSkinTonePopupActive = true
    }

    fun computeScrollOffset(): Boolean {
        if (scroller.computeScrollOffset()) {
            scrollOffsetY = scroller.currY.toFloat().coerceIn(0f, maxScrollY)
            return true
        }
        return false
    }

    fun draw(canvas: Canvas) {
        val d = theme.density
        val hasShadow = theme.keyShadowHeight > 0f && (theme.colorKeyShadow ushr 24) > 0
        val hasBorder = theme.keyBorderWidth > 0f && (theme.colorKeyBorder ushr 24) > 0

        // 1. Draw Panel Background
        canvas.drawRect(bounds, theme.bgPaint)

        // 2. Draw Top Category Bar (Pinned Pure-Canvas Vector Search + 12 Scrollable Tabs)
        canvas.drawRect(0f, 0f, panelWidth.toFloat(), tabHeight, tabBgPaint)
        canvas.drawRect(0f, tabHeight - d, panelWidth.toFloat(), tabHeight, theme.stripDividerPaint)

        // 2a. Pinned Vector Search Button (far left)
        if (isSearchPressed) {
            val inset = 5f * d
            theme.reusableRectF.set(
                searchBtnBounds.left + inset,
                searchBtnBounds.top + inset,
                searchBtnBounds.right - inset,
                searchBtnBounds.bottom - inset
            )
            val pillR = theme.reusableRectF.height() * 0.5f
            canvas.drawRoundRect(theme.reusableRectF, pillR, pillR, theme.keyPressedPaint)
        }
        drawSearchGlassVector(
            canvas,
            searchBtnBounds.centerX(),
            searchBtnBounds.centerY(),
            6.2f * d,
            if (isSearchPressed) theme.colorAccent else theme.colorTextSecondary
        )
        // Hairline vertical divider separating pinned search from scrollable category tabs
        val divPadV = 8f * d
        canvas.drawRect(
            tabsStartX - d * 0.5f,
            divPadV,
            tabsStartX + d * 0.5f,
            tabHeight - divPadV,
            theme.stripDividerPaint
        )

        // 2b. Scrollable 12-Category Tabs
        canvas.save()
        canvas.clipRect(tabsStartX, 0f, panelWidth.toFloat(), tabHeight)
        val pillPadH = 4f * d
        val pillPadV = 5.5f * d
        val indicatorH = 2.4f * d

        for (i in 0 until EmojiData.CATEGORY_COUNT) {
            val left = tabsStartX + i * tabWidth - tabScrollX
            val right = left + tabWidth
            if (right < tabsStartX || left > panelWidth.toFloat()) continue

            val cx = (left + right) * 0.5f
            val cy = tabHeight * 0.5f
            val isSelected = (i == selectedCategory)

            if (isSelected) {
                // Sleek Material 3 tinted rounded pill backdrop
                theme.reusableRectF.set(
                    left + pillPadH,
                    pillPadV,
                    right - pillPadH,
                    tabHeight - pillPadV
                )
                val pillR = theme.reusableRectF.height() * 0.5f
                canvas.drawRoundRect(theme.reusableRectF, pillR, pillR, tabPillPaint)

                // Subtle bottom accent bar
                val barHalfW = (tabWidth - pillPadH * 2f) * 0.28f
                theme.reusableRectF.set(
                    cx - barHalfW,
                    tabHeight - indicatorH - d * 0.5f,
                    cx + barHalfW,
                    tabHeight - d * 0.5f
                )
                canvas.drawRoundRect(theme.reusableRectF, indicatorH, indicatorH, tabSelectedPaint)
            }

            if (i == EmojiData.CAT_RECENTS) {
                // Pure-Canvas Vector Clock icon (zero emoji chrome!)
                drawClockVector(
                    canvas,
                    cx,
                    cy,
                    7.2f * d,
                    if (isSelected) theme.colorAccent else theme.colorTextSecondary
                )
            } else if (i >= EmojiData.CAT_KAOMOJI) {
                tabSymbolPaint.color = if (isSelected) theme.colorAccent else theme.colorTextSecondary
                val textY = cy - (tabSymbolPaint.descent() + tabSymbolPaint.ascent()) * 0.5f
                canvas.drawText(EmojiData.CATEGORY_ICONS[i], cx, textY, tabSymbolPaint)
            } else {
                val textY = cy - (tabTextPaint.descent() + tabTextPaint.ascent()) * 0.5f
                canvas.drawText(EmojiData.CATEGORY_ICONS[i], cx, textY, tabTextPaint)
            }
        }
        canvas.restore()

        // 3. Draw Adaptive Airy Grid with clipping (zero allocations)
        val gridTop = tabHeight
        val gridBottom = bottomBarBounds.top
        canvas.save()
        canvas.clipRect(0f, gridTop, panelWidth.toFloat(), gridBottom)

        val totalCount = getActiveCategoryCount()
        if (totalCount == 0 && selectedCategory == EmojiData.CAT_RECENTS) {
            val emptyY = (gridTop + gridBottom) * 0.5f -
                (theme.textSpacePaint.descent() + theme.textSpacePaint.ascent()) * 0.5f
            canvas.drawText(EMPTY_RECENTS_TEXT, panelWidth * 0.5f, emptyY, theme.textSpacePaint)
        } else if (cellHeight > 0f) {
            val cols = getActiveColumns()
            val cellW = getActiveCellWidth()
            val totalRows = (totalCount + cols - 1) / cols
            val startRow = (scrollOffsetY / cellHeight).toInt().coerceAtLeast(0)
            val endRow = ((scrollOffsetY + (gridBottom - gridTop)) / cellHeight).toInt() + 1
            val isKaomojiCat = (selectedCategory == EmojiData.CAT_KAOMOJI)
            val isMathCat = (selectedCategory == EmojiData.CAT_MATH_TECH)
            val isRecentsCat = (selectedCategory == EmojiData.CAT_RECENTS)
            val padH = if (isKaomojiCat) 3.5f * d else 3.5f * d
            val padV = if (isKaomojiCat) 3.5f * d else 3.5f * d
            val radius = if (isKaomojiCat) theme.keyCornerRadius else maxOf(theme.keyCornerRadius, 8f * d)

            for (row in startRow..endRow) {
                if (row >= totalRows) break
                val y = gridTop + row * cellHeight - scrollOffsetY
                for (col in 0 until cols) {
                    val index = row * cols + col
                    if (index >= totalCount) break
                    val item = getItemAt(index) ?: continue
                    val cellLeft = col * cellW
                    val cx = cellLeft + cellW * 0.5f
                    val cy = y + cellHeight * 0.5f

                    if (isKaomojiCat) {
                        theme.reusableRectF.set(
                            cellLeft + padH,
                            y + padV,
                            cellLeft + cellW - padH,
                            y + cellHeight - padV
                        )
                        val cardPaint = if (index == pressedCellIndex) theme.keyPressedPaint else actionKeyPaint
                        canvas.drawRoundRect(theme.reusableRectF, radius, radius, cardPaint)
                        if (hasBorder) {
                            canvas.drawRoundRect(theme.reusableRectF, radius, radius, theme.keyBorderPaint)
                        }
                        drawAutoFitText(canvas, item, cx, cy, cellW - padH * 2f - 8f * d, kaomojiPaint, kaomojiBaseTextSize)
                    } else {
                        if (index == pressedCellIndex) {
                            theme.reusableRectF.set(
                                cellLeft + padH,
                                y + padV,
                                cellLeft + cellW - padH,
                                y + cellHeight - padV
                            )
                            canvas.drawRoundRect(theme.reusableRectF, radius, radius, theme.keyPressedPaint)
                        }

                        if (isMathCat) {
                            val baseline = cy - (mathSymbolPaint.descent() + mathSymbolPaint.ascent()) * 0.5f
                            canvas.drawText(item, cx, baseline, mathSymbolPaint)
                        } else if (isRecentsCat && isLikelyKaomoji(item)) {
                            drawAutoFitText(canvas, item, cx, cy, cellW - 4f * d, kaomojiPaint, kaomojiBaseTextSize)
                        } else if (isRecentsCat && isLikelyMathSymbol(item)) {
                            val baseline = cy - (mathSymbolPaint.descent() + mathSymbolPaint.ascent()) * 0.5f
                            canvas.drawText(item, cx, baseline, mathSymbolPaint)
                        } else {
                            val baseline = cy - (emojiPaint.descent() + emojiPaint.ascent()) * 0.5f
                            canvas.drawText(item, cx, baseline, emojiPaint)
                        }
                    }
                }
            }

            // Subtle vertical scrollbar thumb indicator
            if (maxScrollY > 2f * d) {
                val trackH = (gridBottom - gridTop - 12f * d).coerceAtLeast(16f * d)
                val totalContentH = maxScrollY + (gridBottom - gridTop)
                val thumbH = ((gridBottom - gridTop) / totalContentH * trackH).coerceIn(20f * d, trackH * 0.65f)
                val scrollFrac = (scrollOffsetY / maxScrollY).coerceIn(0f, 1f)
                val thumbTop = gridTop + 6f * d + scrollFrac * (trackH - thumbH)
                val barW = 2.6f * d
                val barRight = panelWidth - 2.5f * d
                scrollbarPaint.alpha = if (isDragging || !scroller.isFinished) 155 else 72
                theme.reusableRectF.set(barRight - barW, thumbTop, barRight, thumbTop + thumbH)
                canvas.drawRoundRect(theme.reusableRectF, barW * 0.5f, barW * 0.5f, scrollbarPaint)
            }
        }
        canvas.restore()

        // 4. Draw Bottom Control Bar (ABC | Category Title | Backspace)
        canvas.drawRect(bottomBarBounds, tabBgPaint)
        canvas.drawRect(0f, bottomBarBounds.top, panelWidth.toFloat(), bottomBarBounds.top + d, theme.stripDividerPaint)

        // Back to keyboard button
        val backPaint = if (isBackPressed) theme.keyPressedPaint else actionKeyPaint
        canvas.drawRoundRect(backKeyBounds, theme.keyCornerRadius, theme.keyCornerRadius, backPaint)
        if (hasBorder) {
            canvas.drawRoundRect(backKeyBounds, theme.keyCornerRadius, theme.keyCornerRadius, theme.keyBorderPaint)
        }
        val backTextY = backKeyBounds.centerY() - (theme.textSpecialPaint.descent() + theme.textSpecialPaint.ascent()) * 0.5f
        canvas.drawText(BACK_LABEL, backKeyBounds.centerX(), backTextY, theme.textSpecialPaint)

        // Active Category Title label in center of bottom bar
        val categoryTitle = EmojiData.CATEGORY_NAMES[selectedCategory]
        val titleY = bottomBarBounds.centerY() - (theme.textSpacePaint.descent() + theme.textSpacePaint.ascent()) * 0.5f
        canvas.drawText(categoryTitle, panelWidth * 0.5f, titleY, theme.textSpacePaint)

        // Delete button with vector backspace icon
        val delPaint = if (isDeletePressed) theme.keyPressedPaint else actionKeyPaint
        canvas.drawRoundRect(delKeyBounds, theme.keyCornerRadius, theme.keyCornerRadius, delPaint)
        if (hasBorder) {
            canvas.drawRoundRect(delKeyBounds, theme.keyCornerRadius, theme.keyCornerRadius, theme.keyBorderPaint)
        }
        drawBackspaceVector(canvas, delKeyBounds)

        // 5. Draw Long-Press Skin-Tone Floating Pill on top of everything
        if (isSkinTonePopupActive) {
            drawSkinTonePopup(canvas, hasShadow, hasBorder)
        }
    }

    private fun drawSkinTonePopup(canvas: Canvas, hasShadow: Boolean, hasBorder: Boolean) {
        val variants = skinToneVariants ?: return
        if (variants.isEmpty()) return
        val d = theme.density
        val pillRadius = 14f * d

        if (hasShadow) {
            theme.reusableRectF.set(
                skinTonePopupRect.left,
                skinTonePopupRect.top + 2.5f * d,
                skinTonePopupRect.right,
                skinTonePopupRect.bottom + 2.5f * d
            )
            canvas.drawRoundRect(theme.reusableRectF, pillRadius, pillRadius, theme.keyShadowPaint)
        }

        canvas.drawRoundRect(skinTonePopupRect, pillRadius, pillRadius, theme.popupBgPaint)
        if (hasBorder) {
            canvas.drawRoundRect(skinTonePopupRect, pillRadius, pillRadius, theme.keyBorderPaint)
        }

        val inset = 3.5f * d
        val innerR = (pillRadius - inset * 0.7f).coerceAtLeast(6f * d)
        val selIdx = skinToneSelectedIndex.coerceIn(0, variants.lastIndex)
        val selLeft = skinTonePopupRect.left + selIdx * skinToneCellWidth + inset * 0.6f
        val selRight = skinTonePopupRect.left + (selIdx + 1) * skinToneCellWidth - inset * 0.6f
        theme.reusableRectF.set(
            selLeft,
            skinTonePopupRect.top + inset,
            selRight,
            skinTonePopupRect.bottom - inset
        )
        canvas.drawRoundRect(theme.reusableRectF, innerR, innerR, skinToneHighlightPaint)
        canvas.drawRoundRect(theme.reusableRectF, innerR, innerR, skinToneBorderPaint)

        val cy = skinTonePopupRect.centerY()
        val baseline = cy - (emojiPaint.descent() + emojiPaint.ascent()) * 0.5f
        for (i in variants.indices) {
            val cx = skinTonePopupRect.left + skinToneCellWidth * (i + 0.5f)
            canvas.drawText(variants[i], cx, baseline, emojiPaint)
        }
    }

    private fun drawSearchGlassVector(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val prevColor = vectorStrokePaint.color
        vectorStrokePaint.color = color
        val gx = cx - r * 0.22f
        val gy = cy - r * 0.22f
        val glassR = r * 0.76f
        canvas.drawCircle(gx, gy, glassR, vectorStrokePaint)
        canvas.drawLine(
            gx + glassR * 0.72f,
            gy + glassR * 0.72f,
            cx + r * 0.92f,
            cy + r * 0.92f,
            vectorStrokePaint
        )
        vectorStrokePaint.color = prevColor
    }

    private fun drawClockVector(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val prevColor = vectorStrokePaint.color
        vectorStrokePaint.color = color
        canvas.drawCircle(cx, cy, r, vectorStrokePaint)
        // 12 o'clock hand
        canvas.drawLine(cx, cy, cx, cy - r * 0.52f, vectorStrokePaint)
        // 4 o'clock hand
        canvas.drawLine(cx, cy, cx + r * 0.44f, cy + r * 0.24f, vectorStrokePaint)
        vectorStrokePaint.color = prevColor
    }

    private fun drawAutoFitText(
        canvas: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        maxWidth: Float,
        paint: Paint,
        baseSize: Float
    ) {
        paint.textSize = baseSize
        val measured = paint.measureText(text)
        if (measured > maxWidth && maxWidth > 4f) {
            val scale = (maxWidth / measured).coerceIn(0.45f, 1f)
            paint.textSize = baseSize * scale
        }
        val baseline = cy - (paint.descent() + paint.ascent()) * 0.5f
        canvas.drawText(text, cx, baseline, paint)
        paint.textSize = baseSize
    }

    private fun isLikelyKaomoji(s: String): Boolean {
        if (s.length < 3) return false
        val c0 = s[0].code
        return c0 < 0x2000 || c0 == 0x0295 || c0 == 0x0CA0 || c0 == 0x252C || c0 == 0x25BC || c0 == 0x2661 || c0 == 0x266A || c0 == 0x30FD || c0 == 0x30FE
    }

    private fun isLikelyMathSymbol(s: String): Boolean {
        if (s.length != 1) return false
        val c = s[0].code
        return (c in 0x00A0..0x03FF) || (c in 0x2010..0x2606) || (c in 0x2713..0x2717)
    }

    private fun drawBackspaceVector(canvas: Canvas, b: RectF) {
        val cx = b.centerX()
        val cy = b.centerY()
        val s = minOf(b.width(), b.height()) * 0.30f
        val path = theme.reusablePath
        path.reset()
        path.moveTo(cx - s * 1.05f, cy)
        path.lineTo(cx - s * 0.42f, cy - s * 0.62f)
        path.lineTo(cx + s * 0.95f, cy - s * 0.62f)
        path.lineTo(cx + s * 0.95f, cy + s * 0.62f)
        path.lineTo(cx - s * 0.42f, cy + s * 0.62f)
        path.close()
        canvas.drawPath(path, theme.iconPaint)

        val xCx = cx + s * 0.24f
        val arm = s * 0.28f
        canvas.drawLine(xCx - arm, cy - arm, xCx + arm, cy + arm, theme.iconPaint)
        canvas.drawLine(xCx + arm, cy - arm, xCx - arm, cy + arm, theme.iconPaint)
    }

    private fun findGridIndexAt(x: Float, y: Float): Int {
        val gridBottom = bottomBarBounds.top
        if (y !in tabHeight..gridBottom || cellHeight <= 0f) return -1
        val cols = getActiveColumns()
        val cellW = getActiveCellWidth()
        if (cellW <= 0f) return -1
        val gridY = y - tabHeight + scrollOffsetY
        val row = (gridY / cellHeight).toInt()
        val col = (x / cellW).toInt().coerceIn(0, cols - 1)
        val idx = row * cols + col
        return if (idx in 0 until getActiveCategoryCount()) idx else -1
    }

    private fun ensureTabVisible(index: Int) {
        if (maxTabScrollX <= 0f) return
        val tabLeft = index * tabWidth
        val tabRight = tabLeft + tabWidth
        if (tabLeft < tabScrollX) {
            tabScrollX = tabLeft.coerceIn(0f, maxTabScrollX)
        } else if (tabRight > tabScrollX + tabsViewportW) {
            tabScrollX = (tabRight - tabsViewportW).coerceIn(0f, maxTabScrollX)
        }
    }

    fun handleTouchDown(x: Float, y: Float): Boolean {
        scroller.forceFinished(true)
        touchStartX = x
        touchStartY = y
        lastTouchX = x
        lastTouchY = y
        lastMoveTimeMs = SystemClock.uptimeMillis()
        velocityYPxPerSec = 0f
        isDragging = false
        isSearchPressed = false
        isTabTouch = false
        isTabDragging = false
        isDeletePressed = false
        isBackPressed = false
        pressedCellIndex = -1
        clearSkinTonePopup()
        mainHandler.removeCallbacks(deleteRepeatRunnable)
        mainHandler.removeCallbacks(skinToneLongPressRunnable)

        if (y <= tabHeight) {
            if (searchBtnBounds.contains(x, y)) {
                isSearchPressed = true
            } else {
                isTabTouch = true
            }
            return true
        }
        if (delKeyBounds.contains(x, y)) {
            isDeletePressed = true
            onDeleteClicked()
            mainHandler.postDelayed(deleteRepeatRunnable, 350L)
            return true
        }
        if (backKeyBounds.contains(x, y)) {
            isBackPressed = true
            return true
        }

        val idx = findGridIndexAt(x, y)
        pressedCellIndex = idx
        if (idx >= 0 && selectedCategory != EmojiData.CAT_KAOMOJI && selectedCategory != EmojiData.CAT_MATH_TECH) {
            pendingLongPressIndex = idx
            mainHandler.postDelayed(skinToneLongPressRunnable, longPressDelayMs)
        }
        return true
    }

    fun handleTouchMove(x: Float, y: Float): Boolean {
        val prevTouchX = lastTouchX
        lastTouchX = x

        if (isSkinTonePopupActive) {
            val variants = skinToneVariants
            if (variants != null && variants.isNotEmpty() && skinToneCellWidth > 0f) {
                if (!skinToneDragged && abs(x - skinToneAnchorX) > 6f * theme.density) {
                    skinToneDragged = true
                }
                if (skinToneDragged) {
                    val nextIdx = ((x - skinTonePopupRect.left) / skinToneCellWidth)
                        .toInt()
                        .coerceIn(0, variants.lastIndex)
                    if (nextIdx != skinToneSelectedIndex) {
                        skinToneSelectedIndex = nextIdx
                        onTriggerHaptic()
                        return true
                    }
                }
            }
            return false
        }

        if (isSearchPressed) {
            if (!searchBtnBounds.contains(x, y)) {
                isSearchPressed = false
                return true
            }
            return false
        }

        if (isTabTouch) {
            if (!isTabDragging && maxTabScrollX > 0f && abs(x - touchStartX) > touchSlop) {
                isTabDragging = true
            }
            if (isTabDragging) {
                val dx = prevTouchX - x
                tabScrollX = (tabScrollX + dx).coerceIn(0f, maxTabScrollX)
                return true
            }
            return false
        }

        if (isDeletePressed) {
            if (!delKeyBounds.contains(x, y)) {
                isDeletePressed = false
                mainHandler.removeCallbacks(deleteRepeatRunnable)
                return true
            }
            return false
        }
        if (isBackPressed) {
            if (!backKeyBounds.contains(x, y)) {
                isBackPressed = false
                return true
            }
            return false
        }

        if (!isDragging && (abs(y - touchStartY) > touchSlop || abs(x - touchStartX) > touchSlop)) {
            mainHandler.removeCallbacks(skinToneLongPressRunnable)
            pendingLongPressIndex = -1
            if (abs(y - touchStartY) > touchSlop) {
                isDragging = true
                pressedCellIndex = -1
            }
        }
        if (isDragging) {
            val dy = lastTouchY - y
            val now = SystemClock.uptimeMillis()
            val dt = (now - lastMoveTimeMs).coerceAtLeast(1L)
            val instantVel = (dy / dt.toFloat()) * 1000f
            velocityYPxPerSec = (velocityYPxPerSec * 0.35f + instantVel * 0.65f)
                .coerceIn(-maxFlingVelocity, maxFlingVelocity)
            lastMoveTimeMs = now
            scrollOffsetY = (scrollOffsetY + dy).coerceIn(0f, maxScrollY)
            lastTouchY = y
            return true
        }
        return false
    }

    fun handleTouchUp(x: Float, y: Float): Boolean {
        mainHandler.removeCallbacks(deleteRepeatRunnable)
        mainHandler.removeCallbacks(skinToneLongPressRunnable)

        if (isSkinTonePopupActive) {
            val chosen = skinToneVariants?.getOrNull(skinToneSelectedIndex)
            clearSkinTonePopup()
            pressedCellIndex = -1
            if (chosen != null) {
                historyManager.addRecent(chosen)
                if (selectedCategory == EmojiData.CAT_RECENTS) {
                    recalcMaxScroll()
                }
                onEmojiSelected(chosen)
            }
            return true
        }

        if (isSearchPressed) {
            isSearchPressed = false
            if (searchBtnBounds.contains(x, y)) {
                onTriggerHaptic()
                onSearchClicked()
            }
            return true
        }

        if (isTabTouch) {
            val wasDragging = isTabDragging
            isTabTouch = false
            isTabDragging = false
            if (!wasDragging && y <= tabHeight + 12f * theme.density && tabWidth > 0f && x >= tabsStartX) {
                val tappedTab = ((x - tabsStartX + tabScrollX) / tabWidth)
                    .toInt()
                    .coerceIn(0, EmojiData.CATEGORY_COUNT - 1)
                selectedCategory = tappedTab
                ensureTabVisible(tappedTab)
                scroller.forceFinished(true)
                scrollOffsetY = 0f
                recalcMaxScroll()
                onTriggerHaptic()
            }
            return true
        }

        if (isDeletePressed) {
            isDeletePressed = false
            return true
        }
        if (isBackPressed) {
            isBackPressed = false
            if (backKeyBounds.contains(x, y)) {
                onBackToKeyboard()
            }
            return true
        }

        pressedCellIndex = -1
        pendingLongPressIndex = -1

        if (isDragging) {
            isDragging = false
            if (abs(velocityYPxPerSec) >= minFlingVelocity && maxScrollY > 0f) {
                scroller.fling(
                    0,
                    scrollOffsetY.toInt(),
                    0,
                    velocityYPxPerSec.toInt(),
                    0,
                    0,
                    0,
                    maxScrollY.toInt()
                )
                onRequestInvalidate()
            }
            return true
        }

        if (backKeyBounds.contains(x, y)) {
            onBackToKeyboard()
            return true
        }
        if (delKeyBounds.contains(x, y)) {
            onDeleteClicked()
            return true
        }

        val index = findGridIndexAt(x, y)
        if (index >= 0) {
            val item = getItemAt(index)
            if (item != null) {
                historyManager.addRecent(item)
                if (selectedCategory == EmojiData.CAT_RECENTS) {
                    recalcMaxScroll()
                }
                onEmojiSelected(item)
                return true
            }
        }
        return true
    }

    /**
     * Safely aborts any in-progress gesture or delete hold without firing clicks.
     */
    fun handleTouchCancel() {
        mainHandler.removeCallbacks(deleteRepeatRunnable)
        mainHandler.removeCallbacks(skinToneLongPressRunnable)
        isDragging = false
        isSearchPressed = false
        isTabTouch = false
        isTabDragging = false
        isDeletePressed = false
        isBackPressed = false
        pressedCellIndex = -1
        velocityYPxPerSec = 0f
        clearSkinTonePopup()
    }
}
