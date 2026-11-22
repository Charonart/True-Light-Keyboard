package com.truelight.ime.engine

/**
 * High-performance, zero-allocation Finite State Machine (FSM)
 * for Vietnamese Telex typing.
 */
class TelexFsmEngine {

    companion object {
        private const val MAX_WORD_LENGTH = 64
    }

    // Composing word buffer - preallocated CharArray
    private val buffer = CharArray(MAX_WORD_LENGTH)
    private var bufferLength = 0

    // History tracking for reversible states
    private val rawKeyHistory = CharArray(MAX_WORD_LENGTH)
    private var rawHistoryLength = 0

    // Preallocated vowel index scratch array for zero-allocation tone placement
    private val vowelIndicesScratch = IntArray(8)

    // Currently active tone mark (0: none, 1: sac, 2: huyen, 3: hoi, 4: nga, 5: nang)
    private var currentTone = VietnameseCharMap.TONE_NONE
    private var lastToneKey: Char = '\u0000'

    // Result carrier to avoid allocation during typing
    class StepResult {
        var consumed: Boolean = false
        var text: String = ""
        var isWordTerminated: Boolean = false
    }

    private val cachedResult = StepResult()
    private val stringBuilder = StringBuilder(MAX_WORD_LENGTH)

    /**
     * Process incoming character.
     * Returns cached StepResult with updated composing text.
     */
    fun processChar(c: Char): StepResult {
        // If not a letter, finalize word
        if (!c.isLetter()) {
            cachedResult.consumed = false
            cachedResult.text = getComposingText()
            cachedResult.isWordTerminated = true
            reset()
            return cachedResult
        }

        if (bufferLength >= MAX_WORD_LENGTH - 2) {
            // Buffer full, reset
            reset()
        }

        val lower = c.lowercaseChar()

        // 1. Check for 'z' - remove tone
        if (lower == 'z' && currentTone != VietnameseCharMap.TONE_NONE) {
            removeCurrentTone()
            recordRawKey(c)
            lastToneKey = '\u0000'
            cachedResult.consumed = true
            cachedResult.text = getComposingText()
            cachedResult.isWordTerminated = false
            return cachedResult
        }

        // 2. Check for tone keys ('s', 'f', 'r', 'x', 'j')
        if (VietnameseCharMap.isToneKey(c)) {
            val tone = VietnameseCharMap.getToneFromKey(c)
            if (currentTone == tone && lastToneKey == lower) {
                // Revert tone and append raw key (e.g. toas -> toá -> toas)
                removeCurrentTone()
                appendChar(c)
                recordRawKey(c)
                lastToneKey = '\u0000'
                cachedResult.consumed = true
                cachedResult.text = getComposingText()
                cachedResult.isWordTerminated = false
                return cachedResult
            } else if (hasAnyVowel()) {
                val success = applyToneToBestVowel(tone)
                if (success) {
                    currentTone = tone
                    lastToneKey = lower
                    recordRawKey(c)
                    cachedResult.consumed = true
                    cachedResult.text = getComposingText()
                    cachedResult.isWordTerminated = false
                    return cachedResult
                }
            }
        }

        // 3. Check for vowel/consonant modifier ('a', 'w', 'e', 'o', 'd')
        if (VietnameseCharMap.isModifyKey(c)) {
            val handled = handleModifierKey(c)
            if (handled) {
                if (currentTone != VietnameseCharMap.TONE_NONE) {
                    applyToneToBestVowel(currentTone)
                }
                recordRawKey(c)
                lastToneKey = '\u0000'
                cachedResult.consumed = true
                cachedResult.text = getComposingText()
                cachedResult.isWordTerminated = false
                return cachedResult
            }
        }

        // 4. Default: append character directly
        appendChar(c)
        recordRawKey(c)
        lastToneKey = '\u0000'

        // Re-position active tone when syllable structure changes (e.g. hoafn -> hoàn, chuoosi -> chuối)
        if (currentTone != VietnameseCharMap.TONE_NONE) {
            applyToneToBestVowel(currentTone)
        }

        cachedResult.consumed = true
        cachedResult.text = getComposingText()
        cachedResult.isWordTerminated = false
        return cachedResult
    }

