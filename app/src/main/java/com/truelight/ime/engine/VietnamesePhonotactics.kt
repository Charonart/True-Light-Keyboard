package com.truelight.ime.engine

/**
 * Zero-allocation Vietnamese syllable phonotactics validator.
 *
 * Validates `[Onset] + [VowelCluster] + [Coda]` structure (and intermediate typing
 * prefixes for Telex & VNI) directly on a [CharArray] with zero heap allocations.
 *
 * Used by [TelexFsmEngine] and [VniFsmEngine] to detect non-Vietnamese words
 * (e.g. "window", "word", "work", "user", "cases", "software", "facebook", "google")
 * and cleanly auto-restore raw keystrokes without breaking any valid Vietnamese word.
 */
object VietnamesePhonotactics {

    private const val VOWEL_INVALID = 0
    private const val VOWEL_OPEN_COMPLETE = 1
    private const val VOWEL_OPEN_INTERMEDIATE = 2
    private const val VOWEL_CLOSED_COMPLETE = 3
    private const val VOWEL_CLOSED_INTERMEDIATE = 4

    /**
     * Returns true if `buf[0 until len]` is a valid Vietnamese syllable or a valid
     * in-progress prefix while typing in Telex (or VNI when [allowVniIntermediates] is true).
     */
    fun isValidVietnamesePrefix(
        buf: CharArray,
        len: Int,
        allowVniIntermediates: Boolean = false
    ): Boolean = validate(buf, len, requireComplete = false, allowVniIntermediates = allowVniIntermediates)

    /**
     * Returns true if `buf[0 until len]` forms a complete Vietnamese syllable
     * (i.e. has a vowel nucleus that is NOT an incomplete intermediate like `ue`, `uo`, `ưo`, `ie`, `ye`, `uu`).
     */
    fun isCompleteVietnameseSyllable(buf: CharArray, len: Int): Boolean =
        validate(buf, len, requireComplete = true, allowVniIntermediates = false)

    /**
     * Checks whether `buf[0 until len]` contains ONLY a valid Vietnamese consonant onset
     * (no vowels yet, excluding `'q'`) so standalone `'w'` can legitimately produce `'ư'`
     * (e.g. `"nhw"` -> `"như"`).
     */
    fun isValidOnsetOnly(buf: CharArray, len: Int): Boolean {
        if (len <= 0 || len > 3) return false
        val c0 = buf[0].lowercaseChar()
        if (len == 1) {
            if (VietnameseCharMap.isVowel(c0)) return false
            if (c0 == 'đ') return true
            return c0 in 'a'..'z' &&
                c0 != 'f' && c0 != 'j' && c0 != 'w' && c0 != 'z' && c0 != 'q'
        }
        val c1 = buf[1].lowercaseChar()
        if (len == 2) {
            return (c0 == 'c' && c1 == 'h') ||
                (c0 == 'g' && c1 == 'h') ||
                (c0 == 'k' && c1 == 'h') ||
                (c0 == 'n' && (c1 == 'g' || c1 == 'h')) ||
                (c0 == 'p' && c1 == 'h') ||
                (c0 == 't' && (c1 == 'h' || c1 == 'r'))
        }
        val c2 = buf[2].lowercaseChar()
        return c0 == 'n' && c1 == 'g' && c2 == 'h'
    }

    /**
     * Returns true if [c] can appear anywhere in a Vietnamese coda (`c, ch, m, n, ng, nh, p, t`).
     */
    fun isValidCodaChar(c: Char): Boolean {
        return when (c.lowercaseChar()) {
            'c', 'h', 'm', 'n', 'g', 'p', 't' -> true
            else -> false
        }
    }

