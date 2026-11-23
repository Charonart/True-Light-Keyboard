package com.truelight.ime.engine

/**
 * High-performance, zero-allocation Finite State Machine (FSM)
 * for Vietnamese Telex typing with raw-key tracking, standalone 'w'
 * reversion (`ww -> w`, `www -> ww`, `nhww -> nhw`), and Vietnamese
 * phonotactic auto-restore for English/loanwords.
 */
class TelexFsmEngine : InputEngine {

    companion object {
        private const val MAX_WORD_LENGTH = 64

        private const val MOD_NONE = 0
        private const val MOD_APPLIED = 1
        private const val MOD_REVERTED = 2
    }

    /** false = old style (hòa, thúy), true = modern style (hoà, thuý) */
    var modernTonePlacement: Boolean = false

    /** true = automatically restore raw keystrokes when word breaks Vietnamese phonotactics */
    var autoRestoreEnabled: Boolean = true

    // Composing word buffer - preallocated CharArray
    private val buffer = CharArray(MAX_WORD_LENGTH)
    private var bufferLength = 0

    // Raw keystrokes recorded for the current composing word (for auto-restore)
    private val rawKeys = CharArray(MAX_WORD_LENGTH)
    private var rawLength = 0

    // Marks buffer indices where 'ư'/'Ư' originated from standalone 'w'/'W' (not 'u' + 'w')
    private val standaloneWFlags = BooleanArray(MAX_WORD_LENGTH)

    // Preallocated vowel index scratch array for zero-allocation tone placement
    private val vowelIndicesScratch = IntArray(8)

    // Currently active tone mark (0: none, 1: sac, 2: huyen, 3: hoi, 4: nga, 5: nang)
    private var currentTone = VietnameseCharMap.TONE_NONE
    private var lastToneKey: Char = '\u0000'
    private var lastKeyWasTone: Boolean = false

    // Tracks whether any Telex transformation has happened & whether raw-restore is latched
    private var hasTransformed: Boolean = false
    private var isRawRestored: Boolean = false

    private val cachedResult = InputEngine.StepResult()
    private val stringBuilder = StringBuilder(MAX_WORD_LENGTH)

