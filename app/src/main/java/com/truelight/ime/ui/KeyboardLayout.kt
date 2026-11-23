package com.truelight.ime.ui

import com.truelight.ime.lang.LanguageRegistry
import com.truelight.ime.lang.LanguageSpec
import com.truelight.ime.ui.theme.KeyboardTheme

/**
 * Dynamic, zero-allocation-in-onDraw Key Layout Engine.
 * Supports all Group 1 languages, optional dedicated number row,
 * dedicated comma key, zero dead zones via nearest-key hit resolution,
 * Symbols, Symbols-Shift, and Numpad.
 */
class KeyboardLayout(private val theme: KeyboardTheme) {

    enum class Mode {
        ALPHA,
        SYMBOLS,
        SYMBOLS_SHIFT,
        NUMPAD
    }

    var currentMode = Mode.ALPHA
    var isShifted = false
    var isCapsLock = false
    var showNumberRow = false
    var showSubLabels = true
    var showEmojiKey = false

    var activeLanguage: LanguageSpec = LanguageRegistry.EN_US
        private set

    var hasMultipleLanguages = false
        private set

    private var lastWidth = 0
    private var lastTopOffset = 0f
    private var lastKeyAreaHeight = 0f

    // Track dynamic row counts for active language alpha layout
    private var numberRowCount = 0
    private var r0Count = 0
    private var r1Count = 0
    private var r2LetterCount = 0

    // Preallocated key lists
    val alphaKeys = ArrayList<KeyModel>(64)
    val symbolKeys = ArrayList<KeyModel>(40)
    val symbolShiftKeys = ArrayList<KeyModel>(40)
    val numpadKeys = ArrayList<KeyModel>(16)

    init {
        buildAlphaKeys()
        buildSymbolKeys()
        buildSymbolShiftKeys()
        buildNumpadKeys()
    }

    fun setLanguage(spec: LanguageSpec, multipleLanguages: Boolean) {
        this.activeLanguage = spec
        this.hasMultipleLanguages = multipleLanguages
        this.isShifted = false
        this.isCapsLock = false
        buildAlphaKeys()
        buildSymbolKeys()
        buildSymbolShiftKeys()
        if (lastWidth > 0 && lastKeyAreaHeight > 0f) {
            measure(lastWidth, lastTopOffset, lastKeyAreaHeight)
        }
    }

    fun setDisplayOptions(
        numberRow: Boolean,
        subLabels: Boolean,
        emojiKey: Boolean = false
    ) {
        val numberRowChanged = (this.showNumberRow != numberRow)
        val emojiKeyChanged = (this.showEmojiKey != emojiKey)
        this.showNumberRow = numberRow
        this.showSubLabels = subLabels
        this.showEmojiKey = emojiKey
        if (numberRowChanged || emojiKeyChanged) {
            buildAlphaKeys()
            if (emojiKeyChanged) {
                buildSymbolKeys()
                buildSymbolShiftKeys()
            }
            if (isShifted || isCapsLock) {
                updateShiftState(isShifted, isCapsLock)
            }
            if (lastWidth > 0 && lastKeyAreaHeight > 0f) {
                measure(lastWidth, lastTopOffset, lastKeyAreaHeight)
            }
        }
    }

    private fun buildSpaceLabel(): String {
        return if (hasMultipleLanguages) {
            "${activeLanguage.spaceLabel} · ${activeLanguage.shortCode}"
        } else {
            activeLanguage.spaceLabel
        }
    }