    /**
     * Handle backspace event. Returns true if consumed, false if buffer was empty.
     */
    fun handleBackspace(): Boolean {
        if (bufferLength == 0) return false

        bufferLength--
        if (rawHistoryLength > 0) {
            rawHistoryLength--
        }

        // Re-evaluate tone from remaining characters
        recalcCurrentTone()
        if (currentTone != VietnameseCharMap.TONE_NONE) {
            applyToneToBestVowel(currentTone)
        }

        cachedResult.consumed = true
        cachedResult.text = getComposingText()
        cachedResult.isWordTerminated = false
        return true
    }

    fun getComposingText(): String {
        stringBuilder.setLength(0)
        for (i in 0 until bufferLength) {
            stringBuilder.append(buffer[i])
        }
        return stringBuilder.toString()
    }

    fun length(): Int = bufferLength

    fun isEmpty(): Boolean = bufferLength == 0

    fun reset() {
        bufferLength = 0
        rawHistoryLength = 0
        currentTone = VietnameseCharMap.TONE_NONE
        lastToneKey = '\u0000'
    }

    private fun recordRawKey(c: Char) {
        if (rawHistoryLength < MAX_WORD_LENGTH) {
            rawKeyHistory[rawHistoryLength++] = c
        }
    }

    private fun appendChar(c: Char) {
        if (bufferLength < MAX_WORD_LENGTH) {
            buffer[bufferLength++] = c
        }
    }

