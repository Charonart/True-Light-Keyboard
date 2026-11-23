package com.truelight.ime.engine

/**
 * Pure-math, zero-allocation Korean 2-Bulsik Hangul Automaton.
 * Composes Unicode Hangul syllables (0xAC00 + (cho * 21 + jung) * 28 + jong)
 * with compound vowels, compound finals (jongseong), final splitting, and step-by-step backspace.
 */
class HangulEngine : InputEngine {

    companion object {
        private const val MAX_WORD_LENGTH = 64
        private const val HANGUL_BASE = 0xAC00

        private val CHO = charArrayOf(
            'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ',
            'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
        )
        private val JUNG = charArrayOf(
            'ㅏ', 'ㅐ', 'ㅑ', 'ㅒ', 'ㅓ', 'ㅔ', 'ㅕ', 'ㅖ', 'ㅗ', 'ㅘ',
            'ㅙ', 'ㅚ', 'ㅛ', 'ㅜ', 'ㅝ', 'ㅞ', 'ㅟ', 'ㅠ', 'ㅡ', 'ㅢ', 'ㅣ'
        )
        private val JONG = charArrayOf(
            '\u0000', 'ㄱ', 'ㄲ', 'ㄳ', 'ㄴ', 'ㄵ', 'ㄶ', 'ㄷ', 'ㄹ', 'ㄺ',
            'ㄻ', 'ㄼ', 'ㄽ', 'ㄾ', 'ㄿ', 'ㅀ', 'ㅁ', 'ㅂ', 'ㅄ', 'ㅅ',
            'ㅆ', 'ㅇ', 'ㅈ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
        )
    }

    private val committed = CharArray(MAX_WORD_LENGTH)
    private var committedLen = 0

    // Current active Hangul syllable state (-1 means empty)
    private var cho = -1
    private var jung = -1
    private var jong = 0 // 0 means no jongseong

    private val cachedResult = InputEngine.StepResult()
    private val sb = StringBuilder(MAX_WORD_LENGTH)

    override fun processChar(c: Char): InputEngine.StepResult {
        val choIdx = CHO.indexOf(c)
        val jungIdx = JUNG.indexOf(c)

        if (choIdx < 0 && jungIdx < 0) {
            cachedResult.consumed = false
            cachedResult.text = getComposingText()
            cachedResult.isWordTerminated = true
            reset()
            return cachedResult
        }

        if (committedLen >= MAX_WORD_LENGTH - 2) {
            cachedResult.consumed = false
            cachedResult.text = getComposingText()
            cachedResult.isWordTerminated = true
            reset()
            return cachedResult
        }

        if (choIdx >= 0) {
            handleConsonant(c, choIdx)
        } else {
            handleVowel(jungIdx)
        }

        cachedResult.consumed = true
        cachedResult.text = getComposingText()
        cachedResult.isWordTerminated = false
        return cachedResult
    }

    private fun handleConsonant(jamo: Char, choIdx: Int) {
        if (cho < 0 && jung < 0) {
            // Start new syllable with initial consonant
            cho = choIdx
            return
        }
        if (cho >= 0 && jung < 0) {
            // Standalone consonant followed by another consonant -> flush previous
            flushCurrent()
            cho = choIdx
            return
        }
        if (cho >= 0 && jung >= 0 && jong == 0) {
            val jongIdx = JONG.indexOf(jamo)
            if (jongIdx > 0) {
                jong = jongIdx
            } else {
                // Consonant cannot be jongseong (e.g. ㄸ, ㅃ, ㅉ) -> flush and start new
                flushCurrent()
                cho = choIdx
            }
            return
        }
        if (cho >= 0 && jung >= 0 && jong > 0) {
            val combined = combineJong(jong, jamo)
            if (combined > 0) {
                jong = combined
            } else {
                flushCurrent()
                cho = choIdx
            }
            return
        }
        // Standalone vowel + consonant
        flushCurrent()
        cho = choIdx
    }

    private fun handleVowel(jungIdx: Int) {
        if (cho < 0 && jung < 0) {
            // Standalone vowel
            jung = jungIdx
            return
        }
        if (cho >= 0 && jung < 0) {
            // Initial consonant + vowel
            jung = jungIdx
            return
        }
        if (cho >= 0 && jung >= 0 && jong == 0) {
            val combined = combineJung(jung, jungIdx)
            if (combined >= 0) {
                jung = combined
            } else {
                flushCurrent()
                jung = jungIdx
            }
            return
        }
        if (cho >= 0 && jung >= 0 && jong > 0) {
            // Split jongseong: move trailing consonant to next syllable's initial
            val split = splitJong(jong)
            if (split != null) {
                jong = split.first
                flushCurrent()
                cho = CHO.indexOf(split.second)
                jung = jungIdx
            } else {
                val movedJamo = JONG[jong]
                jong = 0
                flushCurrent()
                cho = CHO.indexOf(movedJamo)
                jung = jungIdx
            }
            return
        }
        // Standalone vowel + vowel
        val combined = combineJung(jung, jungIdx)
        if (combined >= 0) {
            jung = combined
        } else {
            flushCurrent()
            jung = jungIdx
        }
    }