    /**
     * Process incoming character.
     * Returns cached StepResult with updated composing text.
     */
    override fun processChar(c: Char): InputEngine.StepResult {
        // If not a letter, finalize word
        if (!c.isLetter()) {
            cachedResult.consumed = false
            cachedResult.text = getComposingText()
            cachedResult.isWordTerminated = true
            reset()
            return cachedResult
        }

        // Prevent buffer overflow: cleanly terminate current chunk so IME commits it
        // and starts a fresh composing token with the incoming character.
        if (bufferLength >= MAX_WORD_LENGTH - 2 || rawLength >= MAX_WORD_LENGTH - 2) {
            cachedResult.consumed = false
            cachedResult.text = getComposingText()
            cachedResult.isWordTerminated = true
            reset()
            return cachedResult
        }

        // Always record raw key for accurate auto-restore
        rawKeys[rawLength++] = c

        // Once explicitly reverted or auto-restored, keep appending raw chars for this word
        if (isRawRestored) {
            appendChar(c)
            lastKeyWasTone = false
            return finishStep()
        }

        val lower = c.lowercaseChar()

        // 1. Check for 'z' - remove tone
        if (lower == 'z' && currentTone != VietnameseCharMap.TONE_NONE) {
            removeCurrentTone()
            lastToneKey = '\u0000'
            lastKeyWasTone = false
            hasTransformed = true
            return finishStep()
        }

        // 2. Check for tone keys ('s', 'f', 'r', 'x', 'j')
        if (VietnameseCharMap.isToneKey(c)) {
            val tone = VietnameseCharMap.getToneFromKey(c)
            if (currentTone == tone && lastToneKey == lower) {
                if (lastKeyWasTone || !autoRestoreEnabled) {
                    // Adjacent repeat tone key -> Telex escape (e.g. toass -> toas)
                    removeCurrentTone()
                    appendChar(c)
                    lastToneKey = '\u0000'
                    lastKeyWasTone = false
                    isRawRestored = true
                } else {
                    // Non-adjacent repeat tone key (e.g. uses, roses, loses) -> auto-restore raw word
                    restoreRawKeys()
                }
                return finishStep()
            } else if (hasAnyVowel() && isSingleVnSyllableShape()) {
                // Prevent a non-adjacent second different tone key from hijacking incomplete
                // vowel prefixes in English words (e.g. "user" -> u + s + e + r, "cursor").
                val blockSecondTone = autoRestoreEnabled &&
                    currentTone != VietnameseCharMap.TONE_NONE &&
                    !lastKeyWasTone &&
                    !VietnamesePhonotactics.isCompleteVietnameseSyllable(buffer, bufferLength)

                if (!blockSecondTone) {
                    val success = applyToneToBestVowel(tone)
                    if (success) {
                        currentTone = tone
                        lastToneKey = lower
                        lastKeyWasTone = true
                        hasTransformed = true
                        maybeAutoRestore()
                        return finishStep()
                    }
                }
            }
        }

        // 3. Check for vowel/consonant modifier ('a', 'w', 'e', 'o', 'd')
        if (VietnameseCharMap.isModifyKey(c)) {
            val modResult = handleModifierKey(c)
            if (modResult == MOD_APPLIED) {
                hasTransformed = true
                lastKeyWasTone = false
                if (currentTone != VietnameseCharMap.TONE_NONE) {
                    applyToneToBestVowel(currentTone)
                }
                maybeAutoRestore()
                return finishStep()
            } else if (modResult == MOD_REVERTED) {
                lastKeyWasTone = false
                isRawRestored = true
                if (currentTone != VietnameseCharMap.TONE_NONE) {
                    applyToneToBestVowel(currentTone)
                }
                return finishStep()
            }
        }

        // 4. Default: append character directly (keep lastToneKey active while currentTone != TONE_NONE)
        appendChar(c)
        lastKeyWasTone = false

        // Re-position active tone when syllable structure changes (e.g. hoafn -> hoàn, chuoosi -> chuối)
        if (currentTone != VietnameseCharMap.TONE_NONE) {
            applyToneToBestVowel(currentTone)
        }

        maybeAutoRestore()
        return finishStep()
    }

