package com.truelight.ime.config

import java.util.Locale

data class CssThemeOverrides(
    val bg: Int? = null,
    val stripBg: Int? = null,
    val keyBg: Int? = null,
    val keySpecialBg: Int? = null,
    val keyPressedBg: Int? = null,
    val keyBorder: Int? = null,
    val keyBorderWidthDp: Float? = null,
    val keyRadiusDp: Float? = null,
    val keyGapHDp: Float? = null,
    val keyGapVDp: Float? = null,
    val keyShadowDp: Float? = null,
    val textColor: Int? = null,
    val subtextColor: Int? = null,
    val accent: Int? = null,
    val textOnAccent: Int? = null,
    val fontSizeSp: Float? = null
)

/**
 * Pure Kotlin lightweight CSS-like theme parser.
 * Zero external libraries; never throws on malformed user input.
 */
object CssThemeParser {

    const val DEFAULT_CSS_TEMPLATE: String = """keyboard {
  bg: #111318;
  strip-bg: #0D0F14;
  key-bg: #1C1F26;
  key-special-bg: #272B35;
  key-pressed-bg: #3A4152;
  key-border: #2F3442;
  key-border-width: 1dp;
  key-radius: 5dp;
  key-gap-h: 3dp;
  key-gap-v: 4dp;
  key-shadow: 1.5dp;
  text-color: #F4F6FA;
  subtext-color: #8A91A0;
  accent: #4F8CFF;
  text-on-accent: #FFFFFF;
  font-size: 20sp;
}"""

    fun parse(css: String): CssThemeOverrides {
        if (css.isBlank()) return CssThemeOverrides()

        val cleaned = stripComments(css)
        var bg: Int? = null
        var stripBg: Int? = null
        var keyBg: Int? = null
        var keySpecialBg: Int? = null
        var keyPressedBg: Int? = null
        var keyBorder: Int? = null
        var keyBorderWidthDp: Float? = null
        var keyRadiusDp: Float? = null
        var keyGapHDp: Float? = null
        var keyGapVDp: Float? = null
        var keyShadowDp: Float? = null
        var textColor: Int? = null
        var subtextColor: Int? = null
        var accent: Int? = null
        var textOnAccent: Int? = null
        var fontSizeSp: Float? = null

        val len = cleaned.length
        var start = 0
        while (start < len) {
            var end = start
            while (end < len && cleaned[end] != ';' && cleaned[end] != '{' && cleaned[end] != '}') {
                end++
            }
            if (end > start) {
                val colon = cleaned.indexOf(':', start)
                if (colon in (start + 1) until end) {
                    val key = cleaned.substring(start, colon)
                        .trim()
                        .removePrefix("--")
                        .lowercase(Locale.ROOT)
                    val value = cleaned.substring(colon + 1, end).trim()
                    if (value.isNotEmpty()) {
                        when (key) {
                            "bg", "background" -> parseColor(value)?.let { bg = it }
                            "strip-bg" -> parseColor(value)?.let { stripBg = it }
                            "key-bg" -> parseColor(value)?.let { keyBg = it }
                            "key-special-bg" -> parseColor(value)?.let { keySpecialBg = it }
                            "key-pressed-bg" -> parseColor(value)?.let { keyPressedBg = it }
                            "key-border" -> parseColor(value)?.let { keyBorder = it }
                            "key-border-width" -> parseNonNegFloat(value)?.let { keyBorderWidthDp = it }
                            "key-radius" -> parseNonNegFloat(value)?.let { keyRadiusDp = it }
                            "key-gap-h" -> parseNonNegFloat(value)?.let { keyGapHDp = it }
                            "key-gap-v" -> parseNonNegFloat(value)?.let { keyGapVDp = it }
                            "key-shadow" -> parseNonNegFloat(value)?.let { keyShadowDp = it }
                            "text-color" -> parseColor(value)?.let { textColor = it }
                            "subtext-color" -> parseColor(value)?.let { subtextColor = it }
                            "accent" -> parseColor(value)?.let { accent = it }
                            "text-on-accent" -> parseColor(value)?.let { textOnAccent = it }
                            "font-size" -> parsePositiveFloat(value)?.let { fontSizeSp = it }
                        }
                    }
                }
            }
            start = end + 1
        }

        return CssThemeOverrides(
            bg = bg,
            stripBg = stripBg,
            keyBg = keyBg,
            keySpecialBg = keySpecialBg,
            keyPressedBg = keyPressedBg,
            keyBorder = keyBorder,
            keyBorderWidthDp = keyBorderWidthDp,
            keyRadiusDp = keyRadiusDp,
            keyGapHDp = keyGapHDp,
            keyGapVDp = keyGapVDp,
            keyShadowDp = keyShadowDp,
            textColor = textColor,
            subtextColor = subtextColor,
            accent = accent,
            textOnAccent = textOnAccent,
            fontSizeSp = fontSizeSp
        )
    }

