package com.truelight.ime.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CssThemeParserTest {

    @Test
    fun testDefaultCssTemplateParsesAllProperties() {
        val overrides = CssThemeParser.parse(CssThemeParser.DEFAULT_CSS_TEMPLATE)

        assertEquals(0xFF111318.toInt(), overrides.bg)
        assertEquals(0xFF0D0F14.toInt(), overrides.stripBg)
        assertEquals(0xFF1C1F26.toInt(), overrides.keyBg)
        assertEquals(0xFF272B35.toInt(), overrides.keySpecialBg)
        assertEquals(0xFF3A4152.toInt(), overrides.keyPressedBg)
        assertEquals(0xFF2F3442.toInt(), overrides.keyBorder)
        assertEquals(1f, overrides.keyBorderWidthDp!!, 0.001f)
        assertEquals(5f, overrides.keyRadiusDp!!, 0.001f)
        assertEquals(3f, overrides.keyGapHDp!!, 0.001f)
        assertEquals(4f, overrides.keyGapVDp!!, 0.001f)
        assertEquals(1.5f, overrides.keyShadowDp!!, 0.001f)
        assertEquals(0xFFF4F6FA.toInt(), overrides.textColor)
        assertEquals(0xFF8A91A0.toInt(), overrides.subtextColor)
        assertEquals(0xFF4F8CFF.toInt(), overrides.accent)
        assertEquals(0xFFFFFFFF.toInt(), overrides.textOnAccent)
        assertEquals(20f, overrides.fontSizeSp!!, 0.001f)
    }

    @Test
    fun testHexColorFormatsAndTransparent() {
        val css = """
            keyboard {
              bg: #f0a;
              strip-bg: #8f0a;
              key-bg: #123456;
              key-special-bg: #80ABCDEF;
              key-border: transparent;
            }
        """.trimIndent()

        val overrides = CssThemeParser.parse(css)
        assertEquals(0xFFFF00AA.toInt(), overrides.bg)
        assertEquals(0x88FF00AA.toInt(), overrides.stripBg)
        assertEquals(0xFF123456.toInt(), overrides.keyBg)
        assertEquals(0x80ABCDEF.toInt(), overrides.keySpecialBg)
        assertEquals(0x00000000, overrides.keyBorder)
    }

    @Test
    fun testUnitsDpSpPxAndAliases() {
        val css = """
            :root {
              --background: #010203;
              --key-border-width: 0.5dp;
              key-radius: 8px;
              key-gap-h: 2.5dip;
              key-gap-v: 0;
              key-shadow: 2.25dp;
              font-size: 18.5sp;
            }
        """.trimIndent()

        val overrides = CssThemeParser.parse(css)
        assertEquals(0xFF010203.toInt(), overrides.bg)
        assertEquals(0.5f, overrides.keyBorderWidthDp!!, 0.001f)
        assertEquals(8f, overrides.keyRadiusDp!!, 0.001f)
        assertEquals(2.5f, overrides.keyGapHDp!!, 0.001f)
        assertEquals(0f, overrides.keyGapVDp!!, 0.001f)
        assertEquals(2.25f, overrides.keyShadowDp!!, 0.001f)
        assertEquals(18.5f, overrides.fontSizeSp!!, 0.001f)
    }

    @Test
    fun testBlockAndLineComments() {
        val css = """
            /* Top header comment
               bg: #FF0000; */
            keyboard {
              // Line comment should be ignored: accent: #00FF00;
              bg: #102030; // trailing comment
              /* inline comment */ accent: #ABCDEF;
              key-radius: /* mid */ 6dp;
            }
            /* unterminated block comment at EOF
               text-color: #111111;
        """.trimIndent()

        val overrides = CssThemeParser.parse(css)
        assertEquals(0xFF102030.toInt(), overrides.bg)
        assertEquals(0xFFABCDEF.toInt(), overrides.accent)
        assertEquals(6f, overrides.keyRadiusDp!!, 0.001f)
        assertNull(overrides.textColor)
    }

    @Test
    fun testMalformedAndEmptyCssSafety() {
        assertEquals(CssThemeOverrides(), CssThemeParser.parse(""))
        assertEquals(CssThemeOverrides(), CssThemeParser.parse("   \n\t  "))

        val malformed = """
            keyboard {{{
              bg: not-a-color;
              strip-bg: #12345;
              key-bg: #ZZZZZZ;
              key-radius: -4dp;
              font-size: 0sp;
              key-shadow: NaN;
              unknown-prop: 999dp;
              ::::;;;
              accent: #00ff88
            }
        """.trimIndent()

        val overrides = CssThemeParser.parse(malformed)
        assertNull(overrides.bg)
        assertNull(overrides.stripBg)
        assertNull(overrides.keyBg)
        assertNull(overrides.keyRadiusDp)
        assertNull(overrides.fontSizeSp)
        assertNull(overrides.keyShadowDp)
        // Valid declaration without trailing semicolon still parses cleanly
        assertEquals(0xFF00FF88.toInt(), overrides.accent)
    }
}