    private fun buildAlphaKeys() {
        alphaKeys.clear()

        // Optional dedicated number row (1..0)
        numberRowCount = if (showNumberRow) 10 else 0
        if (showNumberRow) {
            val digits = arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
            for (d in digits) {
                alphaKeys.add(KeyModel(code = d[0].code, label = d, type = KeyModel.TYPE_NORMAL))
            }
        }

        val rows = activeLanguage.rows
        val subs = activeLanguage.subLabels

        r0Count = rows[0].size
        for (i in 0 until r0Count) {
            val sub = if (!showNumberRow && subs != null && i < subs[0].size) subs[0][i] else null
            alphaKeys.add(
                KeyModel(
                    code = rows[0][i][0].code,
                    label = rows[0][i],
                    subLabel = sub,
                    type = KeyModel.TYPE_NORMAL
                )
            )
        }

        r1Count = rows[1].size
        for (i in 0 until r1Count) {
            val sub = if (subs != null && i < subs[1].size) subs[1][i] else null
            alphaKeys.add(
                KeyModel(
                    code = rows[1][i][0].code,
                    label = rows[1][i],
                    subLabel = sub,
                    type = KeyModel.TYPE_NORMAL
                )
            )
        }

        // Row 2: Shift, letters, Backspace
        alphaKeys.add(KeyModel(code = KeyModel.CODE_SHIFT, label = "⇧", type = KeyModel.TYPE_SHIFT))
        r2LetterCount = rows[2].size
        for (i in 0 until r2LetterCount) {
            val sub = if (subs != null && i < subs[2].size) subs[2][i] else null
            alphaKeys.add(
                KeyModel(
                    code = rows[2][i][0].code,
                    label = rows[2][i],
                    subLabel = sub,
                    type = KeyModel.TYPE_NORMAL
                )
            )
        }
        alphaKeys.add(KeyModel(code = KeyModel.CODE_DELETE, label = "⌫", type = KeyModel.TYPE_DELETE))

        // Row 3 (5 clean symmetrical keys by default, or 6 with optional Emoji key):
        // ?123, [optional Emoji], Comma, Space, Period, Enter
        alphaKeys.add(KeyModel(code = KeyModel.CODE_SWITCH_SYMBOLS, label = "?123", type = KeyModel.TYPE_SWITCH_SYMBOLS))
        if (showEmojiKey) {
            alphaKeys.add(KeyModel(code = KeyModel.CODE_EMOJI, label = "", type = KeyModel.TYPE_EMOJI))
        }
        alphaKeys.add(KeyModel(code = ','.code, label = ",", subLabel = "!", type = KeyModel.TYPE_NORMAL))
        alphaKeys.add(KeyModel(code = KeyModel.CODE_SPACE, label = buildSpaceLabel(), type = KeyModel.TYPE_SPACE))
        alphaKeys.add(KeyModel(code = '.'.code, label = ".", subLabel = "?", type = KeyModel.TYPE_NORMAL))
        alphaKeys.add(KeyModel(code = KeyModel.CODE_ENTER, label = "↵", type = KeyModel.TYPE_ENTER))
    }

    private fun buildSymbolKeys() {
        symbolKeys.clear()
        val r0 = arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        for (s in r0) symbolKeys.add(KeyModel(code = s[0].code, label = s, type = KeyModel.TYPE_NORMAL))

        val r1 = arrayOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/")
        for (s in r1) symbolKeys.add(KeyModel(code = s[0].code, label = s, type = KeyModel.TYPE_NORMAL))

        symbolKeys.add(KeyModel(code = KeyModel.CODE_SHIFT, label = "=\\<", type = KeyModel.TYPE_SHIFT))
        val r2 = arrayOf("*", "\"", "'", ":", ";", "!", "?")
        for (s in r2) symbolKeys.add(KeyModel(code = s[0].code, label = s, type = KeyModel.TYPE_NORMAL))
        symbolKeys.add(KeyModel(code = KeyModel.CODE_DELETE, label = "⌫", type = KeyModel.TYPE_DELETE))

        symbolKeys.add(KeyModel(code = KeyModel.CODE_SWITCH_ALPHA, label = "ABC", type = KeyModel.TYPE_SWITCH_ALPHA))
        if (showEmojiKey) {
            symbolKeys.add(KeyModel(code = KeyModel.CODE_EMOJI, label = "", type = KeyModel.TYPE_EMOJI))
        }
        symbolKeys.add(KeyModel(code = ','.code, label = ",", type = KeyModel.TYPE_NORMAL))
        symbolKeys.add(KeyModel(code = KeyModel.CODE_SPACE, label = buildSpaceLabel(), type = KeyModel.TYPE_SPACE))
        symbolKeys.add(KeyModel(code = '.'.code, label = ".", type = KeyModel.TYPE_NORMAL))
        symbolKeys.add(KeyModel(code = KeyModel.CODE_ENTER, label = "↵", type = KeyModel.TYPE_ENTER))
    }

