package com.truelight.ime.engine

/**
 * Static lookup tables and constants for Vietnamese character transformations.
 * Zero-allocation, high-performance lookup arrays.
 */
object VietnameseCharMap {

    const val TONE_NONE = 0
    const val TONE_SAC = 1   // 's'
    const val TONE_HUYEN = 2 // 'f'
    const val TONE_HOI = 3   // 'r'
    const val TONE_NGA = 4   // 'x'
    const val TONE_NANG = 5  // 'j'

    // Base vowels index mapping
    // a -> 0, ă -> 1, â -> 2, e -> 3, ê -> 4, i -> 5, o -> 6, ô -> 7, ơ -> 8, u -> 9, ư -> 10, y -> 11
    val LOWER_VOWEL_MATRIX = arrayOf(
        charArrayOf('a', 'á', 'à', 'ả', 'ã', 'ạ'), // 0: a
        charArrayOf('ă', 'ắ', 'ằ', 'ẳ', 'ẵ', 'ặ'), // 1: ă
        charArrayOf('â', 'ấ', 'ầ', 'ẩ', 'ẫ', 'ậ'), // 2: â
        charArrayOf('e', 'é', 'è', 'ẻ', 'ẽ', 'ẹ'), // 3: e
        charArrayOf('ê', 'ế', 'ề', 'ể', 'ễ', 'ệ'), // 4: ê
        charArrayOf('i', 'í', 'ì', 'ỉ', 'ĩ', 'ị'), // 5: i
        charArrayOf('o', 'ó', 'ò', 'ỏ', 'õ', 'ọ'), // 6: o
        charArrayOf('ô', 'ố', 'ồ', 'ổ', 'ỗ', 'ộ'), // 7: ô
        charArrayOf('ơ', 'ớ', 'ờ', 'ở', 'ỡ', 'ợ'), // 8: ơ
        charArrayOf('u', 'ú', 'ù', 'ủ', 'ũ', 'ụ'), // 9: u
        charArrayOf('ư', 'ứ', 'ừ', 'ử', 'ữ', 'ự'), // 10: ư
        charArrayOf('y', 'ý', 'ỳ', 'ỷ', 'ỹ', 'ỵ')  // 11: y
    )

    val UPPER_VOWEL_MATRIX = arrayOf(
        charArrayOf('A', 'Á', 'À', 'Ả', 'Ã', 'Ạ'),
        charArrayOf('Ă', 'Ắ', 'Ằ', 'Ẳ', 'Ẵ', 'Ặ'),
        charArrayOf('Â', 'Ấ', 'Ầ', 'Ẩ', 'Ẫ', 'Ậ'),
        charArrayOf('E', 'É', 'È', 'Ẻ', 'Ẽ', 'Ẹ'),
        charArrayOf('Ê', 'Ế', 'Ề', 'Ể', 'Ễ', 'Ệ'),
        charArrayOf('I', 'Í', 'Ì', 'Ỉ', 'Ĩ', 'Ị'),
        charArrayOf('O', 'Ó', 'Ò', 'Ỏ', 'Õ', 'Ọ'),
        charArrayOf('Ô', 'Ố', 'Ồ', 'Ổ', 'Ỗ', 'Ộ'),
        charArrayOf('Ơ', 'Ớ', 'Ờ', 'Ở', 'Ỡ', 'Ợ'),
        charArrayOf('U', 'Ú', 'Ù', 'Ủ', 'Ũ', 'Ụ'),
        charArrayOf('Ư', 'Ứ', 'Ừ', 'Ử', 'Ữ', 'Ự'),
        charArrayOf('Y', 'Ý', 'Ỳ', 'Ỷ', 'Ỹ', 'Ỵ')
    )

    fun isToneKey(c: Char): Boolean {
        val lower = c.lowercaseChar()
        return lower == 's' || lower == 'f' || lower == 'r' || lower == 'x' || lower == 'j'
    }

