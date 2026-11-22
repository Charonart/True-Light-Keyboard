package com.truelight.ime.emoji

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.truelight.ime.ui.theme.KeyboardTheme
import java.io.File
import kotlin.math.abs

/**
 * High-performance, zero-allocation native Emoji Canvas Renderer.
 * Directly utilizes system font /system/fonts/NotoColorEmoji.ttf.
 */
class EmojiCanvasPanel(
    private val theme: KeyboardTheme,
    private val historyManager: EmojiHistoryManager,
    private val onEmojiSelected: (String) -> Unit,
    private val onBackToKeyboard: () -> Unit,
    private val onDeleteClicked: () -> Unit
) {

    private var panelWidth = 0
    private var panelHeight = 0

    // System font loading
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 28f
        val fontFile = File("/system/fonts/NotoColorEmoji.ttf")
        if (fontFile.exists() && fontFile.canRead()) {
            try {
                typeface = Typeface.createFromFile(fontFile)
            } catch (ignored: Exception) {
                typeface = Typeface.DEFAULT
            }
        } else {
            typeface = Typeface.DEFAULT
        }
    }

    private val tabBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.colorBgKeySpecial
        style = Paint.Style.FILL
    }

    private val tabSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.colorAccent
        style = Paint.Style.FILL
    }

    private val tabTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 18f
        typeface = emojiPaint.typeface
    }

    private val actionKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.colorBgKey
        style = Paint.Style.FILL
    }

    // Geometry caches
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

    // Touch tracking for scrolling
    private var lastTouchY = 0f
    private var isDragging = false
    private var touchStartY = 0f

    fun measure(width: Int, height: Int) {
        this.panelWidth = width
        this.panelHeight = height
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())

        val tabHeight = height * 0.12f
        val bottomHeight = height * 0.14f
        val gridHeight = height - tabHeight - bottomHeight

        // Measure top tabs (10 tabs)
        val tabW = width / 10f
        for (i in 0 until 10) {
            categoryTabBounds[i].set(i * tabW, 0f, (i + 1) * tabW, tabHeight)
        }
        tabTextPaint.textSize = tabHeight * 0.55f

        // Measure bottom bar
        bottomBarBounds.set(0f, height - bottomHeight, width.toFloat(), height.toFloat())
        val actionW = width * 0.2f
        backKeyBounds.set(
            theme.keyMarginH,
            height - bottomHeight + theme.keyMarginV,
            actionW,
            height - theme.keyMarginV
        )
        delKeyBounds.set(
            width - actionW,
            height - bottomHeight + theme.keyMarginV,
            width - theme.keyMarginH,
            height - theme.keyMarginV
        )

        // Measure grid cells
        cellWidth = width.toFloat() / columns
        cellHeight = gridHeight / 3.5f // ~3.5 rows visible
        emojiPaint.textSize = cellHeight * 0.55f

        recalcMaxScroll()
    }

    private fun recalcMaxScroll() {
        val count = getActiveCategoryCount()
        val totalRows = (count + columns - 1) / columns
        val visibleGridHeight = panelHeight * 0.74f
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

    fun draw(canvas: Canvas) {
        // 1. Draw Panel Background
        canvas.drawRect(bounds, theme.bgPaint)

        // 2. Draw Top Category Tabs
        val tabH = categoryTabBounds[0].height()
        canvas.drawRect(0f, 0f, panelWidth.toFloat(), tabH, tabBgPaint)
        for (i in 0 until 10) {
            val rect = categoryTabBounds[i]
            if (i == selectedCategory) {
                canvas.drawRect(rect.left, rect.bottom - 4f, rect.right, rect.bottom, tabSelectedPaint)
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
        val totalRows = (totalCount + columns - 1) / columns

        // Calculate visible row range
        if (cellHeight > 0f) {
            val startRow = (scrollOffsetY / cellHeight).toInt().coerceAtLeast(0)
            val endRow = ((scrollOffsetY + (gridBottom - gridTop)) / cellHeight).toInt() + 1

            for (row in startRow..endRow) {
                if (row >= totalRows) break
                val y = gridTop + row * cellHeight - scrollOffsetY
                for (col in 0 until columns) {
                    val index = row * columns + col
                    if (index >= totalCount) break
                    val emoji = getEmojiAt(index) ?: continue
                    val x = col * cellWidth + cellWidth / 2f
                    val baseline = y + cellHeight / 2f - (emojiPaint.descent() + emojiPaint.ascent()) / 2f
                    canvas.drawText(emoji, x, baseline, emojiPaint)
                }
            }
        }
        canvas.restore()

        // 4. Draw Bottom Control Bar
        canvas.drawRect(bottomBarBounds, tabBgPaint)

        // Back to keyboard button
        canvas.drawRoundRect(backKeyBounds, theme.keyCornerRadius, theme.keyCornerRadius, actionKeyPaint)
        val backTextY = backKeyBounds.centerY() - (theme.textPrimaryPaint.descent() + theme.textPrimaryPaint.ascent()) / 2f
        canvas.drawText("ABC", backKeyBounds.centerX(), backTextY, theme.textPrimaryPaint)

        // Delete button
        canvas.drawRoundRect(delKeyBounds, theme.keyCornerRadius, theme.keyCornerRadius, actionKeyPaint)
        val delTextY = delKeyBounds.centerY() - (theme.textPrimaryPaint.descent() + theme.textPrimaryPaint.ascent()) / 2f
        canvas.drawText("⌫", delKeyBounds.centerX(), delTextY, theme.textPrimaryPaint)
    }

    fun handleTouchDown(x: Float, y: Float): Boolean {
        touchStartY = y
        lastTouchY = y
        isDragging = false
        return true
    }

    fun handleTouchMove(x: Float, y: Float): Boolean {
        val dy = lastTouchY - y
        if (abs(y - touchStartY) > 8f) {
            isDragging = true
        }
        if (isDragging) {
            scrollOffsetY = (scrollOffsetY + dy).coerceIn(0f, maxScrollY)
            lastTouchY = y
            return true
        }
        return false
    }

    fun handleTouchUp(x: Float, y: Float): Boolean {
        if (!isDragging) {
            // Check top tabs
            for (i in 0 until 10) {
                if (categoryTabBounds[i].contains(x, y)) {
                    selectedCategory = i
                    scrollOffsetY = 0f
                    recalcMaxScroll()
                    return true
                }
            }

            // Check bottom actions
            if (backKeyBounds.contains(x, y)) {
                onBackToKeyboard()
                return true
            }
            if (delKeyBounds.contains(x, y)) {
                onDeleteClicked()
                return true
            }

            // Check emoji grid click
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
                    onEmojiSelected(emoji)
                    return true
                }
            }
        }
        isDragging = false
        return true
    }
}
