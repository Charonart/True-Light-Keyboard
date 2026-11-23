package com.truelight.ime.engine

/**
 * Zero-allocation Vietnamese VNI Input Engine.
 * VNI keys:
 *  1 = sắc, 2 = huyền, 3 = hỏi, 4 = ngã, 5 = nặng
 *  6 = mũ (a->â, e->ê, o->ô)
 *  7 = móc (o->ơ, u->ư, uo->ươ, uu->ưu)
 *  8 = trăng (a->ă)
 *  9 = gạch (d->đ)
 *  0 = xóa dấu thanh
 */
class VniFsmEngine : InputEngine {

    companion object {
        private const val MAX_WORD_LENGTH = 64
    }

    override val acceptsDigits: Boolean = true

    /** false = old style (hòa, thúy), true = modern style (hoà, thuý) */
    var modernTonePlacement: Boolean = false

    private val buffer = CharArray(MAX_WORD_LENGTH)
    private var bufferLength = 0
    private val vowelIndicesScratch = IntArray(8)
    private var currentTone = VietnameseCharMap.TONE_NONE
    private var lastToneKey: Char = '\u0000'

    private val cachedResult = InputEngine.StepResult()
    private val sb = StringBuilder(MAX_WORD_LENGTH)

    override fun processChar(c: Char): InputEngine.StepResult {
        if (c.isDigit()) {
            if (bufferLength > 0 && handleVniDigit(c)) {
                cachedResult.consumed = true
                cachedResult.text = getComposingText()
                cachedResult.isWordTerminated = false
                return cachedResult
            }
            // Digit was not consumed as VNI modifier -> terminate word so caller commits digit
            cachedResult.consumed = false
            cachedResult.text = getComposingText()
            cachedResult.isWordTerminated = true
            reset()
            return cachedResult
        }

        if (!c.isLetter()) {
            cachedResult.consumed = false
            cachedResult.text = getComposingText()
            cachedResult.isWordTerminated = true
            reset()
            return cachedResult
        }

        // Prevent buffer overflow: cleanly terminate chunk so IME commits it & starts fresh token
        if (bufferLength >= MAX_WORD_LENGTH - 2) {
            cachedResult.consumed = false
            cachedResult.text = getComposingText()
            cachedResult.isWordTerminated = true
            reset()
            return cachedResult
        }

        appendChar(c)

        if (currentTone != VietnameseCharMap.TONE_NONE) {
            applyToneToBestVowel(currentTone)
        }

        cachedResult.consumed = true
        cachedResult.text = getComposingText()
        cachedResult.isWordTerminated = false
        return cachedResult
    }

    private fun handleVniDigit(digit: Char): Boolean {
        return when (digit) {
            '0' -> {
                if (currentTone != VietnameseCharMap.TONE_NONE) {
                    removeCurrentTone()
                    lastToneKey = '\u0000'
                    true
                } else {
                    false
                }
            }
            '1', '2', '3', '4', '5' -> {
                val tone = digit - '0'
                if (currentTone == tone && lastToneKey == digit) {
                    removeCurrentTone()
                    appendChar(digit)
                    lastToneKey = '\u0000'
                    true
                } else if (hasAnyVowel()) {
                    val ok = applyToneToBestVowel(tone)
                    if (ok) {
                        currentTone = tone
                        lastToneKey = digit
                    }
                    ok
                } else {
                    false
                }
            }
            '6' -> applyCircumflex()
            '7' -> applyHorn()
            '8' -> applyBreve()
            '9' -> applyStrokeD()
            else -> false
        }
    }

    private fun applyCircumflex(): Boolean {
        for (i in bufferLength - 1 downTo 0) {
            val ch = buffer[i]
            val row = VietnameseCharMap.getVowelRow(ch)
            val transformKey = when (row) {
                0 -> 'a'
                3 -> 'e'
                6 -> 'o'
                else -> null
            }
            if (transformKey != null) {
                val transformed = VietnameseCharMap.transformVowel(ch, transformKey)
                if (transformed != null) {
                    buffer[i] = transformed
                    if (currentTone != VietnameseCharMap.TONE_NONE) applyToneToBestVowel(currentTone)
                    return true
                }
            } else if (row == 2 || row == 4 || row == 7) {
                val reverted = VietnameseCharMap.revertVowel(ch)
                if (reverted != null) {
                    buffer[i] = reverted
                    appendChar('6')
                    return true
                }
            }
        }
        return false
    }

