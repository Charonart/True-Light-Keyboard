package com.truelight.ime.engine

/**
 * Zero-allocation Vietnamese VNI Input Engine with raw-key tracking and
 * Vietnamese phonotactic auto-restore.
 *
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

        private const val MOD_NONE = 0
        private const val MOD_APPLIED = 1
        private const val MOD_REVERTED = 2
    }

    override val acceptsDigits: Boolean = true

    /** false = old style (hòa, thúy), true = modern style (hoà, thuý) */
    var modernTonePlacement: Boolean = false

    /** true = automatically restore raw keystrokes when word breaks Vietnamese phonotactics */
    var autoRestoreEnabled: Boolean = true

    private val buffer = CharArray(MAX_WORD_LENGTH)
    private var bufferLength = 0

    // Raw keystrokes recorded for the current composing word (for auto-restore)
    private val rawKeys = CharArray(MAX_WORD_LENGTH)
    private var rawLength = 0

    // Preallocated snapshot buffer for safe rollback on rejected VNI digits
    private val snapshotBuf = CharArray(MAX_WORD_LENGTH)

    private val vowelIndicesScratch = IntArray(8)
    private var currentTone = VietnameseCharMap.TONE_NONE
    private var lastToneKey: Char = '\u0000'

    private var hasTransformed: Boolean = false
    private var isRawRestored: Boolean = false

    private val cachedResult = InputEngine.StepResult()
    private val sb = StringBuilder(MAX_WORD_LENGTH)

    override fun processChar(c: Char): InputEngine.StepResult {
        if (c.isDigit()) {
            val canTryVni = bufferLength > 0 &&
                !isRawRestored &&
                rawLength < MAX_WORD_LENGTH - 2 &&
                (!autoRestoreEnabled ||
                    VietnamesePhonotactics.isValidVietnamesePrefix(
                        buffer, bufferLength, allowVniIntermediates = true
                    ))

            if (canTryVni) {
                // Save lightweight snapshot in case applying the digit violates Vietnamese phonotactics
                val savedLen = bufferLength
                val savedTone = currentTone
                val savedLastToneKey = lastToneKey
                for (i in 0 until savedLen) {
                    snapshotBuf[i] = buffer[i]
                }

                val modResult = handleVniDigit(c)
                if (modResult == MOD_APPLIED) {
                    if (autoRestoreEnabled &&
                        !VietnamesePhonotactics.isValidVietnamesePrefix(
                            buffer, bufferLength, allowVniIntermediates = true
                        )
                    ) {
                        // Roll back so the digit is not swallowed into an invalid syllable (e.g. "mat2")
                        for (i in 0 until savedLen) {
                            buffer[i] = snapshotBuf[i]
                        }
                        bufferLength = savedLen
                        currentTone = savedTone
                        lastToneKey = savedLastToneKey
                    } else {
                        rawKeys[rawLength++] = c
                        hasTransformed = true
                        return finishStep()
                    }
                } else if (modResult == MOD_REVERTED) {
                    rawKeys[rawLength++] = c
                    isRawRestored = true
                    return finishStep()
                }
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
        if (bufferLength >= MAX_WORD_LENGTH - 2 || rawLength >= MAX_WORD_LENGTH - 2) {
            cachedResult.consumed = false
            cachedResult.text = getComposingText()
            cachedResult.isWordTerminated = true
            reset()
            return cachedResult
        }

        rawKeys[rawLength++] = c

        if (isRawRestored) {
            appendChar(c)
            return finishStep()
        }

        appendChar(c)

        if (currentTone != VietnameseCharMap.TONE_NONE) {
            applyToneToBestVowel(currentTone)
        }

        maybeAutoRestore()
        return finishStep()
    }

    private fun handleVniDigit(digit: Char): Int {
        return when (digit) {
            '0' -> {
                if (currentTone != VietnameseCharMap.TONE_NONE) {
                    removeCurrentTone()
                    lastToneKey = '\u0000'
                    MOD_APPLIED
                } else {
                    MOD_NONE
                }
            }
            '1', '2', '3', '4', '5' -> {
                val tone = digit - '0'
                if (currentTone == tone && lastToneKey == digit) {
                    removeCurrentTone()
                    appendChar(digit)
                    lastToneKey = '\u0000'
                    MOD_REVERTED
                } else if (hasAnyVowel()) {
                    val ok = applyToneToBestVowel(tone)
                    if (ok) {
                        currentTone = tone
                        lastToneKey = digit
                        MOD_APPLIED
                    } else {
                        MOD_NONE
                    }
                } else {
                    MOD_NONE
                }
            }
            '6' -> applyCircumflex()
            '7' -> applyHorn()
            '8' -> applyBreve()
            '9' -> applyStrokeD()
            else -> MOD_NONE
        }
    }

    private fun applyCircumflex(): Int {
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
                    return MOD_APPLIED
                }
            } else if (row == 2 || row == 4 || row == 7) {
                val reverted = VietnameseCharMap.revertVowel(ch)
                if (reverted != null) {
                    buffer[i] = reverted
                    appendChar('6')
                    return MOD_REVERTED
                }
            }
        }
        return MOD_NONE
    }

    private fun applyHorn(): Int {
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
                        return MOD_APPLIED
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
                        return MOD_APPLIED
                    }
                } else if (prevRow == 10) {
                    val rFirstU = VietnameseCharMap.revertVowel(prevCh)
                    if (rFirstU != null) {
                        buffer[i - 1] = rFirstU
                        appendChar('7')
                        return MOD_REVERTED
                    }
                }
            }
            // Never transform 'u' inside onset 'qu' into 'ư'
            if (row == 9 && i > 0 && buffer[i - 1].lowercaseChar() == 'q') {
                continue
            }
            if (row == 6 || row == 9) {
                val transformed = VietnameseCharMap.transformVowel(ch, 'w')
                if (transformed != null) {
                    buffer[i] = transformed
                    if (currentTone != VietnameseCharMap.TONE_NONE) applyToneToBestVowel(currentTone)
                    return MOD_APPLIED
                }
            } else if (row == 8 || row == 10) {
                if (row == 8 && i > 0 && VietnameseCharMap.getVowelRow(buffer[i - 1]) == 10) {
                    val rU = VietnameseCharMap.revertVowel(buffer[i - 1])
                    val rO = VietnameseCharMap.revertVowel(ch)
                    if (rU != null && rO != null) {
                        buffer[i - 1] = rU
                        buffer[i] = rO
                        appendChar('7')
                        return MOD_REVERTED
                    }
                }
                val reverted = VietnameseCharMap.revertVowel(ch)
                if (reverted != null) {
                    buffer[i] = reverted
                    appendChar('7')
                    return MOD_REVERTED
                }
            }
        }
        return MOD_NONE
    }

    private fun applyBreve(): Int {
        for (i in bufferLength - 1 downTo 0) {
            val ch = buffer[i]
            val row = VietnameseCharMap.getVowelRow(ch)
            if (row == 0) {
                val transformed = VietnameseCharMap.transformVowel(ch, 'w')
                if (transformed != null) {
                    buffer[i] = transformed
                    if (currentTone != VietnameseCharMap.TONE_NONE) applyToneToBestVowel(currentTone)
                    return MOD_APPLIED
                }
            } else if (row == 1) {
                val reverted = VietnameseCharMap.revertVowel(ch)
                if (reverted != null) {
                    buffer[i] = reverted
                    appendChar('8')
                    return MOD_REVERTED
                }
            }
        }
        return MOD_NONE
    }

    private fun applyStrokeD(): Int {
        for (i in bufferLength - 1 downTo 0) {
            val ch = buffer[i]
            when (ch) {
                'd' -> {
                    buffer[i] = 'đ'
                    return MOD_APPLIED
                }
                'D' -> {
                    buffer[i] = 'Đ'
                    return MOD_APPLIED
                }
                'đ', 'Đ' -> {
                    buffer[i] = if (ch == 'đ') 'd' else 'D'
                    appendChar('9')
                    return MOD_REVERTED
                }
            }
        }
        return MOD_NONE
    }

    override fun handleBackspace(): Boolean {
        if (bufferLength == 0) return false
        val deletedChar = buffer[bufferLength - 1]
        bufferLength--
        lastToneKey = '\u0000'

        if (bufferLength == 0) {
            reset()
        } else if (isRawRestored) {
            rawLength = bufferLength
            for (i in 0 until bufferLength) {
                rawKeys[i] = buffer[i]
            }
        } else {
            removeLastMatchingRawKey(deletedChar)
            recalcCurrentTone()
            if (currentTone != VietnameseCharMap.TONE_NONE) {
                applyToneToBestVowel(currentTone)
            }
            recalcHasTransformed()
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
        rawLength = 0
        currentTone = VietnameseCharMap.TONE_NONE
        lastToneKey = '\u0000'
        hasTransformed = false
        isRawRestored = false
    }

    private fun finishStep(): InputEngine.StepResult {
        cachedResult.consumed = true
        cachedResult.text = getComposingText()
        cachedResult.isWordTerminated = false
        return cachedResult
    }

    private fun maybeAutoRestore() {
        if (!autoRestoreEnabled || !hasTransformed || isRawRestored) return
        if (!VietnamesePhonotactics.isValidVietnamesePrefix(
                buffer, bufferLength, allowVniIntermediates = true
            )
        ) {
            restoreRawKeys()
        }
    }

    private fun restoreRawKeys() {
        val len = rawLength.coerceAtMost(MAX_WORD_LENGTH)
        for (i in 0 until len) {
            buffer[i] = rawKeys[i]
        }
        bufferLength = len
        currentTone = VietnameseCharMap.TONE_NONE
        lastToneKey = '\u0000'
        isRawRestored = true
    }

    private fun removeLastMatchingRawKey(deletedChar: Char) {
        if (rawLength <= 0) return
        val targetBase = baseAsciiLower(deletedChar)
        for (i in rawLength - 1 downTo 0) {
            if (rawKeys[i].lowercaseChar() == targetBase) {
                for (j in i until rawLength - 1) {
                    rawKeys[j] = rawKeys[j + 1]
                }
                rawLength--
                return
            }
        }
        rawLength--
    }

    private fun baseAsciiLower(c: Char): Char {
        if (c == 'đ' || c == 'Đ') return 'd'
        val row = VietnameseCharMap.getVowelRow(c)
        return when (row) {
            0, 1, 2 -> 'a'
            3, 4 -> 'e'
            5 -> 'i'
            6, 7, 8 -> 'o'
            9, 10 -> 'u'
            11 -> 'y'
            else -> c.lowercaseChar()
        }
    }

    private fun recalcHasTransformed() {
        hasTransformed = false
        for (i in 0 until bufferLength) {
            val ch = buffer[i]
            if (ch == 'đ' || ch == 'Đ') {
                hasTransformed = true
                return
            }
            if (VietnameseCharMap.getCharTone(ch) != VietnameseCharMap.TONE_NONE) {
                hasTransformed = true
                return
            }
            val row = VietnameseCharMap.getVowelRow(ch)
            if (row == 1 || row == 2 || row == 4 || row == 7 || row == 8 || row == 10) {
                hasTransformed = true
                return
            }
        }
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