    /**
     * Handle backspace event. Returns true if consumed, false if buffer was empty.
     */
    override fun handleBackspace(): Boolean {
        if (bufferLength == 0) return false

        val deletedChar = buffer[bufferLength - 1]
        val wasStandaloneW = standaloneWFlags[bufferLength - 1]
        bufferLength--
        standaloneWFlags[bufferLength] = false
        lastToneKey = '\u0000'
        lastKeyWasTone = false

        if (bufferLength == 0) {
            reset()
        } else if (isRawRestored) {
            rawLength = bufferLength
            for (i in 0 until bufferLength) {
                rawKeys[i] = buffer[i]
            }
        } else {
            removeLastMatchingRawKey(deletedChar, wasStandaloneW)
            // Re-evaluate tone from remaining characters
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
        stringBuilder.setLength(0)
        for (i in 0 until bufferLength) {
            stringBuilder.append(buffer[i])
        }
        return stringBuilder.toString()
    }

    override fun length(): Int = bufferLength

    override fun isEmpty(): Boolean = bufferLength == 0

    override fun reset() {
        for (i in 0 until bufferLength) {
            standaloneWFlags[i] = false
        }
        bufferLength = 0
        rawLength = 0
        currentTone = VietnameseCharMap.TONE_NONE
        lastToneKey = '\u0000'
        lastKeyWasTone = false
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
        if (!VietnamesePhonotactics.isValidVietnamesePrefix(buffer, bufferLength)) {
            restoreRawKeys()
        }
    }

    private fun restoreRawKeys() {
        val len = rawLength.coerceAtMost(MAX_WORD_LENGTH)
        for (i in 0 until len) {
            buffer[i] = rawKeys[i]
            standaloneWFlags[i] = false
        }
        bufferLength = len
        currentTone = VietnameseCharMap.TONE_NONE
        lastToneKey = '\u0000'
        lastKeyWasTone = false
        isRawRestored = true
    }

    private fun removeLastMatchingRawKey(deletedChar: Char, wasStandaloneW: Boolean) {
        if (rawLength <= 0) return
        val targetBase = if (wasStandaloneW) {
            'w'
        } else {
            baseAsciiLower(deletedChar)
        }
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
            if (standaloneWFlags[i] || ch == 'đ' || ch == 'Đ') {
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
            standaloneWFlags[bufferLength] = false
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

    /**
     * Verifies buffer has a single contiguous vowel cluster (rejects multi-syllable
     * foreign words like "came" in "camera" that have a Vowel-Consonant-Vowel split).
     */
    private fun isSingleVnSyllableShape(): Boolean {
        var inVowelCluster = false
        var clusterCount = 0
        for (i in 0 until bufferLength) {
            val ch = buffer[i]
            val lower = ch.lowercaseChar()
            if (lower == 'f' || lower == 'j' || lower == 'w' || lower == 'z') {
                return false
            }
            if (VietnameseCharMap.isVowel(ch)) {
                if (!inVowelCluster) {
                    clusterCount++
                    if (clusterCount > 1) return false
                    inVowelCluster = true
                }
            } else {
                inVowelCluster = false
            }
        }
        return clusterCount == 1
    }

    /**
     * Checks whether the current buffer (starting with 'd'/'D') is a plausible Vietnamese syllable
     * eligible for remote 'd' -> 'đ' (e.g. "duongd" -> "đương", "dand" -> "đan"),
     * preventing corruption of English words like "download", "discord", "decide", "divider".
     */
    private fun isPlausibleVnSyllableForRemoteD(): Boolean {
        if (bufferLength < 2 || bufferLength > 6) return false
        var seenVowel = false
        var seenCoda = false
        for (i in 1 until bufferLength) {
            val ch = buffer[i]
            val lower = ch.lowercaseChar()
            if (lower == 'f' || lower == 'j' || lower == 'w' || lower == 'z') return false
            if (VietnameseCharMap.isVowel(ch)) {
                if (seenCoda) return false // Vowel after coda -> multi-syllable loanword (e.g. "decide")
                seenVowel = true
            } else {
                if (!seenVowel) return false // Consonant cluster after 'd' (e.g. "dr") -> not Vietnamese
                if (!VietnamesePhonotactics.isValidCodaChar(ch)) return false // Non-VN coda (s, l, v, r)
                seenCoda = true
            }
        }
        return seenVowel
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
     * (e.g. toán, hòa, hòe, thúy, việt, đường, chuối, nguyễn, người, cứu).
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
            if (modernTonePlacement) {
                val r1 = VietnameseCharMap.getVowelRow(buffer[vowelIndicesScratch[0]])
                val r2 = VietnameseCharMap.getVowelRow(buffer[vowelIndicesScratch[1]])
                // Modern orthography: oa, oe, uy -> 2nd vowel (hoà, hoè, thuý)
                if ((r1 == 6 && r2 == 0) || (r1 == 6 && r2 == 3) || (r1 == 9 && r2 == 11)) {
                    return vowelIndicesScratch[1]
                }
            }
            // Open syllable: hòa, hòe, thúy, mía, múa, mứa, báo, cứu -> 1st vowel
            return vowelIndicesScratch[0]
        }

        // 3+ vowels without diacritic (oai, oay, uya): middle vowel
        return vowelIndicesScratch[1]
    }

    private fun handleModifierKey(c: Char): Int {
        val lower = c.lowercaseChar()

        // 'dd' -> đ (checks adjacent char first, then remote onset 'd' at index 0 for valid VN syllables)
        if (lower == 'd') {
            if (bufferLength > 0) {
                val last = buffer[bufferLength - 1]
                val targetIdx = when {
                    last.lowercaseChar() == 'd' || last == 'đ' || last == 'Đ' -> bufferLength - 1
                    buffer[0] == 'đ' || buffer[0] == 'Đ' -> 0
                    buffer[0].lowercaseChar() == 'd' && isPlausibleVnSyllableForRemoteD() -> 0
                    else -> -1
                }
                if (targetIdx >= 0) {
                    val prev = buffer[targetIdx]
                    return when (prev) {
                        'd' -> {
                            buffer[targetIdx] = 'đ'
                            MOD_APPLIED
                        }
                        'D' -> {
                            buffer[targetIdx] = 'Đ'
                            MOD_APPLIED
                        }
                        'đ', 'Đ' -> {
                            // Revert: đ + d -> d...d (e.g. ddd -> dd, dadd -> dad)
                            buffer[targetIdx] = if (prev == 'đ') 'd' else 'D'
                            appendChar(c)
                            MOD_REVERTED
                        }
                        else -> MOD_NONE
                    }
                }
            }
            return MOD_NONE
        }

        // 'w' shortcut for ư, ơ, ă, compound uo -> ươ, and compound uu -> ưu (lưu, hưu, cứu)
        if (lower == 'w') {
            if (bufferLength == 0) {
                standaloneWFlags[0] = true
                buffer[bufferLength++] = if (c.isUpperCase()) 'Ư' else 'ư'
                return MOD_APPLIED
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
                            standaloneWFlags[i - 1] = false
                            standaloneWFlags[i] = false
                            return MOD_APPLIED
                        }
                    }
                }

                // Handle compound 'ua' + 'w' -> 'ưa' (mưa, chưa, giữa, sữa; excluding 'qua' -> 'quă')
                // and revert 'ưa' + 'w' -> 'uaw' (or 'waw' if 'ư' came from standalone 'w')
                if (row == 0 && i > 0) {
                    val prevCh = buffer[i - 1]
                    val prevRow = VietnameseCharMap.getVowelRow(prevCh)
                    val isAfterQ = i >= 2 && buffer[i - 2].lowercaseChar() == 'q'
                    if (!isAfterQ && prevRow == 9) {
                        val transformedU = VietnameseCharMap.transformVowel(prevCh, 'w')
                        if (transformedU != null) {
                            buffer[i - 1] = transformedU
                            standaloneWFlags[i - 1] = false
                            return MOD_APPLIED
                        }
                    } else if (prevRow == 10) {
                        if (standaloneWFlags[i - 1]) {
                            buffer[i - 1] = if (prevCh.isUpperCase()) 'W' else 'w'
                            standaloneWFlags[i - 1] = false
                            appendChar(c)
                            recalcCurrentTone()
                            return MOD_REVERTED
                        }
                        val revertedU = VietnameseCharMap.revertVowel(prevCh)
                        if (revertedU != null) {
                            buffer[i - 1] = revertedU
                            standaloneWFlags[i - 1] = false
                            appendChar(c)
                            return MOD_REVERTED
                        }
                    }
                }

                // Handle compound 'uu' + 'w' -> 'ưu' (lưu, hưu, cứu) and revert 'ưu' + 'w' -> 'uuw'
                if (row == 9 && i > 0) {
                    val prevCh = buffer[i - 1]
                    val prevRow = VietnameseCharMap.getVowelRow(prevCh)
                    val isAfterQ = i >= 2 && buffer[i - 2].lowercaseChar() == 'q'
                    if (!isAfterQ && prevRow == 9) {
                        val transformedFirstU = VietnameseCharMap.transformVowel(prevCh, 'w')
                        if (transformedFirstU != null) {
                            buffer[i - 1] = transformedFirstU
                            standaloneWFlags[i - 1] = false
                            return MOD_APPLIED
                        }
                    } else if (prevRow == 10) {
                        val revertedFirstU = VietnameseCharMap.revertVowel(prevCh)
                        if (revertedFirstU != null) {
                            buffer[i - 1] = revertedFirstU
                            standaloneWFlags[i - 1] = false
                            appendChar(c)
                            return MOD_REVERTED
                        }
                    }
                }

                // Never transform 'u' inside onset 'qu' into 'ư' (e.g. quw must not become qư)
                if (row == 9 && i > 0 && buffer[i - 1].lowercaseChar() == 'q') {
                    continue
                }

                // Transform single vowel (a -> ă, o -> ơ, u -> ư)
                val transformed = VietnameseCharMap.transformVowel(ch, 'w')
                if (transformed != null) {
                    buffer[i] = transformed
                    standaloneWFlags[i] = false
                    return MOD_APPLIED
                }

                // Revert only vowels that were created by 'w' (ă=1, ơ=8, ư=10)
                if (row == 1 || row == 8 || row == 10) {
                    // Standalone 'ư' + 'w' -> direct 'w' (ww -> w, WW -> W, nhww -> nhw)
                    if (row == 10 && standaloneWFlags[i]) {
                        buffer[i] = if (ch.isUpperCase()) 'W' else 'w'
                        standaloneWFlags[i] = false
                        recalcCurrentTone()
                        return MOD_REVERTED
                    }

                    // Revert compound 'ươ' + 'w' -> 'uow' (or 'wow' if 'ư' was standalone 'w')
                    if (row == 8 && i > 0 && VietnameseCharMap.getVowelRow(buffer[i - 1]) == 10) {
                        val revertedO = VietnameseCharMap.revertVowel(ch)
                        if (revertedO != null) {
                            if (standaloneWFlags[i - 1]) {
                                buffer[i - 1] = if (buffer[i - 1].isUpperCase()) 'W' else 'w'
                                standaloneWFlags[i - 1] = false
                                buffer[i] = revertedO
                                appendChar(c)
                                return MOD_REVERTED
                            }
                            val revertedU = VietnameseCharMap.revertVowel(buffer[i - 1])
                            if (revertedU != null) {
                                buffer[i - 1] = revertedU
                                buffer[i] = revertedO
                                appendChar(c)
                                return MOD_REVERTED
                            }
                        }
                    }

                    val reverted = VietnameseCharMap.revertVowel(ch)
                    if (reverted != null) {
                        buffer[i] = reverted
                        appendChar(c)
                        return MOD_REVERTED
                    }
                }
            }

            // Only emit standalone 'ư' when preceded by a valid Vietnamese onset consonant (no vowels yet).
            // Otherwise let 'w' append normally so English words like "new", "view", "few" stay intact!
            if (VietnamesePhonotactics.isValidOnsetOnly(buffer, bufferLength)) {
                val idx = bufferLength
                appendChar(if (c.isUpperCase()) 'Ư' else 'ư')
                standaloneWFlags[idx] = true
                return MOD_APPLIED
            }
            return MOD_NONE
        }