    private fun applyHorn(): Boolean {
        for (i in bufferLength - 1 downTo 0) {
            val ch = buffer[i]
            val row = VietnameseCharMap.getVowelRow(ch)
            if (row == 6 && i > 0) {
                val prevCh = buffer[i - 1]
                val isAfterQ = i >= 2 && buffer[i - 2].lowercaseChar() == 'q'
                if (!isAfterQ && VietnameseCharMap.getVowelRow(prevCh) == 9) {
                    val tU = VietnameseCharMap.transformVowel(prevCh, 'w')
                    val tO = VietnameseCharMap.transformVowel(ch, 'w')
                    if (tU != null && tO != null) {
                        buffer[i - 1] = tU
                        buffer[i] = tO
                        if (currentTone != VietnameseCharMap.TONE_NONE) applyToneToBestVowel(currentTone)
                        return true
                    }
                }
            }
            // Handle compound 'uu' + '7' -> 'ưu' (lưu, hưu, cứu) and revert 'ưu' + '7' -> 'uu7'
            if (row == 9 && i > 0) {
                val prevCh = buffer[i - 1]
                val prevRow = VietnameseCharMap.getVowelRow(prevCh)
                val isAfterQ = i >= 2 && buffer[i - 2].lowercaseChar() == 'q'
                if (!isAfterQ && prevRow == 9) {
                    val tFirstU = VietnameseCharMap.transformVowel(prevCh, 'w')
                    if (tFirstU != null) {
                        buffer[i - 1] = tFirstU
                        if (currentTone != VietnameseCharMap.TONE_NONE) applyToneToBestVowel(currentTone)
                        return true
                    }
                } else if (prevRow == 10) {
                    val rFirstU = VietnameseCharMap.revertVowel(prevCh)
                    if (rFirstU != null) {
                        buffer[i - 1] = rFirstU
                        appendChar('7')
                        return true
                    }
                }
            }
            if (row == 6 || row == 9) {
                val transformed = VietnameseCharMap.transformVowel(ch, 'w')
                if (transformed != null) {
                    buffer[i] = transformed
                    if (currentTone != VietnameseCharMap.TONE_NONE) applyToneToBestVowel(currentTone)
                    return true
                }
            } else if (row == 8 || row == 10) {
                if (row == 8 && i > 0 && VietnameseCharMap.getVowelRow(buffer[i - 1]) == 10) {
                    val rU = VietnameseCharMap.revertVowel(buffer[i - 1])
                    val rO = VietnameseCharMap.revertVowel(ch)
                    if (rU != null && rO != null) {
                        buffer[i - 1] = rU
                        buffer[i] = rO
                        appendChar('7')
                        return true
                    }
                }
                val reverted = VietnameseCharMap.revertVowel(ch)
                if (reverted != null) {
                    buffer[i] = reverted
                    appendChar('7')
                    return true
                }
            }
        }
        return false
    }

    private fun applyBreve(): Boolean {
        for (i in bufferLength - 1 downTo 0) {
            val ch = buffer[i]
            val row = VietnameseCharMap.getVowelRow(ch)
            if (row == 0) {
                val transformed = VietnameseCharMap.transformVowel(ch, 'w')
                if (transformed != null) {
                    buffer[i] = transformed
                    if (currentTone != VietnameseCharMap.TONE_NONE) applyToneToBestVowel(currentTone)
                    return true
                }
            } else if (row == 1) {
                val reverted = VietnameseCharMap.revertVowel(ch)
                if (reverted != null) {
                    buffer[i] = reverted
                    appendChar('8')
                    return true
                }
            }
        }
        return false
    }

    private fun applyStrokeD(): Boolean {
        for (i in bufferLength - 1 downTo 0) {
            val ch = buffer[i]
            when (ch) {
                'd' -> {
                    buffer[i] = 'đ'
                    return true
                }
                'D' -> {
                    buffer[i] = 'Đ'
                    return true
                }
                'đ', 'Đ' -> {
                    buffer[i] = if (ch == 'đ') 'd' else 'D'
                    appendChar('9')
                    return true
                }
            }
        }
        return false
    }