    fun getToneFromKey(c: Char): Int {
        return when (c.lowercaseChar()) {
            's' -> TONE_SAC
            'f' -> TONE_HUYEN
            'r' -> TONE_HOI
            'x' -> TONE_NGA
            'j' -> TONE_NANG
            else -> TONE_NONE
        }
    }

    fun isModifyKey(c: Char): Boolean {
        val lower = c.lowercaseChar()
        return lower == 'a' || lower == 'w' || lower == 'e' || lower == 'o' || lower == 'd'
    }

    fun isVowel(c: Char): Boolean {
        return getVowelRow(c) >= 0
    }

    /**
     * Returns the vowel family index (0..11) or -1 if not a Vietnamese vowel.
     */
    fun getVowelRow(c: Char): Int {
        for (i in LOWER_VOWEL_MATRIX.indices) {
            for (j in LOWER_VOWEL_MATRIX[i].indices) {
                if (LOWER_VOWEL_MATRIX[i][j] == c || UPPER_VOWEL_MATRIX[i][j] == c) {
                    return i
                }
            }
        }
        return -1
    }

    /**
     * Returns the tone index (0..5) of the given char, or 0 if none.
     */
    fun getCharTone(c: Char): Int {
        for (i in LOWER_VOWEL_MATRIX.indices) {
            for (j in LOWER_VOWEL_MATRIX[i].indices) {
                if (LOWER_VOWEL_MATRIX[i][j] == c || UPPER_VOWEL_MATRIX[i][j] == c) {
                    return j
                }
            }
        }
        return TONE_NONE
    }

    /**
     * Replaces tone of vowel c with newTone (0..5), preserving casing.
     */
    fun applyTone(c: Char, newTone: Int): Char {
        if (newTone !in 0..5) return c
        for (i in LOWER_VOWEL_MATRIX.indices) {
            for (j in LOWER_VOWEL_MATRIX[i].indices) {
                if (LOWER_VOWEL_MATRIX[i][j] == c) {
                    return LOWER_VOWEL_MATRIX[i][newTone]
                }
                if (UPPER_VOWEL_MATRIX[i][j] == c) {
                    return UPPER_VOWEL_MATRIX[i][newTone]
                }
            }
        }
        return c
    }

    /**
     * Transform base vowel by modification key ('a', 'w', 'e', 'o').
     * Returns transformed char preserving tone and case, or null if no transform.
     */
    fun transformVowel(vowel: Char, key: Char): Char? {
        val lowerV = vowel.lowercaseChar()
        val lowerK = key.lowercaseChar()
        val isUpper = vowel.isUpperCase()
        val tone = getCharTone(vowel)

        val targetRow = when (lowerK) {
            'a' -> when (getVowelRow(vowel)) {
                0 -> 2 // a -> â
                else -> null
            }
            'w' -> when (getVowelRow(vowel)) {
                0 -> 1  // a -> ă
                6 -> 8  // o -> ơ
                9 -> 10 // u -> ư
                else -> null
            }
            'e' -> when (getVowelRow(vowel)) {
                3 -> 4 // e -> ê
                else -> null
            }
            'o' -> when (getVowelRow(vowel)) {
                6 -> 7 // o -> ô
                else -> null
            }
            else -> null
        } ?: return null

        val matrix = if (isUpper) UPPER_VOWEL_MATRIX else LOWER_VOWEL_MATRIX
        return matrix[targetRow][tone]
    }

    /**
     * Reverts a transformed vowel back to base (e.g. â -> a, ô -> o).
     */
    fun revertVowel(vowel: Char): Char? {
        val row = getVowelRow(vowel)
        val isUpper = vowel.isUpperCase()
        val tone = getCharTone(vowel)
        val matrix = if (isUpper) UPPER_VOWEL_MATRIX else LOWER_VOWEL_MATRIX

        val baseRow = when (row) {
            1 -> 0 // ă -> a
            2 -> 0 // â -> a
            4 -> 3 // ê -> e
            7 -> 6 // ô -> o
            8 -> 6 // ơ -> o
            10 -> 9 // ư -> u
            else -> null
        } ?: return null

        return matrix[baseRow][tone]
    }
}