    private fun buildSymbolShiftKeys() {
        symbolShiftKeys.clear()
        val r0 = arrayOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆")
        for (s in r0) symbolShiftKeys.add(KeyModel(code = s[0].code, label = s, type = KeyModel.TYPE_NORMAL))

        val r1 = arrayOf("£", "¥", "€", "¢", "^", "°", "=", "{", "}", "\\")
        for (s in r1) symbolShiftKeys.add(KeyModel(code = s[0].code, label = s, type = KeyModel.TYPE_NORMAL))

        symbolShiftKeys.add(KeyModel(code = KeyModel.CODE_SHIFT, label = "?123", type = KeyModel.TYPE_SHIFT))
        val r2 = arrayOf("©", "®", "™", "%", "[", "]")
        for (s in r2) symbolShiftKeys.add(KeyModel(code = s[0].code, label = s, type = KeyModel.TYPE_NORMAL))
        symbolShiftKeys.add(KeyModel(code = KeyModel.CODE_DELETE, label = "⌫", type = KeyModel.TYPE_DELETE))

        symbolShiftKeys.add(KeyModel(code = KeyModel.CODE_SWITCH_ALPHA, label = "ABC", type = KeyModel.TYPE_SWITCH_ALPHA))
        if (showEmojiKey) {
            symbolShiftKeys.add(KeyModel(code = KeyModel.CODE_EMOJI, label = "", type = KeyModel.TYPE_EMOJI))
        }
        symbolShiftKeys.add(KeyModel(code = '<'.code, label = "<", type = KeyModel.TYPE_NORMAL))
        symbolShiftKeys.add(KeyModel(code = KeyModel.CODE_SPACE, label = buildSpaceLabel(), type = KeyModel.TYPE_SPACE))
        symbolShiftKeys.add(KeyModel(code = '>'.code, label = ">", type = KeyModel.TYPE_NORMAL))
        symbolShiftKeys.add(KeyModel(code = KeyModel.CODE_ENTER, label = "↵", type = KeyModel.TYPE_ENTER))
    }

    private fun buildNumpadKeys() {
        numpadKeys.clear()
        numpadKeys.add(KeyModel(code = '1'.code, label = "1", type = KeyModel.TYPE_NORMAL))
        numpadKeys.add(KeyModel(code = '2'.code, label = "2", type = KeyModel.TYPE_NORMAL))
        numpadKeys.add(KeyModel(code = '3'.code, label = "3", type = KeyModel.TYPE_NORMAL))
        numpadKeys.add(KeyModel(code = KeyModel.CODE_DELETE, label = "⌫", type = KeyModel.TYPE_DELETE))

        numpadKeys.add(KeyModel(code = '4'.code, label = "4", type = KeyModel.TYPE_NORMAL))
        numpadKeys.add(KeyModel(code = '5'.code, label = "5", type = KeyModel.TYPE_NORMAL))
        numpadKeys.add(KeyModel(code = '6'.code, label = "6", type = KeyModel.TYPE_NORMAL))
        numpadKeys.add(KeyModel(code = '-'.code, label = "-", type = KeyModel.TYPE_NORMAL))

        numpadKeys.add(KeyModel(code = '7'.code, label = "7", type = KeyModel.TYPE_NORMAL))
        numpadKeys.add(KeyModel(code = '8'.code, label = "8", type = KeyModel.TYPE_NORMAL))
        numpadKeys.add(KeyModel(code = '9'.code, label = "9", type = KeyModel.TYPE_NORMAL))
        numpadKeys.add(KeyModel(code = '+'.code, label = "+", type = KeyModel.TYPE_NORMAL))

        numpadKeys.add(KeyModel(code = KeyModel.CODE_SWITCH_ALPHA, label = "ABC", type = KeyModel.TYPE_SWITCH_ALPHA))
        numpadKeys.add(KeyModel(code = '0'.code, label = "0", type = KeyModel.TYPE_NORMAL))
        numpadKeys.add(KeyModel(code = '.'.code, label = ".", type = KeyModel.TYPE_NORMAL))
        numpadKeys.add(KeyModel(code = KeyModel.CODE_ENTER, label = "↵", type = KeyModel.TYPE_ENTER))
    }

    fun getActiveKeys(): List<KeyModel> {
        return when (currentMode) {
            Mode.ALPHA -> alphaKeys
            Mode.SYMBOLS -> symbolKeys
            Mode.SYMBOLS_SHIFT -> symbolShiftKeys
            Mode.NUMPAD -> numpadKeys
        }
    }

    fun measure(width: Int, topOffset: Float, keyAreaHeight: Float) {
        lastWidth = width
        lastTopOffset = topOffset
        lastKeyAreaHeight = keyAreaHeight

        val mh = theme.keyMarginH
        val mv = theme.keyMarginV

        measureAlpha(width, topOffset, keyAreaHeight, mh, mv)

        // Symbols, Symbols-Shift, and Numpad fill the exact same keyAreaHeight -> zero vertical jump
        val rowHeight4 = keyAreaHeight / 4f
        measureSymbols(width, topOffset, rowHeight4, mh, mv)
        measureSymbolShift(width, topOffset, rowHeight4, mh, mv)
        measureNumpad(width, topOffset, rowHeight4, mh, mv)
    }