    override fun handleBackspace(): Boolean {
        if (bufferLength == 0) return false
        bufferLength--
        lastToneKey = '\u0000'
        recalcCurrentTone()
        if (currentTone != VietnameseCharMap.TONE_NONE) {
            applyToneToBestVowel(currentTone)
        }
        cachedResult.consumed = true
        cachedResult.text = getComposingText()
        cachedResult.isWordTerminated = false
        return true
    }

    override fun getComposingText(): String {
        sb.setLength(0)
        for (i in 0 until bufferLength) {
            sb.append(buffer[i])
        }
        return sb.toString()
    }

    override fun length(): Int = bufferLength

    override fun isEmpty(): Boolean = bufferLength == 0

    override fun reset() {
        bufferLength = 0
        currentTone = VietnameseCharMap.TONE_NONE
        lastToneKey = '\u0000'
    }

    private fun appendChar(c: Char) {
        if (bufferLength < MAX_WORD_LENGTH) {
            buffer[bufferLength++] = c
        }
    }

    private fun hasAnyVowel(): Boolean {
        for (i in 0 until bufferLength) {
            if (VietnameseCharMap.isVowel(buffer[i])) return true
        }
        return false
    }

    private fun recalcCurrentTone() {
        currentTone = VietnameseCharMap.TONE_NONE
        for (i in 0 until bufferLength) {
            val tone = VietnameseCharMap.getCharTone(buffer[i])
            if (tone != VietnameseCharMap.TONE_NONE) {
                currentTone = tone
                return
            }
        }
    }

    private fun removeCurrentTone() {
        for (i in 0 until bufferLength) {
            if (VietnameseCharMap.isVowel(buffer[i])) {
                buffer[i] = VietnameseCharMap.applyTone(buffer[i], VietnameseCharMap.TONE_NONE)
            }
        }
        currentTone = VietnameseCharMap.TONE_NONE
    }

    private fun applyToneToBestVowel(newTone: Int): Boolean {
        val targetIndex = findToneTargetIndex()
        if (targetIndex < 0) return false
        removeCurrentTone()
        buffer[targetIndex] = VietnameseCharMap.applyTone(buffer[targetIndex], newTone)
        currentTone = newTone
        return true
    }

    private fun findToneTargetIndex(): Int {
        var vowelCount = 0
        for (i in 0 until bufferLength) {
            if (VietnameseCharMap.isVowel(buffer[i])) {
                if (i > 0) {
                    val prev = buffer[i - 1].lowercaseChar()
                    val currRow = VietnameseCharMap.getVowelRow(buffer[i])
                    if (prev == 'q' && currRow == 9 && i + 1 < bufferLength && VietnameseCharMap.isVowel(buffer[i + 1])) continue
                    if (prev == 'g' && currRow == 5 && i + 1 < bufferLength && VietnameseCharMap.isVowel(buffer[i + 1])) continue
                }
                if (vowelCount < vowelIndicesScratch.size) {
                    vowelIndicesScratch[vowelCount++] = i
                }
            }
        }
        if (vowelCount == 0) return -1
        if (vowelCount == 1) return vowelIndicesScratch[0]

        for (k in vowelCount - 1 downTo 0) {
            val idx = vowelIndicesScratch[k]
            val row = VietnameseCharMap.getVowelRow(buffer[idx])
            if (row == 1 || row == 2 || row == 4 || row == 7 || row == 8) return idx
        }

        val lastVowelIdx = vowelIndicesScratch[vowelCount - 1]
        val hasTrailingConsonant = lastVowelIdx < bufferLength - 1

        if (vowelCount == 2) {
            if (hasTrailingConsonant) return vowelIndicesScratch[1]
            if (modernTonePlacement) {
                val r1 = VietnameseCharMap.getVowelRow(buffer[vowelIndicesScratch[0]])
                val r2 = VietnameseCharMap.getVowelRow(buffer[vowelIndicesScratch[1]])
                // oa, oe, uy -> modern style places tone on 2nd vowel (hoà, hoè, thuý)
                if ((r1 == 6 && r2 == 0) || (r1 == 6 && r2 == 3) || (r1 == 9 && r2 == 11)) {
                    return vowelIndicesScratch[1]
                }
            }
            return vowelIndicesScratch[0]
        }
        return vowelIndicesScratch[1]
    }
}
