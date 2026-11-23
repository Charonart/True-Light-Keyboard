package com.truelight.ime.engine

/**
 * Zero-allocation pass-through word buffer for alphabetic languages
 * (English, French, German, Spanish, Russian).
 * Tracks the active composing word so Text Suggestions and Auto-correct
 * work consistently across all Group 1 languages.
 */
class SimpleWordEngine : InputEngine {

    companion object {
        private const val MAX_WORD_LENGTH = 64
    }

    private val buffer = CharArray(MAX_WORD_LENGTH)
    private var bufferLength = 0
    private val cachedResult = InputEngine.StepResult()
    private val sb = StringBuilder(MAX_WORD_LENGTH)

    override fun processChar(c: Char): InputEngine.StepResult {
        if (!c.isLetter() && c != '\'') {
            cachedResult.consumed = false
            cachedResult.text = getComposingText()
            cachedResult.isWordTerminated = true
            reset()
            return cachedResult
        }

        if (bufferLength >= MAX_WORD_LENGTH - 2) {
            cachedResult.consumed = false
            cachedResult.text = getComposingText()
            cachedResult.isWordTerminated = true
            reset()
            return cachedResult
        }

        buffer[bufferLength++] = c
        cachedResult.consumed = true
        cachedResult.text = getComposingText()
        cachedResult.isWordTerminated = false
        return cachedResult
    }

    override fun handleBackspace(): Boolean {
        if (bufferLength == 0) return false
        bufferLength--
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
    }
}