        // 'a' (a <-> â), 'e' (e <-> ê), 'o' (o <-> ô)
        val baseRow = when (lower) {
            'a' -> 0
            'e' -> 3
            'o' -> 6
            else -> return MOD_NONE
        }
        val transformedRow = when (lower) {
            'a' -> 2 // â
            'e' -> 4 // ê
            'o' -> 7 // ô
            else -> return MOD_NONE
        }

        // Scan backwards, stopping if we hit an intervening different vowel or non-Vietnamese coda consonant
        for (i in bufferLength - 1 downTo 0) {
            val ch = buffer[i]
            val row = VietnameseCharMap.getVowelRow(ch)
            if (row == baseRow) {
                val transformed = VietnameseCharMap.transformVowel(ch, lower)
                if (transformed != null) {
                    buffer[i] = transformed
                    return MOD_APPLIED
                }
            } else if (row == transformedRow) {
                // Revert transformation e.g. â + a -> aa, ê + e -> ee, ô + o -> oo
                val reverted = VietnameseCharMap.revertVowel(ch)
                if (reverted != null) {
                    buffer[i] = reverted
                    appendChar(c)
                    return MOD_REVERTED
                }
            } else if (row >= 0) {
                // Intervening different vowel (e.g. "camera", "pokemon", "tomato") -> stop!
                break
            } else if (!VietnamesePhonotactics.isValidCodaChar(ch)) {
                // Non-Vietnamese coda consonant (e.g. "here", "level", "java", "delete", "color") -> stop!
                break
            }
        }

        return MOD_NONE
    }
}
