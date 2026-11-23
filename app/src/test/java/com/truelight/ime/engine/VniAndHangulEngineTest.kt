package com.truelight.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VniAndHangulEngineTest {

    private fun typeWith(engine: InputEngine, input: String): String {
        engine.reset()
        var text = ""
        for (c in input) {
            val res = engine.processChar(c)
            text = res.text
        }
        return text
    }

    /**
     * Mirrors TrueLightIME.onChar() commit flow so mixed alphanumeric tokens
     * (e.g. "v1", "mp3", "h2o", "chrome1", "android9", "user123", "covid19")
     * verify full committed + composing output accurately.
     */
    private fun typeStreamLikeIme(engine: InputEngine, input: String): String {
        engine.reset()
        val committed = StringBuilder()
        for (c in input) {
            val canProcess = c.isLetter() || (engine.acceptsDigits && c.isDigit() && engine.length() > 0)
            if (canProcess) {
                val res = engine.processChar(c)
                if (res.isWordTerminated) {
                    if (res.text.isNotEmpty()) {
                        committed.append(res.text)
                    }
                    val retry = if (c.isLetter()) engine.processChar(c) else null
                    if (retry == null || retry.isWordTerminated) {
                        committed.append(c)
                    }
                }
            } else {
                if (engine.length() > 0) {
                    committed.append(engine.getComposingText())
                    engine.reset()
                }
                committed.append(c)
            }
        }
        if (engine.length() > 0) {
            committed.append(engine.getComposingText())
        }
        return committed.toString()
    }

    @Test
    fun testVniTypingAndTones() {
        val vni = VniFsmEngine()
        assertEquals("tiếng", typeWith(vni, "tie6ng1"))
        assertEquals("việt", typeWith(vni, "vie6t5"))
        assertEquals("việt", typeWith(vni, "vie65t"))
        assertEquals("được", typeWith(vni, "d9u7o7c5"))
        assertEquals("lưu", typeWith(vni, "lu7u"))
        assertEquals("toán", typeWith(vni, "toan1"))
        assertEquals("hòa", typeWith(vni, "hoa2"))
        assertEquals("hoàn", typeWith(vni, "hoa2n"))
        assertEquals("đường", typeWith(vni, "d9u7o7ng2"))
        assertEquals("đường", typeWith(vni, "d9uong72"))
        assertEquals("chuối", typeWith(vni, "chuo61i"))
        assertEquals("bao", typeWith(vni, "bao10"))
        assertEquals("toan1", typeWith(vni, "toan11"))
        assertEquals("luu7", typeWith(vni, "luu77"))

        vni.modernTonePlacement = true
        assertEquals("hoà", typeWith(vni, "hoa2"))
        assertEquals("thuý", typeWith(vni, "thuy1"))
    }

    @Test
    fun testVniMixedAlphanumericAndForeignWords() {
        val vni = VniFsmEngine()
        vni.autoRestoreEnabled = true

        val mixedTokens = listOf(
            "v1", "mp3", "h2o", "chrome1", "android9", "user123", "covid19"
        )
        for (token in mixedTokens) {
            assertEquals("VNI corrupted alphanumeric token '$token'", token, typeStreamLikeIme(vni, token))
        }
    }

    @Test
    fun testVniCompoundUuHornAndReversion() {
        val vni = VniFsmEngine()
        assertEquals("lưu", typeWith(vni, "luu7"))
        assertEquals("hưu", typeWith(vni, "huu7"))
        assertEquals("cứu", typeWith(vni, "cuu71"))
        assertEquals("cứu", typeWith(vni, "cuu17"))
        assertEquals("luu7", typeWith(vni, "luu77"))
    }

    @Test
    fun testVniLongWordOverflowTerminatesCleanly() {
        val vni = VniFsmEngine()
        val chunk = "b".repeat(62)
        for (c in chunk) {
            val res = vni.processChar(c)
            assertTrue(res.consumed)
            assertFalse(res.isWordTerminated)
        }
        assertEquals(62, vni.length())

        val overflowRes = vni.processChar('x')
        assertFalse(overflowRes.consumed)
        assertTrue(overflowRes.isWordTerminated)
        assertEquals(chunk, overflowRes.text)
        assertTrue(vni.isEmpty())

        val freshRes = vni.processChar('x')
        assertTrue(freshRes.consumed)
        assertFalse(freshRes.isWordTerminated)
        assertEquals("x", freshRes.text)
    }

    @Test
    fun testHangulCompositionAndBackspace() {
        val hangul = HangulEngine()
        assertEquals("한글", typeWith(hangul, "ㅎㅏㄴㄱㅡㄹ"))
        assertEquals("과", typeWith(hangul, "ㄱㅗㅏ"))
        assertEquals("닭", typeWith(hangul, "ㄷㅏㄹㄱ"))
        assertEquals("달가", typeWith(hangul, "ㄷㅏㄹㄱㅏ"))

        // Step-by-step backspace on compound jongseong 닭 -> 달 -> 다 -> ㄷ -> ""
        typeWith(hangul, "ㄷㅏㄹㄱ")
        hangul.handleBackspace()
        assertEquals("달", hangul.getComposingText())
        hangul.handleBackspace()
        assertEquals("다", hangul.getComposingText())
        hangul.handleBackspace()
        assertEquals("ㄷ", hangul.getComposingText())
        hangul.handleBackspace()
        assertEquals("", hangul.getComposingText())
    }
}