    private fun hasAnyVowel(): Boolean {
        for (i in 0 until bufferLength) {
            if (VietnameseCharMap.isVowel(buffer[i])) {
                return true
            }
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

        // First strip existing tones
        removeCurrentTone()

        // Apply new tone to target vowel
        buffer[targetIndex] = VietnameseCharMap.applyTone(buffer[targetIndex], newTone)
        currentTone = newTone
        return true
    }

    /**
     * Tone placement according to Vietnamese orthography rules
     * (e.g. toán, hòa, hòe, thúy, việt, đường, chuối, nguyễn, người).
     */
    private fun findToneTargetIndex(): Int {
        var vowelCount = 0

        for (i in 0 until bufferLength) {
            if (VietnameseCharMap.isVowel(buffer[i])) {
                // Check if part of onset clusters 'qu' or 'gi'
                if (i > 0) {
                    val prev = buffer[i - 1].lowercaseChar()
                    val currRow = VietnameseCharMap.getVowelRow(buffer[i])
                    if (prev == 'q' && currRow == 9 && i + 1 < bufferLength && VietnameseCharMap.isVowel(buffer[i + 1])) {
                        // 'qu' is onset consonant, skip 'u'
                        continue
                    }
                    if (prev == 'g' && currRow == 5 && i + 1 < bufferLength && VietnameseCharMap.isVowel(buffer[i + 1])) {
                        // 'gi' is onset consonant, skip 'i'
                        continue
                    }
                }
                if (vowelCount < vowelIndicesScratch.size) {
                    vowelIndicesScratch[vowelCount++] = i
                }
            }
        }

        if (vowelCount == 0) return -1
        if (vowelCount == 1) return vowelIndicesScratch[0]

        // Priority rule: vowels with circumflex/breve/horn (ă=1, â=2, ê=4, ô=7, ơ=8) always take the tone
        // Searching right-to-left ensures 'ơ' wins in 'ươ' (đường, người), 'ê' in 'uyê' (nguyễn), 'ô' in 'uôi' (chuối)
        for (k in vowelCount - 1 downTo 0) {
            val idx = vowelIndicesScratch[k]
            val row = VietnameseCharMap.getVowelRow(buffer[idx])
            if (row == 1 || row == 2 || row == 4 || row == 7 || row == 8) {
                return idx
            }
        }

        val lastVowelIdx = vowelIndicesScratch[vowelCount - 1]
        val hasTrailingConsonant = lastVowelIdx < bufferLength - 1

        if (vowelCount == 2) {
            if (hasTrailingConsonant) {
                // Closed syllable: toán, hoàn, thuận -> 2nd vowel
                return vowelIndicesScratch[1]
            }
            // Open syllable: hòa, hòe, thúy, mía, múa, mứa, báo -> 1st vowel
            return vowelIndicesScratch[0]
        }

        // 3+ vowels without diacritic (oai, oay, uya): middle vowel
        return vowelIndicesScratch[1]
    }

    private fun handleModifierKey(c: Char): Boolean {
        val lower = c.lowercaseChar()

        // 'dd' -> đ (checks adjacent char first, then onset 'd' at index 0)
        if (lower == 'd') {
            if (bufferLength > 0) {
                val targetIdx = when {
                    buffer[bufferLength - 1].lowercaseChar() == 'd' ||
                        buffer[bufferLength - 1] == 'đ' ||
                        buffer[bufferLength - 1] == 'Đ' -> bufferLength - 1
                    buffer[0].lowercaseChar() == 'd' ||
                        buffer[0] == 'đ' ||
                        buffer[0] == 'Đ' -> 0
                    else -> -1
                }
                if (targetIdx >= 0) {
                    val prev = buffer[targetIdx]
                    return when (prev) {
                        'd' -> {
                            buffer[targetIdx] = 'đ'
                            true
                        }
                        'D' -> {
                            buffer[targetIdx] = 'Đ'
                            true
                        }
                        'đ', 'Đ' -> {
                            // Revert: đ + d -> dd
                            buffer[targetIdx] = if (prev == 'đ') 'd' else 'D'
                            appendChar(c)
                            true
                        }
                        else -> false
                    }
                }
            }
            return false
        }

        // 'w' shortcut for ư, ơ, ă, and compound uo -> ươ
        if (lower == 'w') {
            if (bufferLength == 0) {
                buffer[bufferLength++] = if (c.isUpperCase()) 'Ư' else 'ư'
                return true
            }

            // Scan backwards for a vowel modifiable or revertible by 'w'
            for (i in bufferLength - 1 downTo 0) {
                val ch = buffer[i]
                if (!VietnameseCharMap.isVowel(ch)) continue

                val row = VietnameseCharMap.getVowelRow(ch)

                // Handle compound 'uo' + 'w' -> 'ươ' (excluding 'quo' -> 'quơ')
                if (row == 6 && i > 0) {
                    val prevCh = buffer[i - 1]
                    val isAfterQ = i >= 2 && buffer[i - 2].lowercaseChar() == 'q'
                    if (!isAfterQ && VietnameseCharMap.getVowelRow(prevCh) == 9) {
                        val transformedU = VietnameseCharMap.transformVowel(prevCh, 'w')
                        val transformedO = VietnameseCharMap.transformVowel(ch, 'w')
                        if (transformedU != null && transformedO != null) {
                            buffer[i - 1] = transformedU
                            buffer[i] = transformedO
                            return true
                        }
                    }
                }

                // Transform single vowel (a -> ă, o -> ơ, u -> ư)
                val transformed = VietnameseCharMap.transformVowel(ch, 'w')
                if (transformed != null) {
                    buffer[i] = transformed
                    return true
                }

                // Revert only vowels that were created by 'w' (ă=1, ơ=8, ư=10)
                if (row == 1 || row == 8 || row == 10) {
                    // Revert compound 'ươ' + 'w' -> 'uow'
                    if (row == 8 && i > 0 && VietnameseCharMap.getVowelRow(buffer[i - 1]) == 10) {
                        val revertedU = VietnameseCharMap.revertVowel(buffer[i - 1])
                        val revertedO = VietnameseCharMap.revertVowel(ch)
                        if (revertedU != null && revertedO != null) {
                            buffer[i - 1] = revertedU
                            buffer[i] = revertedO
                            appendChar(c)
                            return true
                        }
                    }
                    val reverted = VietnameseCharMap.revertVowel(ch)
                    if (reverted != null) {
                        buffer[i] = reverted
                        appendChar(c)
                        return true
                    }
                }
            }

            appendChar(if (c.isUpperCase()) 'Ư' else 'ư')
            return true
        }

        // 'a' (a <-> â), 'e' (e <-> ê), 'o' (o <-> ô)
        val baseRow = when (lower) {
            'a' -> 0
            'e' -> 3
            'o' -> 6
            else -> return false
        }
        val transformedRow = when (lower) {
            'a' -> 2 // â
            'e' -> 4 // ê
            'o' -> 7 // ô
            else -> return false
        }

        for (i in bufferLength - 1 downTo 0) {
            val ch = buffer[i]
            val row = VietnameseCharMap.getVowelRow(ch)
            if (row == baseRow) {
                val transformed = VietnameseCharMap.transformVowel(ch, lower)
                if (transformed != null) {
                    buffer[i] = transformed
                    return true
                }
            } else if (row == transformedRow) {
                // Revert transformation e.g. â + a -> aa, ê + e -> ee, ô + o -> oo
                val reverted = VietnameseCharMap.revertVowel(ch)
                if (reverted != null) {
                    buffer[i] = reverted
                    appendChar(c)
                    return true
                }
            }
        }

        return false
    }
}
