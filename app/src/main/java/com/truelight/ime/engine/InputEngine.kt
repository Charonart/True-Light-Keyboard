package com.truelight.ime.engine

/**
 * Pluggable InputEngine interface for True Light.
 * Supports Group 1 rule-based engines (Simple, Telex, VNI, Hangul)
 * and leaves a clean extension point for future Group 2 engines (Pinyin, Kana-Kanji).
 */
interface InputEngine {

    /**
     * Reusable step result carrier to avoid per-keystroke allocations.
     */
    class StepResult {
        var consumed: Boolean = false
        var text: String = ""
        var isWordTerminated: Boolean = false
    }

    /** Whether this engine handles digit keys (e.g. Vietnamese VNI uses 0-9 for tones/diacritics). */
    val acceptsDigits: Boolean get() = false

    fun processChar(c: Char): StepResult
    fun handleBackspace(): Boolean
    fun getComposingText(): String
    fun length(): Int
    fun isEmpty(): Boolean
    fun reset()
}