    private fun validate(
        buf: CharArray,
        len: Int,
        requireComplete: Boolean,
        allowVniIntermediates: Boolean
    ): Boolean {
        if (len <= 0) return !requireComplete

        // 1. Reject letters/chars that never exist inside a Vietnamese syllable
        for (k in 0 until len) {
            val ch = buf[k]
            if (!ch.isLetter()) return false
            val lower = ch.lowercaseChar()
            if (lower == 'f' || lower == 'j' || lower == 'w' || lower == 'z') {
                return false
            }
        }

        // 2. Scan consonant onset
        var i = 0
        while (i < len && !VietnameseCharMap.isVowel(buf[i])) {
            i++
        }
        val onsetLen = i
        var isGhOrNgh = false

        when (onsetLen) {
            0 -> { /* empty onset is valid */ }
            1 -> {
                val c0 = buf[0].lowercaseChar()
                if (c0 != 'đ' && c0 !in 'a'..'z') return false
            }
            2 -> {
                val c0 = buf[0].lowercaseChar()
                val c1 = buf[1].lowercaseChar()
                val valid2 = (c0 == 'c' && c1 == 'h') ||
                    (c0 == 'g' && c1 == 'h') ||
                    (c0 == 'k' && c1 == 'h') ||
                    (c0 == 'n' && (c1 == 'g' || c1 == 'h')) ||
                    (c0 == 'p' && c1 == 'h') ||
                    (c0 == 't' && (c1 == 'h' || c1 == 'r'))
                if (!valid2) return false
                if (c0 == 'g' && c1 == 'h') isGhOrNgh = true
            }
            3 -> {
                val c0 = buf[0].lowercaseChar()
                val c1 = buf[1].lowercaseChar()
                val c2 = buf[2].lowercaseChar()
                if (!(c0 == 'n' && c1 == 'g' && c2 == 'h')) return false
                isGhOrNgh = true
            }
            else -> return false
        }

        // Consonant-only prefix (no vowels yet)
        if (i == len) return !requireComplete

        // 3. Handle orthographic onset clusters 'qu', 'gi', and 'gh'/'ngh' front-vowel rule
        if (onsetLen == 1 && buf[0].lowercaseChar() == 'q') {
            val firstVowelRow = VietnameseCharMap.getVowelRow(buf[i])
            // 'q' must be followed by plain 'u' (row 9)
            if (firstVowelRow != 9) return false
            if (i + 1 == len) {
                // "qu" alone is a valid typing prefix, not a complete syllable
                return !requireComplete
            }
            if (!VietnameseCharMap.isVowel(buf[i + 1])) {
                // "qu" + consonant without a vowel (e.g. "qun", "qut") is invalid
                return false
            }
            // 'qu' acts as onset; reject 'qu' + 'u'/'ư' (e.g. "quu", "quư")
            val nextRow = VietnameseCharMap.getVowelRow(buf[i + 1])
            if (nextRow == 9 || nextRow == 10) return false
            i++ // consume 'u' as part of onset 'qu'
        } else if (onsetLen == 1 && buf[0].lowercaseChar() == 'g' &&
            VietnameseCharMap.getVowelRow(buf[i]) == 5 &&
            i + 1 < len && VietnameseCharMap.isVowel(buf[i + 1])
        ) {
            // 'gi' + vowel acts as onset (e.g. "giá", "giường", "giữ", "giêng"), but reject "gii"
            if (VietnameseCharMap.getVowelRow(buf[i + 1]) == 5) return false
            i++ // consume 'i' as part of onset 'gi'
        } else if (isGhOrNgh) {
            // 'gh' and 'ngh' only precede front vowels e (3), ê (4), i (5)
            val firstRow = VietnameseCharMap.getVowelRow(buf[i])
            if (firstRow != 3 && firstRow != 4 && firstRow != 5) return false
        }

        // 4. Scan contiguous vowel cluster
        val vStart = i
        while (i < len && VietnameseCharMap.isVowel(buf[i])) {
            i++
        }
        val vEnd = i
        val vLen = vEnd - vStart
        if (vLen <= 0 || vLen > 3) return false

        val vowelFlags = classifyVowelCluster(buf, vStart, vLen, allowVniIntermediates)
        if (vowelFlags == VOWEL_INVALID) return false
        if (requireComplete &&
            (vowelFlags == VOWEL_OPEN_INTERMEDIATE || vowelFlags == VOWEL_CLOSED_INTERMEDIATE)
        ) {
            return false
        }

        // 5. Parse coda (final consonant)
        val codaLen = len - vEnd
        if (codaLen == 0) return true

        // Open-only diphthongs/triphthongs (ending in off-glide i/y/o/u, or ia/ưa/ưo/uu)
        // can NEVER take a final consonant
        if (vowelFlags == VOWEL_OPEN_COMPLETE || vowelFlags == VOWEL_OPEN_INTERMEDIATE) {
            return false
        }

        if (codaLen > 2) return false

        // Reject any vowel after a coda consonant (multi-syllable V-C-V foreign words)
        if (VietnameseCharMap.isVowel(buf[vEnd])) return false
        val c0 = buf[vEnd].lowercaseChar()
        val isStopCoda: Boolean

        if (codaLen == 1) {
            isStopCoda = when (c0) {
                'c', 'p', 't' -> true
                'm', 'n' -> false
                else -> return false // rejects lone g, h, b, d, k, l, q, r, s, v, x, đ
            }
        } else {
            if (VietnameseCharMap.isVowel(buf[vEnd + 1])) return false
            val c1 = buf[vEnd + 1].lowercaseChar()
            isStopCoda = when {
                c0 == 'c' && c1 == 'h' -> true
                c0 == 'n' && c1 == 'g' -> false
                c0 == 'n' && c1 == 'h' -> false
                else -> return false
            }

            // Palatal codas '-ch' and '-nh' only follow a (0), e (3, typing ê), ê (4), i (5), y (11)
            if (c1 == 'h') {
                val lastVowelRow = VietnameseCharMap.getVowelRow(buf[vEnd - 1])
                if (lastVowelRow != 0 && lastVowelRow != 3 && lastVowelRow != 4 &&
                    lastVowelRow != 5 && lastVowelRow != 11
                ) {
                    return false
                }
            }
        }

        // 6. Checked-syllable tone law: syllables ending in stop codas (-c, -ch, -p, -t)
        // can ONLY take unmarked, sắc (1), or nặng (5) — NEVER huyền (2), hỏi (3), ngã (4).
        if (isStopCoda) {
            for (k in 0 until vEnd) {
                val tone = VietnameseCharMap.getCharTone(buf[k])
                if (tone == VietnameseCharMap.TONE_HUYEN ||
                    tone == VietnameseCharMap.TONE_HOI ||
                    tone == VietnameseCharMap.TONE_NGA
                ) {
                    return false
                }
            }
        }

        return true
    }

