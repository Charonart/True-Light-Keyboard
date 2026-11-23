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
 * High-performance, zero-allocation native Emoji Canvas Renderer.
 * Directly utilizes system font /system/fonts/NotoColorEmoji.ttf.
 * Supports fling scrolling (OverScroller), touchSlop-aware drag detection,
 * hold-to-repeat backspace, and safe touch cancellation.
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
        private const val EMPTY_RECENTS_TEXT = "No recent emojis yet"
        private const val BACK_LABEL = "ABC"
    }

    private var panelWidth = 0
    private var panelHeight = 0

    private val scroller = OverScroller(context)
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private val mainHandler = Handler(Looper.getMainLooper())

    // System font loading
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

    private val actionKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.colorBgKey
        style = Paint.Style.FILL
    }

    // Geometry caches (preallocated)
    private val bounds = RectF()
    private val categoryTabBounds = Array(10) { RectF() }
    private val bottomBarBounds = RectF()
    private val backKeyBounds = RectF()
    private val delKeyBounds = RectF()

    private var selectedCategory = 1 // 0: recents, 1: smileys
    private var scrollOffsetY = 0f
    private var maxScrollY = 0f

    private val columns = 8
    private var cellWidth = 0f
    private var cellHeight = 0f

    // Touch & fling tracking (zero-allocation velocity estimation)
    private var lastTouchY = 0f
    private var touchStartY = 0f
    private var lastMoveTimeMs = 0L
    private var velocityYPxPerSec = 0f
    private var isDragging = false
    private var isDeletePressed = false
    private var isBackPressed = false

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
    }

    fun detach() {
        mainHandler.removeCallbacks(deleteRepeatRunnable)
        scroller.forceFinished(true)
        isDeletePressed = false
        isBackPressed = false
        isDragging = false
    }

    fun measure(width: Int, height: Int) {
        this.panelWidth = width
        this.panelHeight = height
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())

        val tabHeight = (height * 0.14f).coerceIn(34f * theme.density, 44f * theme.density)
        val bottomHeight = (height * 0.16f).coerceIn(38f * theme.density, 50f * theme.density)
        val gridHeight = (height - tabHeight - bottomHeight).coerceAtLeast(1f)

        // Measure top tabs (10 tabs)
        val tabW = width / 10f
        for (i in 0 until 10) {
            categoryTabBounds[i].set(i * tabW, 0f, (i + 1) * tabW, tabHeight)
        }
        tabTextPaint.textSize = tabHeight * 0.52f

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

        // Measure grid cells
        cellWidth = width.toFloat() / columns
        cellHeight = gridHeight / 3.6f
        emojiPaint.textSize = cellHeight * 0.56f

        recalcMaxScroll()
    }

    private fun recalcMaxScroll() {
        val count = getActiveCategoryCount()
        val totalRows = (count + columns - 1) / columns
        val visibleGridHeight = (bottomBarBounds.top - categoryTabBounds[0].bottom).coerceAtLeast(1f)
        val contentHeight = totalRows * cellHeight
        maxScrollY = (contentHeight - visibleGridHeight).coerceAtLeast(0f)
        scrollOffsetY = scrollOffsetY.coerceIn(0f, maxScrollY)
    }

    private fun getActiveCategoryCount(): Int {
        return if (selectedCategory == 0) {
            historyManager.getRecentList().size
        } else {
            EmojiData.ALL_CATEGORIES[selectedCategory].size
        }
    }

    private fun getEmojiAt(index: Int): String? {
        return if (selectedCategory == 0) {
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

        // 2. Draw Top Category Tabs
        val tabH = categoryTabBounds[0].height()
        canvas.drawRect(0f, 0f, panelWidth.toFloat(), tabH, tabBgPaint)
        canvas.drawRect(0f, tabH - theme.density, panelWidth.toFloat(), tabH, theme.stripDividerPaint)

        val indicatorH = 2.5f * theme.density
        for (i in 0 until 10) {
            val rect = categoryTabBounds[i]
            if (i == selectedCategory) {
                canvas.drawRect(
                    rect.left + 3f * theme.density,
                    rect.bottom - indicatorH,
                    rect.right - 3f * theme.density,
                    rect.bottom,
                    tabSelectedPaint
                )
            }
            val textY = rect.centerY() - (tabTextPaint.descent() + tabTextPaint.ascent()) / 2f
            canvas.drawText(EmojiData.CATEGORY_ICONS[i], rect.centerX(), textY, tabTextPaint)
        }

        // 3. Draw Emoji Grid with clipping
        val gridTop = tabH
        val gridBottom = bottomBarBounds.top
        canvas.save()
        canvas.clipRect(0f, gridTop, panelWidth.toFloat(), gridBottom)

        val totalCount = getActiveCategoryCount()
        if (totalCount == 0 && selectedCategory == 0) {
            val emptyY = (gridTop + gridBottom) * 0.5f -
                (theme.textSpacePaint.descent() + theme.textSpacePaint.ascent()) * 0.5f
            canvas.drawText(EMPTY_RECENTS_TEXT, panelWidth * 0.5f, emptyY, theme.textSpacePaint)
        } else if (cellHeight > 0f) {
            val totalRows = (totalCount + columns - 1) / columns
            val startRow = (scrollOffsetY / cellHeight).toInt().coerceAtLeast(0)
            val endRow = ((scrollOffsetY + (gridBottom - gridTop)) / cellHeight).toInt() + 1

            for (row in startRow..endRow) {
                if (row >= totalRows) break
                val y = gridTop + row * cellHeight - scrollOffsetY
                for (col in 0 until columns) {
                    val index = row * columns + col
                    if (index >= totalCount) break
                    val emoji = getEmojiAt(index) ?: continue
                    val x = col * cellWidth + cellWidth * 0.5f
                    val baseline = y + cellHeight * 0.5f - (emojiPaint.descent() + emojiPaint.ascent()) * 0.5f
                    canvas.drawText(emoji, x, baseline, emojiPaint)
                }
            }
        }
        canvas.restore()

        // 4. Draw Bottom Control Bar
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

        // Delete button with vector backspace icon
        val delPaint = if (isDeletePressed) theme.keyPressedPaint else actionKeyPaint
        canvas.drawRoundRect(delKeyBounds, theme.keyCornerRadius, theme.keyCornerRadius, delPaint)
        if (theme.keyBorderWidth > 0f && (theme.colorKeyBorder ushr 24) > 0) {
            canvas.drawRoundRect(delKeyBounds, theme.keyCornerRadius, theme.keyCornerRadius, theme.keyBorderPaint)
        }
        drawBackspaceVector(canvas, delKeyBounds)
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

    fun handleTouchDown(x: Float, y: Float): Boolean {
        scroller.forceFinished(true)
        touchStartY = y
        lastTouchY = y
        lastMoveTimeMs = SystemClock.uptimeMillis()
        velocityYPxPerSec = 0f
        isDragging = false
        isDeletePressed = false
        isBackPressed = false
        mainHandler.removeCallbacks(deleteRepeatRunnable)

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
        return true
    }

    fun handleTouchMove(x: Float, y: Float): Boolean {
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

        // Tap handling
        for (i in 0 until 10) {
            if (categoryTabBounds[i].contains(x, y)) {
                selectedCategory = i
                scroller.forceFinished(true)
                scrollOffsetY = 0f
                recalcMaxScroll()
                return true
            }
        }

        if (backKeyBounds.contains(x, y)) {
            onBackToKeyboard()
            return true
        }
        if (delKeyBounds.contains(x, y)) {
            onDeleteClicked()
            return true
        }

        val tabH = categoryTabBounds[0].height()
        val gridBottom = bottomBarBounds.top
        if (y in tabH..gridBottom && cellHeight > 0f && cellWidth > 0f) {
            val gridY = y - tabH + scrollOffsetY
            val row = (gridY / cellHeight).toInt()
            val col = (x / cellWidth).toInt().coerceIn(0, columns - 1)
            val index = row * columns + col
            val emoji = getEmojiAt(index)
            if (emoji != null) {
                historyManager.addRecent(emoji)
                if (selectedCategory == 0) {
                    recalcMaxScroll()
                }
                onEmojiSelected(emoji)
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
        isDeletePressed = false
        isBackPressed = false
        velocityYPxPerSec = 0f
    }
}