    private fun measureAlpha(width: Int, topOffset: Float, keyAreaHeight: Float, mh: Float, mv: Float) {
        val totalRows = if (showNumberRow) 5 else 4
        val rowHeight = keyAreaHeight / totalRows.toFloat()
        var idx = 0
        var curRow = 0

        // Optional number row (10 keys)
        if (showNumberRow) {
            val kwNum = (width - mh * 20) / 10f
            var x = mh
            val y = topOffset + curRow * rowHeight + mv
            for (i in 0 until 10) {
                alphaKeys[idx++].bounds.set(x, y, x + kwNum, y + rowHeight - mv * 2)
                x += kwNum + mh * 2
            }
            curRow++
        }

        // Row 0: r0Count keys (10 or 11 for Russian)
        val kw0 = (width - mh * (r0Count * 2)) / r0Count.toFloat()
        var x = mh
        var y = topOffset + curRow * rowHeight + mv
        for (i in 0 until r0Count) {
            alphaKeys[idx++].bounds.set(x, y, x + kw0, y + rowHeight - mv * 2)
            x += kw0 + mh * 2
        }
        curRow++

        // Row 1: r1Count keys (centered if fewer than r0Count)
        val kw1 = if (r1Count <= r0Count) kw0 else (width - mh * (r1Count * 2)) / r1Count.toFloat()
        val row1TotalW = kw1 * r1Count + mh * (r1Count * 2)
        val offsetR1 = ((width - row1TotalW) / 2f).coerceAtLeast(0f)
        x = offsetR1 + mh
        y = topOffset + curRow * rowHeight + mv
        for (i in 0 until r1Count) {
            alphaKeys[idx++].bounds.set(x, y, x + kw1, y + rowHeight - mv * 2)
            x += kw1 + mh * 2
        }
        curRow++

        // Row 2: Shift, r2LetterCount keys, Backspace
        val kw10 = (width - mh * 20) / 10f
        val specialW = if (r2LetterCount >= 9) kw10 * 1.1f else kw10 * 1.4f
        val totalColsR2 = r2LetterCount + 2
        val middleW = (width - specialW * 2 - mh * (totalColsR2 * 2)) / r2LetterCount.toFloat()
        y = topOffset + curRow * rowHeight + mv
        x = mh
        alphaKeys[idx++].bounds.set(x, y, x + specialW, y + rowHeight - mv * 2) // Shift
        x += specialW + mh * 2
        for (i in 0 until r2LetterCount) {
            alphaKeys[idx++].bounds.set(x, y, x + middleW, y + rowHeight - mv * 2)
            x += middleW + mh * 2
        }
        alphaKeys[idx++].bounds.set(x, y, x + specialW, y + rowHeight - mv * 2) // Del
        curRow++

        // Row 3: ?123 (1.35x), [optional Emoji 0.9x], ',' (0.9x), Space (~4.9x / ~4.0x), '.' (0.9x), Enter (1.45x)
        y = topOffset + curRow * rowHeight + mv
        val sideKeyW = kw10 * 1.35f
        val emojiKeyW = kw10 * 0.9f
        val punctW = kw10 * 0.9f
        val enterW = kw10 * 1.45f
        val row3KeyCount = if (showEmojiKey) 6 else 5
        val emojiSlotW = if (showEmojiKey) emojiKeyW else 0f
        val spaceW = (
            width - (sideKeyW + emojiSlotW + punctW * 2f + enterW + mh * (row3KeyCount * 2f))
        ).coerceAtLeast(kw10 * 2f)

        x = mh
        alphaKeys[idx++].bounds.set(x, y, x + sideKeyW, y + rowHeight - mv * 2) // ?123
        x += sideKeyW + mh * 2
        if (showEmojiKey) {
            alphaKeys[idx++].bounds.set(x, y, x + emojiKeyW, y + rowHeight - mv * 2) // Emoji
            x += emojiKeyW + mh * 2
        }
        alphaKeys[idx++].bounds.set(x, y, x + punctW, y + rowHeight - mv * 2) // ,
        x += punctW + mh * 2
        alphaKeys[idx++].bounds.set(x, y, x + spaceW, y + rowHeight - mv * 2) // Space
        x += spaceW + mh * 2
        alphaKeys[idx++].bounds.set(x, y, x + punctW, y + rowHeight - mv * 2) // .
        x += punctW + mh * 2
        alphaKeys[idx].bounds.set(x, y, x + enterW, y + rowHeight - mv * 2) // Enter
    }