    private fun stripComments(input: String): String {
        val len = input.length
        val sb = StringBuilder(len)
        var i = 0
        while (i < len) {
            if (i + 1 < len && input[i] == '/' && input[i + 1] == '*') {
                i += 2
                while (i + 1 < len && !(input[i] == '*' && input[i + 1] == '/')) {
                    i++
                }
                i = if (i + 1 < len) i + 2 else len
            } else if (i + 1 < len && input[i] == '/' && input[i + 1] == '/') {
                i += 2
                while (i < len && input[i] != '\n') {
                    i++
                }
            } else {
                sb.append(input[i])
                i++
            }
        }
        return sb.toString()
    }

    private fun parseColor(raw: String): Int? {
        val v = raw.trim()
        if (v.equals("transparent", ignoreCase = true)) return 0x00000000
        if (!v.startsWith("#")) return null
        val hex = v.substring(1)
        return when (hex.length) {
            3 -> {
                val r = hexDigit(hex[0])
                val g = hexDigit(hex[1])
                val b = hexDigit(hex[2])
                if (r < 0 || g < 0 || b < 0) null
                else (0xFF shl 24) or ((r * 17) shl 16) or ((g * 17) shl 8) or (b * 17)
            }
            4 -> {
                val a = hexDigit(hex[0])
                val r = hexDigit(hex[1])
                val g = hexDigit(hex[2])
                val b = hexDigit(hex[3])
                if (a < 0 || r < 0 || g < 0 || b < 0) null
                else ((a * 17) shl 24) or ((r * 17) shl 16) or ((g * 17) shl 8) or (b * 17)
            }
            6 -> {
                val rgb = parseHexLong(hex) ?: return null
                (0xFF000000L or rgb).toInt()
            }
            8 -> {
                val argb = parseHexLong(hex) ?: return null
                argb.toInt()
            }
            else -> null
        }
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private fun parseHexLong(hex: String): Long? {
        var result = 0L
        for (i in hex.indices) {
            val d = hexDigit(hex[i])
            if (d < 0) return null
            result = (result shl 4) or d.toLong()
        }
        return result
    }

    private fun parseNonNegFloat(raw: String): Float? {
        val value = parseFloatValue(raw) ?: return null
        return if (value.isFinite() && value >= 0f) value else null
    }

    private fun parsePositiveFloat(raw: String): Float? {
        val value = parseFloatValue(raw) ?: return null
        return if (value.isFinite() && value > 0f) value else null
    }

    private fun parseFloatValue(raw: String): Float? {
        val lower = raw.trim().lowercase(Locale.ROOT)
        val numStr = when {
            lower.endsWith("dip") -> lower.dropLast(3)
            lower.endsWith("dp") -> lower.dropLast(2)
            lower.endsWith("sp") -> lower.dropLast(2)
            lower.endsWith("px") -> lower.dropLast(2)
            else -> lower
        }.trim()
        return numStr.toFloatOrNull()
    }
}
