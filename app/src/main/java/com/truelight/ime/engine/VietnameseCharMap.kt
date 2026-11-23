package com.truelight.ime.engine

/**
 * Static O(1) lookup tables and constants for Vietnamese character transformations.
 * Zero-allocation, high-performance lookup arrays (~608 bytes total).
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

    // Packed metadata per char: 0 = not a Vietnamese vowel
    // bits 0..3: row + 1 (1..12)
    // bits 4..6: tone (0..5)
    // bit  7   : isUpper (0x80)
    private const val EXT_BASE = 0x1EA0
    private const val EXT_SIZE = 0x60 // 0x1EA0..0x1EFF
    private val META_LOW = ByteArray(0x0200)
    private val META_EXT = ByteArray(EXT_SIZE)

    init {
        for (row in LOWER_VOWEL_MATRIX.indices) {
            val lowerRow = LOWER_VOWEL_MATRIX[row]
            val upperRow = UPPER_VOWEL_MATRIX[row]
            for (tone in lowerRow.indices) {
                putMeta(lowerRow[tone], ((row + 1) or (tone shl 4)).toByte())
                putMeta(upperRow[tone], ((row + 1) or (tone shl 4) or 0x80).toByte())
            }
        }
    }

    private fun putMeta(c: Char, packed: Byte) {
        val code = c.code
        if (code < 0x0200) {
            META_LOW[code] = packed
        } else if (code in EXT_BASE until (EXT_BASE + EXT_SIZE)) {
            META_EXT[code - EXT_BASE] = packed
        }
    }

    private fun lookupMeta(c: Char): Int {
        val code = c.code
        return when {
            code < 0x0200 -> META_LOW[code].toInt() and 0xFF
            code in EXT_BASE until (EXT_BASE + EXT_SIZE) -> META_EXT[code - EXT_BASE].toInt() and 0xFF
            else -> 0
        }
    }

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

    fun isVowel(c: Char): Boolean = lookupMeta(c) != 0

    /**
     * Returns the vowel family index (0..11) or -1 if not a Vietnamese vowel. O(1).
     */
    fun getVowelRow(c: Char): Int {
        val m = lookupMeta(c)
        return if (m == 0) -1 else (m and 0x0F) - 1
    }

    /**
     * Returns the tone index (0..5) of the given char, or 0 if none. O(1).
     */
    fun getCharTone(c: Char): Int {
        val m = lookupMeta(c)
        return if (m == 0) TONE_NONE else (m ushr 4) and 0x07
    }

    /**
     * Replaces tone of vowel c with newTone (0..5), preserving casing. O(1).
     */
    fun applyTone(c: Char, newTone: Int): Char {
        if (newTone !in 0..5) return c
        val m = lookupMeta(c)
        if (m == 0) return c
        val row = (m and 0x0F) - 1
        val isUpper = (m and 0x80) != 0
        return if (isUpper) UPPER_VOWEL_MATRIX[row][newTone] else LOWER_VOWEL_MATRIX[row][newTone]
    }

    /**
     * Transform base vowel by modification key ('a', 'w', 'e', 'o').
     * Returns transformed char preserving tone and case, or null if no transform.
     */
    fun transformVowel(vowel: Char, key: Char): Char? {
        val m = lookupMeta(vowel)
        if (m == 0) return null
        val row = (m and 0x0F) - 1
        val tone = (m ushr 4) and 0x07
        val isUpper = (m and 0x80) != 0

        val targetRow = when (key.lowercaseChar()) {
            'a' -> if (row == 0) 2 else -1 // a -> â
            'w' -> when (row) {
                0 -> 1  // a -> ă
                6 -> 8  // o -> ơ
                9 -> 10 // u -> ư
                else -> -1
            }
            'e' -> if (row == 3) 4 else -1 // e -> ê
            'o' -> if (row == 6) 7 else -1 // o -> ô
            else -> -1
        }
        if (targetRow < 0) return null

        val matrix = if (isUpper) UPPER_VOWEL_MATRIX else LOWER_VOWEL_MATRIX
        return matrix[targetRow][tone]
    }

    /**
     * Reverts a transformed vowel back to base (e.g. â -> a, ô -> o).
     */
    fun revertVowel(vowel: Char): Char? {
        val m = lookupMeta(vowel)
        if (m == 0) return null
        val row = (m and 0x0F) - 1
        val tone = (m ushr 4) and 0x07
        val isUpper = (m and 0x80) != 0

        val baseRow = when (row) {
            1 -> 0 // ă -> a
            2 -> 0 // â -> a
            4 -> 3 // ê -> e
            7 -> 6 // ô -> o
            8 -> 6 // ơ -> o
            10 -> 9 // ư -> u
            else -> -1
        }
        if (baseRow < 0) return null

        val matrix = if (isUpper) UPPER_VOWEL_MATRIX else LOWER_VOWEL_MATRIX
        return matrix[baseRow][tone]
    }
}