    override fun handleBackspace(): Boolean {
        if (cho >= 0 || jung >= 0) {
            if (jong > 0) {
                val split = splitJong(jong)
                jong = split?.first ?: 0
            } else if (jung >= 0) {
                val splitV = splitJung(jung)
                if (splitV >= 0) {
                    jung = splitV
                } else {
                    jung = -1
                }
            } else {
                cho = -1
            }
            cachedResult.consumed = true
            cachedResult.text = getComposingText()
            cachedResult.isWordTerminated = false
            return true
        }
        if (committedLen > 0) {
            committedLen--
            cachedResult.consumed = true
            cachedResult.text = getComposingText()
            cachedResult.isWordTerminated = false
            return true
        }
        return false
    }

    override fun getComposingText(): String {
        sb.setLength(0)
        for (i in 0 until committedLen) {
            sb.append(committed[i])
        }
        val cur = currentChar()
        if (cur != '\u0000') {
            sb.append(cur)
        }
        return sb.toString()
    }

    override fun length(): Int = committedLen + if (currentChar() != '\u0000') 1 else 0

    override fun isEmpty(): Boolean = committedLen == 0 && cho < 0 && jung < 0

    override fun reset() {
        committedLen = 0
        cho = -1
        jung = -1
        jong = 0
    }

    private fun currentChar(): Char {
        if (cho >= 0 && jung >= 0) {
            return (HANGUL_BASE + (cho * 21 + jung) * 28 + jong).toChar()
        }
        if (cho >= 0) return CHO[cho]
        if (jung >= 0) return JUNG[jung]
        return '\u0000'
    }

    private fun flushCurrent() {
        val cur = currentChar()
        if (cur != '\u0000' && committedLen < MAX_WORD_LENGTH) {
            committed[committedLen++] = cur
        }
        cho = -1
        jung = -1
        jong = 0
    }

    private fun combineJung(first: Int, second: Int): Int {
        return when {
            first == 8 && second == 0 -> 9   // ㅗ + ㅏ -> ㅘ
            first == 8 && second == 1 -> 10  // ㅗ + ㅐ -> ㅙ
            first == 8 && second == 20 -> 11 // ㅗ + ㅣ -> ㅚ
            first == 13 && second == 4 -> 14 // ㅜ + ㅓ -> ㅝ
            first == 13 && second == 5 -> 15 // ㅜ + ㅔ -> ㅞ
            first == 13 && second == 20 -> 16 // ㅜ + ㅣ -> ㅟ
            first == 18 && second == 20 -> 19 // ㅡ + ㅣ -> ㅢ
            else -> -1
        }
    }

    private fun splitJung(compound: Int): Int {
        return when (compound) {
            9, 10, 11 -> 8   // ㅘ, ㅙ, ㅚ -> ㅗ
            14, 15, 16 -> 13 // ㅝ, ㅞ, ㅟ -> ㅜ
            19 -> 18         // ㅢ -> ㅡ
            else -> -1
        }
    }

    private fun combineJong(firstJong: Int, nextConsonant: Char): Int {
        return when (firstJong) {
            1 -> if (nextConsonant == 'ㅅ') 3 else 0  // ㄱ+ㅅ = ㄳ
            4 -> when (nextConsonant) {
                'ㅈ' -> 5  // ㄴ+ㅈ = ㄵ
                'ㅎ' -> 6  // ㄴ+ㅎ = ㄶ
                else -> 0
            }
            8 -> when (nextConsonant) {
                'ㄱ' -> 9  // ㄹ+ㄱ = ㄺ
                'ㅁ' -> 10 // ㄹ+ㅁ = ㄻ
                'ㅂ' -> 11 // ㄹ+ㅂ = ㄼ
                'ㅅ' -> 12 // ㄹ+ㅅ = ㄽ
                'ㅌ' -> 13 // ㄹ+ㅌ = ㄾ
                'ㅍ' -> 14 // ㄹ+ㅍ = ㄿ
                'ㅎ' -> 15 // ㄹ+ㅎ = ㅀ
                else -> 0
            }
            17 -> if (nextConsonant == 'ㅅ') 18 else 0 // ㅂ+ㅅ = ㅄ
            else -> 0
        }
    }

    private fun splitJong(compoundJong: Int): Pair<Int, Char>? {
        return when (compoundJong) {
            3 -> 1 to 'ㅅ'
            5 -> 4 to 'ㅈ'
            6 -> 4 to 'ㅎ'
            9 -> 8 to 'ㄱ'
            10 -> 8 to 'ㅁ'
            11 -> 8 to 'ㅂ'
            12 -> 8 to 'ㅅ'
            13 -> 8 to 'ㅌ'
            14 -> 8 to 'ㅍ'
            15 -> 8 to 'ㅎ'
            18 -> 17 to 'ㅅ'
            else -> null
        }
    }
}