    private fun measureSymbols(width: Int, topOffset: Float, rowHeight: Float, mh: Float, mv: Float) {
        val kw10 = (width - mh * 20) / 10f
        var x = mh
        var y = topOffset + mv
        for (i in 0 until 10) {
            symbolKeys[i].bounds.set(x, y, x + kw10, y + rowHeight - mv * 2)
            x += kw10 + mh * 2
        }
        x = mh
        y = topOffset + rowHeight + mv
        for (i in 10 until 20) {
            symbolKeys[i].bounds.set(x, y, x + kw10, y + rowHeight - mv * 2)
            x += kw10 + mh * 2
        }

        val specialW = kw10 * 1.4f
        val middleW = (width - specialW * 2 - mh * 18) / 7f
        y = topOffset + rowHeight * 2 + mv
        x = mh
        symbolKeys[20].bounds.set(x, y, x + specialW, y + rowHeight - mv * 2)
        x += specialW + mh * 2
        for (i in 21 until 28) {
            symbolKeys[i].bounds.set(x, y, x + middleW, y + rowHeight - mv * 2)
            x += middleW + mh * 2
        }
        symbolKeys[28].bounds.set(x, y, x + specialW, y + rowHeight - mv * 2)

        y = topOffset + rowHeight * 3 + mv
        val sideKeyW = kw10 * 1.35f
        val emojiKeyW = kw10 * 0.9f
        val enterW = kw10 * 1.45f
        val commaW = kw10 * 0.9f
        val dotW = kw10 * 0.9f
        val row3KeyCount = if (showEmojiKey) 6 else 5
        val emojiSlotW = if (showEmojiKey) emojiKeyW else 0f
        val spaceW = (
            width - (sideKeyW + emojiSlotW + commaW + dotW + enterW + mh * (row3KeyCount * 2f))
        ).coerceAtLeast(kw10 * 2f)

        var idx = 29
        x = mh
        symbolKeys[idx++].bounds.set(x, y, x + sideKeyW, y + rowHeight - mv * 2)
        x += sideKeyW + mh * 2
        if (showEmojiKey) {
            symbolKeys[idx++].bounds.set(x, y, x + emojiKeyW, y + rowHeight - mv * 2)
            x += emojiKeyW + mh * 2
        }
        symbolKeys[idx++].bounds.set(x, y, x + commaW, y + rowHeight - mv * 2)
        x += commaW + mh * 2
        symbolKeys[idx++].bounds.set(x, y, x + spaceW, y + rowHeight - mv * 2)
        x += spaceW + mh * 2
        symbolKeys[idx++].bounds.set(x, y, x + dotW, y + rowHeight - mv * 2)
        x += dotW + mh * 2
        symbolKeys[idx].bounds.set(x, y, x + enterW, y + rowHeight - mv * 2)
    }