    /**
     * Classifies a 1..3 vowel sequence using [VietnameseCharMap.getVowelRow] indices:
     * `0=a, 1=ă, 2=â, 3=e, 4=ê, 5=i, 6=o, 7=ô, 8=ơ, 9=u, 10=ư, 11=y`.
     */
    private fun classifyVowelCluster(
        buf: CharArray,
        vStart: Int,
        vLen: Int,
        allowVniIntermediates: Boolean
    ): Int {
        if (vLen == 1) {
            return VOWEL_CLOSED_COMPLETE
        }

        if (vLen == 2) {
            val r0 = VietnameseCharMap.getVowelRow(buf[vStart])
            val r1 = VietnameseCharMap.getVowelRow(buf[vStart + 1])
            return when ((r0 shl 4) or r1) {
                // Complete clusters that may take a coda
                (6 shl 4) or 0,   // oa
                (6 shl 4) or 1,   // oă
                (6 shl 4) or 3,   // oe
                (6 shl 4) or 6,   // oo (xoong, boong)
                (9 shl 4) or 0,   // ua (mua + intermediate for uâ e.g. tuần)
                (9 shl 4) or 2,   // uâ
                (9 shl 4) or 4,   // uê
                (9 shl 4) or 11,  // uy
                (5 shl 4) or 4,   // iê
                (11 shl 4) or 4,  // yê
                (9 shl 4) or 7,   // uô
                (10 shl 4) or 8 -> VOWEL_CLOSED_COMPLETE // ươ

                // Intermediate typing clusters that may take a coda before circumflex/horn arrives
                (5 shl 4) or 3,   // ie -> iê
                (11 shl 4) or 3,  // ye -> yê
                (9 shl 4) or 3,   // ue -> uê
                (9 shl 4) or 6,   // uo -> uô / ươ
                (9 shl 4) or 8 -> VOWEL_CLOSED_INTERMEDIATE // uơ -> ươ

                // Complete open-only diphthongs (cannot be followed by a coda)
                (0 shl 4) or 5,   // ai
                (0 shl 4) or 6,   // ao
                (0 shl 4) or 9,   // au
                (2 shl 4) or 9,   // âu
                (0 shl 4) or 11,  // ay
                (2 shl 4) or 11,  // ây
                (3 shl 4) or 6,   // eo
                (4 shl 4) or 9,   // êu
                (5 shl 4) or 0,   // ia
                (5 shl 4) or 9,   // iu
                (6 shl 4) or 5,   // oi
                (7 shl 4) or 5,   // ôi
                (8 shl 4) or 5,   // ơi
                (9 shl 4) or 5,   // ui
                (10 shl 4) or 0,  // ưa
                (10 shl 4) or 5,  // ưi
                (10 shl 4) or 9,  // ưu
                (11 shl 4) or 9 -> VOWEL_OPEN_COMPLETE // yu

                // Intermediate open-only clusters (e.g. dduwowngf at "đưo", cuusw at "cúu")
                (10 shl 4) or 6,  // ưo -> ươ
                (9 shl 4) or 9 -> VOWEL_OPEN_INTERMEDIATE // uu -> ưu

                // VNI intermediate for êu (e.g. neu16 -> nếu)
                (3 shl 4) or 9 -> if (allowVniIntermediates) VOWEL_OPEN_INTERMEDIATE else VOWEL_INVALID

                else -> VOWEL_INVALID
            }
        }

        // vLen == 3
        val r0 = VietnameseCharMap.getVowelRow(buf[vStart])
        val r1 = VietnameseCharMap.getVowelRow(buf[vStart + 1])
        val r2 = VietnameseCharMap.getVowelRow(buf[vStart + 2])
        return when ((r0 shl 8) or (r1 shl 4) or r2) {
            // Complete 3-vowel cluster that may take a coda
            (9 shl 8) or (11 shl 4) or 4 -> VOWEL_CLOSED_COMPLETE // uyê

            // Intermediate 3-vowel cluster for uyê
            (9 shl 8) or (11 shl 4) or 3 -> VOWEL_CLOSED_INTERMEDIATE // uye

            // Complete open-only triphthongs
            (5 shl 8) or (4 shl 4) or 9,    // iêu
            (11 shl 8) or (4 shl 4) or 9,   // yêu
            (6 shl 8) or (0 shl 4) or 5,    // oai
            (6 shl 8) or (0 shl 4) or 11,   // oay
            (6 shl 8) or (0 shl 4) or 6,    // oao
            (6 shl 8) or (3 shl 4) or 6,    // oeo
            (9 shl 8) or (2 shl 4) or 11,   // uây
            (9 shl 8) or (7 shl 4) or 5,    // uôi
            (10 shl 8) or (8 shl 4) or 5,   // ươi
            (10 shl 8) or (8 shl 4) or 9,   // ươu
            (9 shl 8) or (11 shl 4) or 0,   // uya
            (9 shl 8) or (11 shl 4) or 9 -> VOWEL_OPEN_COMPLETE // uyu

            // Intermediate open-only triphthongs
            (9 shl 8) or (6 shl 4) or 5,    // uoi -> uôi / ươi
            (10 shl 8) or (6 shl 4) or 5,   // ưoi -> ươi
            (9 shl 8) or (6 shl 4) or 9,    // uou -> ươu
            (10 shl 8) or (6 shl 4) or 9,   // ưou -> ươu
            (9 shl 8) or (0 shl 4) or 11,   // uay -> uây
            (5 shl 8) or (3 shl 4) or 9,    // ieu -> iêu
            (11 shl 8) or (3 shl 4) or 9 -> VOWEL_OPEN_INTERMEDIATE // yeu -> yêu

            else -> VOWEL_INVALID
        }
    }
}
