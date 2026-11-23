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
 * vector/sans typography for Kaomoji (3 cols, auto-fit) and Math & Tech Symbols (6 cols).
 * Supports horizontal scrollable tab bar, vertical fling scrolling (OverScroller), touchSlop-aware
 * drag detection, hold-to-repeat backspace, and safe touch cancellation.
 */
class EmojiCanvasPanel(
    context: Context,
    private val theme: KeyboardTheme,
    private val historyManager: EmojiHistoryManager,
    private val touchSlop: Float = ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
    private val onEmojiSelected: (String) -> Unit,
    private val onBackToKeyboard: () -> Unit,
    private val onDeleteClicked: () -> Unit,
    private val onRequestInvalidate: () -> Unit = {}
) {

    companion object {
        private const val EMPTY_RECENTS_TEXT = "No recent items yet"
        private const val BACK_LABEL = "ABC"
        private const val COLS_EMOJI = 8
        private const val COLS_MATH = 6
        private const val COLS_KAOMOJI = 3
    }

    private var panelWidth = 0
    private var panelHeight = 0

    private val scroller = OverScroller(context)
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private val mainHandler = Handler(Looper.getMainLooper())

    // System color emoji font
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 28f * theme.density
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

    private val tabTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 18f * theme.density
        typeface = emojiPaint.typeface
    }

    private val tabSymbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 14f * theme.density
        typeface = Typeface.DEFAULT_BOLD
        color = theme.colorTextPrimary
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
    private val bottomBarBounds = RectF()
    private val backKeyBounds = RectF()
    private val delKeyBounds = RectF()

    private var tabHeight = 0f
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
    private var isTabTouch = false
    private var isTabDragging = false
    private var isDeletePressed = false
    private var isBackPressed = false
    private var pressedCellIndex = -1

    private val deleteRepeatRunnable = object : Runnable {
        override fun run() {
            if (isDeletePressed) {
                onDeleteClicked()
                mainHandler.postDelayed(this, 55L)
            }
        }
    }

    fun syncThemeColors() {
        tabBgPaint.color = theme.colorStripBg
        tabSelectedPaint.color = theme.colorAccent
        actionKeyPaint.color = theme.colorBgKey
        tabSymbolPaint.color = theme.colorTextPrimary
        kaomojiPaint.color = theme.colorTextPrimary
        mathSymbolPaint.color = theme.colorTextPrimary
    }

    fun detach() {
        mainHandler.removeCallbacks(deleteRepeatRunnable)
        scroller.forceFinished(true)
        isDeletePressed = false
        isBackPressed = false
        isDragging = false
        isTabTouch = false
        isTabDragging = false
        pressedCellIndex = -1
    }

    fun measure(width: Int, height: Int) {
        this.panelWidth = width
        this.panelHeight = height
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())

        tabHeight = (height * 0.145f).coerceIn(36f * theme.density, 44f * theme.density)
        val bottomHeight = (height * 0.16f).coerceIn(38f * theme.density, 50f * theme.density)
        val gridHeight = (height - tabHeight - bottomHeight).coerceAtLeast(1f)

        // Measure 12 top tabs (fit evenly on wide screens, horizontally scrollable with generous hit targets on phones)
        val minTabW = 40f * theme.density
        tabWidth = maxOf(width.toFloat() / EmojiData.CATEGORY_COUNT, minTabW)
        maxTabScrollX = (tabWidth * EmojiData.CATEGORY_COUNT - width.toFloat()).coerceAtLeast(0f)
        tabScrollX = tabScrollX.coerceIn(0f, maxTabScrollX)

        tabTextPaint.textSize = tabHeight * 0.48f
        tabSymbolPaint.textSize = (tabHeight * 0.34f).coerceIn(11.5f * theme.density, 15.5f * theme.density)

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

        // Measure grid cell heights & font sizes
        cellHeight = gridHeight / 3.6f
        emojiPaint.textSize = cellHeight * 0.54f
        mathSymbolPaint.textSize = (cellHeight * 0.48f).coerceIn(16f * theme.density, 24f * theme.density)
        kaomojiBaseTextSize = (cellHeight * 0.30f).coerceIn(11f * theme.density, 15f * theme.density)
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

    fun computeScrollOffset(): Boolean {
        if (scroller.computeScrollOffset()) {
            scrollOffsetY = scroller.currY.toFloat().coerceIn(0f, maxScrollY)
            return true
        }
        return false
    }

    fun draw(canvas: Canvas) {
        // 1. Draw Panel Background
        canvas.drawRect(bounds, theme.bgPaint)

        // 2. Draw Top 12-Category Tab Strip (with horizontal scroll clipping)
        canvas.drawRect(0f, 0f, panelWidth.toFloat(), tabHeight, tabBgPaint)
        canvas.drawRect(0f, tabHeight - theme.density, panelWidth.toFloat(), tabHeight, theme.stripDividerPaint)

        canvas.save()
        canvas.clipRect(0f, 0f, panelWidth.toFloat(), tabHeight)
        val indicatorH = 2.6f * theme.density
        val indicatorPad = 5f * theme.density

        for (i in 0 until EmojiData.CATEGORY_COUNT) {
            val left = i * tabWidth - tabScrollX
            val right = left + tabWidth
            if (right < 0f || left > panelWidth.toFloat()) continue

            val cx = (left + right) * 0.5f
            val cy = tabHeight * 0.5f

            if (i == selectedCategory) {
                theme.reusableRectF.set(
                    left + indicatorPad,
                    tabHeight - indicatorH,
                    right - indicatorPad,
                    tabHeight
                )
                canvas.drawRoundRect(theme.reusableRectF, indicatorH, indicatorH, tabSelectedPaint)
            }

            if (i >= EmojiData.CAT_KAOMOJI) {
                tabSymbolPaint.color = if (i == selectedCategory) theme.colorAccent else theme.colorTextPrimary
                val textY = cy - (tabSymbolPaint.descent() + tabSymbolPaint.ascent()) * 0.5f
                canvas.drawText(EmojiData.CATEGORY_ICONS[i], cx, textY, tabSymbolPaint)
            } else {
                val textY = cy - (tabTextPaint.descent() + tabTextPaint.ascent()) * 0.5f
                canvas.drawText(EmojiData.CATEGORY_ICONS[i], cx, textY, tabTextPaint)
            }
        }
        canvas.restore()

        // 3. Draw Adaptive Grid with clipping (zero allocations)
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
            val hasBorder = theme.keyBorderWidth > 0f && (theme.colorKeyBorder ushr 24) > 0
            val padH = if (isKaomojiCat) 3.5f * theme.density else 2.5f * theme.density
            val padV = if (isKaomojiCat) 3.5f * theme.density else 2.5f * theme.density
            val radius = theme.keyCornerRadius

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
                        drawAutoFitText(canvas, item, cx, cy, cellW - padH * 2f - 8f * theme.density, kaomojiPaint, kaomojiBaseTextSize)
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
                            drawAutoFitText(canvas, item, cx, cy, cellW - 4f * theme.density, kaomojiPaint, kaomojiBaseTextSize)
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
        }
        canvas.restore()

        // 4. Draw Bottom Control Bar (ABC | Category Title | Backspace)
        canvas.drawRect(bottomBarBounds, tabBgPaint)
        canvas.drawRect(0f, bottomBarBounds.top, panelWidth.toFloat(), bottomBarBounds.top + theme.density, theme.stripDividerPaint)

        // Back to keyboard button
        val backPaint = if (isBackPressed) theme.keyPressedPaint else actionKeyPaint
        canvas.drawRoundRect(backKeyBounds, theme.keyCornerRadius, theme.keyCornerRadius, backPaint)
        if (theme.keyBorderWidth > 0f && (theme.colorKeyBorder ushr 24) > 0) {
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
        if (theme.keyBorderWidth > 0f && (theme.colorKeyBorder ushr 24) > 0) {
            canvas.drawRoundRect(delKeyBounds, theme.keyCornerRadius, theme.keyCornerRadius, theme.keyBorderPaint)
        }
        drawBackspaceVector(canvas, delKeyBounds)
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
        } else if (tabRight > tabScrollX + panelWidth) {
            tabScrollX = (tabRight - panelWidth).coerceIn(0f, maxTabScrollX)
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
        isTabTouch = false
        isTabDragging = false
        isDeletePressed = false
        isBackPressed = false
        pressedCellIndex = -1
        mainHandler.removeCallbacks(deleteRepeatRunnable)

        if (y <= tabHeight) {
            isTabTouch = true
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

        pressedCellIndex = findGridIndexAt(x, y)
        return true
    }

    fun handleTouchMove(x: Float, y: Float): Boolean {
        if (isTabTouch) {
            if (!isTabDragging && maxTabScrollX > 0f && abs(x - touchStartX) > touchSlop) {
                isTabDragging = true
            }
            if (isTabDragging) {
                val dx = lastTouchX - x
                tabScrollX = (tabScrollX + dx).coerceIn(0f, maxTabScrollX)
                lastTouchX = x
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

        if (!isDragging && abs(y - touchStartY) > touchSlop) {
            isDragging = true
            pressedCellIndex = -1
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

        if (isTabTouch) {
            val wasDragging = isTabDragging
            isTabTouch = false
            isTabDragging = false
            if (!wasDragging && y <= tabHeight + 12f * theme.density && tabWidth > 0f) {
                val tappedTab = ((x + tabScrollX) / tabWidth).toInt().coerceIn(0, EmojiData.CATEGORY_COUNT - 1)
                selectedCategory = tappedTab
                ensureTabVisible(tappedTab)
                scroller.forceFinished(true)
                scrollOffsetY = 0f
                recalcMaxScroll()
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
        isDragging = false
        isTabTouch = false
        isTabDragging = false
        isDeletePressed = false
        isBackPressed = false
        pressedCellIndex = -1
        velocityYPxPerSec = 0f
    }
}