    private fun measureSymbolShift(width: Int, topOffset: Float, rowHeight: Float, mh: Float, mv: Float) {
        val kw10 = (width - mh * 20) / 10f
        var x = mh
        var y = topOffset + mv
        for (i in 0 until 10) {
            symbolShiftKeys[i].bounds.set(x, y, x + kw10, y + rowHeight - mv * 2)
            x += kw10 + mh * 2
        }
        x = mh
        y = topOffset + rowHeight + mv
        for (i in 10 until 20) {
            symbolShiftKeys[i].bounds.set(x, y, x + kw10, y + rowHeight - mv * 2)
            x += kw10 + mh * 2
        }

        val specialW = kw10 * 1.5f
        val middleW = (width - specialW * 2 - mh * 16) / 6f
        y = topOffset + rowHeight * 2 + mv
        x = mh
        symbolShiftKeys[20].bounds.set(x, y, x + specialW, y + rowHeight - mv * 2)
        x += specialW + mh * 2
        for (i in 21 until 27) {
            symbolShiftKeys[i].bounds.set(x, y, x + middleW, y + rowHeight - mv * 2)
            x += middleW + mh * 2
        }
        symbolShiftKeys[27].bounds.set(x, y, x + specialW, y + rowHeight - mv * 2)

        y = topOffset + rowHeight * 3 + mv
        val sideKeyW = kw10 * 1.35f
        val emojiKeyW = kw10 * 0.9f
        val enterW = kw10 * 1.45f
        val angleLW = kw10 * 0.9f
        val angleRW = kw10 * 0.9f
        val row3KeyCount = if (showEmojiKey) 6 else 5
        val emojiSlotW = if (showEmojiKey) emojiKeyW else 0f
        val spaceW = (
            width - (sideKeyW + emojiSlotW + angleLW + angleRW + enterW + mh * (row3KeyCount * 2f))
        ).coerceAtLeast(kw10 * 2f)

        var idx = 28
        x = mh
        symbolShiftKeys[idx++].bounds.set(x, y, x + sideKeyW, y + rowHeight - mv * 2)
        x += sideKeyW + mh * 2
        if (showEmojiKey) {
            symbolShiftKeys[idx++].bounds.set(x, y, x + emojiKeyW, y + rowHeight - mv * 2)
            x += emojiKeyW + mh * 2
        }
        symbolShiftKeys[idx++].bounds.set(x, y, x + angleLW, y + rowHeight - mv * 2)
        x += angleLW + mh * 2
        symbolShiftKeys[idx++].bounds.set(x, y, x + spaceW, y + rowHeight - mv * 2)
        x += spaceW + mh * 2
        symbolShiftKeys[idx++].bounds.set(x, y, x + angleRW, y + rowHeight - mv * 2)
        x += angleRW + mh * 2
        symbolShiftKeys[idx].bounds.set(x, y, x + enterW, y + rowHeight - mv * 2)
    }

    private fun measureNumpad(width: Int, topOffset: Float, rowHeight: Float, mh: Float, mv: Float) {
        val kw4 = (width - mh * 8) / 4f
        for (row in 0 until 4) {
            var x = mh
            val y = topOffset + row * rowHeight + mv
            for (col in 0 until 4) {
                val idx = row * 4 + col
                if (idx < numpadKeys.size) {
                    numpadKeys[idx].bounds.set(x, y, x + kw4, y + rowHeight - mv * 2)
                    x += kw4 + mh * 2
                }
            }
        }
    }

    /**
     * Zero-dead-zone hit resolution (100% zero-allocation).
     * Checks exact visual bounds first, then resolves gaps/margins to the nearest keycap edge.
     */
    fun findKeyAt(x: Float, y: Float): KeyModel? {
        val keys = getActiveKeys()
        if (keys.isEmpty()) return null
        if (y < lastTopOffset) return null
        if (lastWidth > 0 && (x < 0f || x > lastWidth.toFloat() || y > lastTopOffset + lastKeyAreaHeight)) {
            return null
        }

        // 1. Exact visual hit check
        for (i in keys.indices) {
            if (keys[i].contains(x, y)) {
                return keys[i]
            }
        }

        // 2. Nearest keycap edge fallback (eliminates gap & row-margin dead zones)
        var bestKey: KeyModel? = null
        var bestDistSq = Float.MAX_VALUE
        for (i in keys.indices) {
            val b = keys[i].bounds
            val cx = x.coerceIn(b.left, b.right)
            val cy = y.coerceIn(b.top, b.bottom)
            val dx = x - cx
            val dy = y - cy
            val distSq = dx * dx + dy * dy
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                bestKey = keys[i]
            }
        }
        return bestKey
    }

    fun updateShiftState(shifted: Boolean, caps: Boolean) {
        this.isShifted = shifted
        this.isCapsLock = caps
        val uppercase = shifted || caps

        val rows = activeLanguage.rows
        val shiftedRows = activeLanguage.shiftedRows

        var idx = numberRowCount
        for (i in 0 until r0Count) {
            alphaKeys[idx++].label = if (uppercase) {
                shiftedRows?.get(0)?.get(i) ?: rows[0][i].uppercase()
            } else {
                rows[0][i]
            }
        }
        for (i in 0 until r1Count) {
            alphaKeys[idx++].label = if (uppercase) {
                shiftedRows?.get(1)?.get(i) ?: rows[1][i].uppercase()
            } else {
                rows[1][i]
            }
        }
        idx++ // skip Shift key
        for (i in 0 until r2LetterCount) {
            alphaKeys[idx++].label = if (uppercase) {
                shiftedRows?.get(2)?.get(i) ?: rows[2][i].uppercase()
            } else {
                rows[2][i]
            }
        }
    }
}
