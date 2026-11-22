package com.truelight.ime.ui

import com.truelight.ime.ui.theme.KeyboardTheme

/**
 * Preallocated Key Layout Engine.
 * Supports QWERTY, Symbols, Symbols-Shift, and Numpad.
 * Zero-allocation during runtime layout switching or rendering.
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
    var isVietnamese = true // Telex vs EN

    // QWERTY Keys
    val qwertyRows = arrayOf(
        arrayOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        arrayOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        arrayOf("z", "x", "c", "v", "b", "n", "m")
    )
    val qwertySubLabels = arrayOf(
        arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        arrayOf("@", "#", "$", "%", "&", "-", "+", "(", ")"),
        arrayOf("*", "\"", "'", ":", ";", "!", "?")
    )

    // Preallocated key arrays
    val alphaKeys = ArrayList<KeyModel>()
    val symbolKeys = ArrayList<KeyModel>()
    val symbolShiftKeys = ArrayList<KeyModel>()
    val numpadKeys = ArrayList<KeyModel>()

    init {
        buildAlphaKeys()
        buildSymbolKeys()
        buildSymbolShiftKeys()
        buildNumpadKeys()
    }

    private fun buildAlphaKeys() {
        alphaKeys.clear()
        // Row 0
        for (i in qwertyRows[0].indices) {
            val key = KeyModel(
                code = qwertyRows[0][i][0].code,
                label = qwertyRows[0][i],
                subLabel = qwertySubLabels[0][i],
                type = KeyModel.TYPE_NORMAL
            )
            alphaKeys.add(key)
        }

        // Row 1
        for (i in qwertyRows[1].indices) {
            val key = KeyModel(
                code = qwertyRows[1][i][0].code,
                label = qwertyRows[1][i],
                subLabel = qwertySubLabels[1][i],
                type = KeyModel.TYPE_NORMAL
            )
            alphaKeys.add(key)
        }

        // Row 2: Shift, z..m, Backspace
        val shiftKey = KeyModel(code = KeyModel.CODE_SHIFT, label = "⇧", type = KeyModel.TYPE_SHIFT)
        alphaKeys.add(shiftKey)

        for (i in qwertyRows[2].indices) {
            val key = KeyModel(
                code = qwertyRows[2][i][0].code,
                label = qwertyRows[2][i],
                subLabel = qwertySubLabels[2][i],
                type = KeyModel.TYPE_NORMAL
            )
            alphaKeys.add(key)
        }

        val delKey = KeyModel(code = KeyModel.CODE_DELETE, label = "⌫", type = KeyModel.TYPE_DELETE)
        alphaKeys.add(delKey)

        // Row 3: ?123, Emoji, Lang, Space, ., Enter
        alphaKeys.add(KeyModel(code = KeyModel.CODE_SWITCH_SYMBOLS, label = "?123", type = KeyModel.TYPE_SWITCH_SYMBOLS))
        alphaKeys.add(KeyModel(code = KeyModel.CODE_EMOJI, label = "😊", type = KeyModel.TYPE_EMOJI))
        alphaKeys.add(KeyModel(code = KeyModel.CODE_SWITCH_LANG, label = "VIE", type = KeyModel.TYPE_SWITCH_LANG))
        alphaKeys.add(KeyModel(code = KeyModel.CODE_SPACE, label = "Dấu cách", type = KeyModel.TYPE_SPACE))
        alphaKeys.add(KeyModel(code = '.'.code, label = ".", type = KeyModel.TYPE_NORMAL))
        alphaKeys.add(KeyModel(code = KeyModel.CODE_ENTER, label = "↵", type = KeyModel.TYPE_ENTER))
    }

    private fun buildSymbolKeys() {
        symbolKeys.clear()
        val r0 = arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        for (s in r0) symbolKeys.add(KeyModel(code = s[0].code, label = s, type = KeyModel.TYPE_NORMAL))

        val r1 = arrayOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/")
        for (s in r1) symbolKeys.add(KeyModel(code = s[0].code, label = s, type = KeyModel.TYPE_NORMAL))

        // Row 2: =\<, *, ", ', :, ;, !, ?, Backspace
        symbolKeys.add(KeyModel(code = KeyModel.CODE_SHIFT, label = "=\\<", type = KeyModel.TYPE_SHIFT))
        val r2 = arrayOf("*", "\"", "'", ":", ";", "!", "?")
        for (s in r2) symbolKeys.add(KeyModel(code = s[0].code, label = s, type = KeyModel.TYPE_NORMAL))
        symbolKeys.add(KeyModel(code = KeyModel.CODE_DELETE, label = "⌫", type = KeyModel.TYPE_DELETE))

        // Row 3: ABC, Emoji, ,, Space, ., Enter
        symbolKeys.add(KeyModel(code = KeyModel.CODE_SWITCH_ALPHA, label = "ABC", type = KeyModel.TYPE_SWITCH_ALPHA))
        symbolKeys.add(KeyModel(code = KeyModel.CODE_EMOJI, label = "😊", type = KeyModel.TYPE_EMOJI))
        symbolKeys.add(KeyModel(code = ','.code, label = ",", type = KeyModel.TYPE_NORMAL))
        symbolKeys.add(KeyModel(code = KeyModel.CODE_SPACE, label = "Dấu cách", type = KeyModel.TYPE_SPACE))
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
        symbolShiftKeys.add(KeyModel(code = KeyModel.CODE_EMOJI, label = "😊", type = KeyModel.TYPE_EMOJI))
        symbolShiftKeys.add(KeyModel(code = '<'.code, label = "<", type = KeyModel.TYPE_NORMAL))
        symbolShiftKeys.add(KeyModel(code = KeyModel.CODE_SPACE, label = "Dấu cách", type = KeyModel.TYPE_SPACE))
        symbolShiftKeys.add(KeyModel(code = '>'.code, label = ">", type = KeyModel.TYPE_NORMAL))
        symbolShiftKeys.add(KeyModel(code = KeyModel.CODE_ENTER, label = "↵", type = KeyModel.TYPE_ENTER))
    }

    private fun buildNumpadKeys() {
        numpadKeys.clear()
        // Row 0: 1, 2, 3, Del
        numpadKeys.add(KeyModel(code = '1'.code, label = "1", type = KeyModel.TYPE_NORMAL))
        numpadKeys.add(KeyModel(code = '2'.code, label = "2", type = KeyModel.TYPE_NORMAL))
        numpadKeys.add(KeyModel(code = '3'.code, label = "3", type = KeyModel.TYPE_NORMAL))
        numpadKeys.add(KeyModel(code = KeyModel.CODE_DELETE, label = "⌫", type = KeyModel.TYPE_DELETE))

        // Row 1: 4, 5, 6, -
        numpadKeys.add(KeyModel(code = '4'.code, label = "4", type = KeyModel.TYPE_NORMAL))
        numpadKeys.add(KeyModel(code = '5'.code, label = "5", type = KeyModel.TYPE_NORMAL))
        numpadKeys.add(KeyModel(code = '6'.code, label = "6", type = KeyModel.TYPE_NORMAL))
        numpadKeys.add(KeyModel(code = '-'.code, label = "-", type = KeyModel.TYPE_NORMAL))

        // Row 2: 7, 8, 9, +
        numpadKeys.add(KeyModel(code = '7'.code, label = "7", type = KeyModel.TYPE_NORMAL))
        numpadKeys.add(KeyModel(code = '8'.code, label = "8", type = KeyModel.TYPE_NORMAL))
        numpadKeys.add(KeyModel(code = '9'.code, label = "9", type = KeyModel.TYPE_NORMAL))
        numpadKeys.add(KeyModel(code = '+'.code, label = "+", type = KeyModel.TYPE_NORMAL))

        // Row 3: ABC, 0, ., Enter
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

    /**
     * Compute and cache bounding boxes for all keys on resize.
     * Zero-allocation in onDraw.
     */
    fun measure(width: Int, height: Int) {
        val rowHeight = height / 4f
        val mh = theme.keyMarginH
        val mv = theme.keyMarginV

        measureAlpha(width, rowHeight, mh, mv)
        measureSymbols(width, rowHeight, mh, mv)
        measureSymbolShift(width, rowHeight, mh, mv)
        measureNumpad(width, rowHeight, mh, mv)
    }

    private fun measureAlpha(width: Int, rowHeight: Float, mh: Float, mv: Float) {
        // Row 0: 10 keys
        val kw10 = (width - mh * 20) / 10f
        var x = mh
        var y = mv
        for (i in 0 until 10) {
            alphaKeys[i].bounds.set(x, y, x + kw10, y + rowHeight - mv * 2)
            x += kw10 + mh * 2
        }

        // Row 1: 9 keys (centered)
        val kw9 = kw10
        val offsetR1 = (width - (kw9 * 9 + mh * 18)) / 2f
        x = offsetR1 + mh
        y = rowHeight + mv
        for (i in 10 until 19) {
            alphaKeys[i].bounds.set(x, y, x + kw9, y + rowHeight - mv * 2)
            x += kw9 + mh * 2
        }

        // Row 2: Shift (1.4x), 7 keys, Backspace (1.4x)
        val specialW = kw10 * 1.4f
        val middleW = (width - specialW * 2 - mh * 18) / 7f
        y = rowHeight * 2 + mv
        x = mh
        alphaKeys[19].bounds.set(x, y, x + specialW, y + rowHeight - mv * 2) // Shift
        x += specialW + mh * 2
        for (i in 20 until 27) {
            alphaKeys[i].bounds.set(x, y, x + middleW, y + rowHeight - mv * 2)
            x += middleW + mh * 2
        }
        alphaKeys[27].bounds.set(x, y, x + specialW, y + rowHeight - mv * 2) // Del

        // Row 3: ?123, Emoji, Lang, Space, ., Enter
        y = rowHeight * 3 + mv
        val sideKeyW = kw10 * 1.3f
        val smallKeyW = kw10 * 0.9f
        val enterW = kw10 * 1.4f
        val dotW = kw10 * 0.8f
        val spaceW = width - (sideKeyW + smallKeyW + smallKeyW + dotW + enterW + mh * 12)

        x = mh
        alphaKeys[28].bounds.set(x, y, x + sideKeyW, y + rowHeight - mv * 2) // ?123
        x += sideKeyW + mh * 2
        alphaKeys[29].bounds.set(x, y, x + smallKeyW, y + rowHeight - mv * 2) // Emoji
        x += smallKeyW + mh * 2
        alphaKeys[30].bounds.set(x, y, x + smallKeyW, y + rowHeight - mv * 2) // Lang
        x += smallKeyW + mh * 2
        alphaKeys[31].bounds.set(x, y, x + spaceW, y + rowHeight - mv * 2) // Space
        x += spaceW + mh * 2
        alphaKeys[32].bounds.set(x, y, x + dotW, y + rowHeight - mv * 2) // .
        x += dotW + mh * 2
        alphaKeys[33].bounds.set(x, y, x + enterW, y + rowHeight - mv * 2) // Enter
    }

    private fun measureSymbols(width: Int, rowHeight: Float, mh: Float, mv: Float) {
        val kw10 = (width - mh * 20) / 10f
        var x = mh
        var y = mv
        for (i in 0 until 10) {
            symbolKeys[i].bounds.set(x, y, x + kw10, y + rowHeight - mv * 2)
            x += kw10 + mh * 2
        }
        x = mh
        y = rowHeight + mv
        for (i in 10 until 20) {
            symbolKeys[i].bounds.set(x, y, x + kw10, y + rowHeight - mv * 2)
            x += kw10 + mh * 2
        }

        // Row 2: Shift/More (1.4x), 7 keys, Backspace (1.4x)
        val specialW = kw10 * 1.4f
        val middleW = (width - specialW * 2 - mh * 18) / 7f
        y = rowHeight * 2 + mv
        x = mh
        symbolKeys[20].bounds.set(x, y, x + specialW, y + rowHeight - mv * 2)
        x += specialW + mh * 2
        for (i in 21 until 28) {
            symbolKeys[i].bounds.set(x, y, x + middleW, y + rowHeight - mv * 2)
            x += middleW + mh * 2
        }
        symbolKeys[28].bounds.set(x, y, x + specialW, y + rowHeight - mv * 2)

        // Row 3: ABC, Emoji, ,, Space, ., Enter
        y = rowHeight * 3 + mv
        val sideKeyW = kw10 * 1.4f
        val smallKeyW = kw10 * 0.9f
        val enterW = kw10 * 1.4f
        val commaW = kw10 * 0.9f
        val dotW = kw10 * 0.9f
        val spaceW = width - (sideKeyW + smallKeyW + commaW + dotW + enterW + mh * 12)

        x = mh
        symbolKeys[29].bounds.set(x, y, x + sideKeyW, y + rowHeight - mv * 2) // ABC
        x += sideKeyW + mh * 2
        symbolKeys[30].bounds.set(x, y, x + smallKeyW, y + rowHeight - mv * 2) // Emoji
        x += smallKeyW + mh * 2
        symbolKeys[31].bounds.set(x, y, x + commaW, y + rowHeight - mv * 2) // ,
        x += commaW + mh * 2
        symbolKeys[32].bounds.set(x, y, x + spaceW, y + rowHeight - mv * 2) // Space
        x += spaceW + mh * 2
        symbolKeys[33].bounds.set(x, y, x + dotW, y + rowHeight - mv * 2) // .
        x += dotW + mh * 2
        symbolKeys[34].bounds.set(x, y, x + enterW, y + rowHeight - mv * 2) // Enter
    }

    private fun measureSymbolShift(width: Int, rowHeight: Float, mh: Float, mv: Float) {
        val kw10 = (width - mh * 20) / 10f
        var x = mh
        var y = mv
        for (i in 0 until 10) {
            symbolShiftKeys[i].bounds.set(x, y, x + kw10, y + rowHeight - mv * 2)
            x += kw10 + mh * 2
        }
        x = mh
        y = rowHeight + mv
        for (i in 10 until 20) {
            symbolShiftKeys[i].bounds.set(x, y, x + kw10, y + rowHeight - mv * 2)
            x += kw10 + mh * 2
        }

        // Row 2: Shift/123 (1.5x), 6 keys, Backspace (1.5x)
        val specialW = kw10 * 1.5f
        val middleW = (width - specialW * 2 - mh * 16) / 6f
        y = rowHeight * 2 + mv
        x = mh
        symbolShiftKeys[20].bounds.set(x, y, x + specialW, y + rowHeight - mv * 2)
        x += specialW + mh * 2
        for (i in 21 until 27) {
            symbolShiftKeys[i].bounds.set(x, y, x + middleW, y + rowHeight - mv * 2)
            x += middleW + mh * 2
        }
        symbolShiftKeys[27].bounds.set(x, y, x + specialW, y + rowHeight - mv * 2)

        // Row 3
        y = rowHeight * 3 + mv
        val sideKeyW = kw10 * 1.4f
        val smallKeyW = kw10 * 0.9f
        val enterW = kw10 * 1.4f
        val angleLW = kw10 * 0.9f
        val angleRW = kw10 * 0.9f
        val spaceW = width - (sideKeyW + smallKeyW + angleLW + angleRW + enterW + mh * 12)

        x = mh
        symbolShiftKeys[28].bounds.set(x, y, x + sideKeyW, y + rowHeight - mv * 2)
        x += sideKeyW + mh * 2
        symbolShiftKeys[29].bounds.set(x, y, x + smallKeyW, y + rowHeight - mv * 2)
        x += smallKeyW + mh * 2
        symbolShiftKeys[30].bounds.set(x, y, x + angleLW, y + rowHeight - mv * 2)
        x += angleLW + mh * 2
        symbolShiftKeys[31].bounds.set(x, y, x + spaceW, y + rowHeight - mv * 2)
        x += spaceW + mh * 2
        symbolShiftKeys[32].bounds.set(x, y, x + angleRW, y + rowHeight - mv * 2)
        x += angleRW + mh * 2
        symbolShiftKeys[33].bounds.set(x, y, x + enterW, y + rowHeight - mv * 2)
    }

    private fun measureNumpad(width: Int, rowHeight: Float, mh: Float, mv: Float) {
        val kw4 = (width - mh * 8) / 4f
        for (row in 0 until 4) {
            var x = mh
            val y = row * rowHeight + mv
            for (col in 0 until 4) {
                val idx = row * 4 + col
                if (idx < numpadKeys.size) {
                    numpadKeys[idx].bounds.set(x, y, x + kw4, y + rowHeight - mv * 2)
                    x += kw4 + mh * 2
                }
            }
        }
    }

    fun findKeyAt(x: Float, y: Float): KeyModel? {
        val keys = getActiveKeys()
        for (i in keys.indices) {
            if (keys[i].contains(x, y)) {
                return keys[i]
            }
        }
        return null
    }

    fun updateShiftState(shifted: Boolean, caps: Boolean) {
        this.isShifted = shifted
        this.isCapsLock = caps
        val uppercase = shifted || caps

        // Update labels for letter keys
        for (i in 0 until 10) {
            alphaKeys[i].label = if (uppercase) qwertyRows[0][i].uppercase() else qwertyRows[0][i]
        }
        for (i in 10 until 19) {
            alphaKeys[i].label = if (uppercase) qwertyRows[1][i - 10].uppercase() else qwertyRows[1][i - 10]
        }
        for (i in 20 until 27) {
            alphaKeys[i].label = if (uppercase) qwertyRows[2][i - 20].uppercase() else qwertyRows[2][i - 20]
        }
    }
}
