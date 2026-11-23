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

    @Test
    fun testVniTypingAndTones() {
        val vni = VniFsmEngine()
        assertEquals("toán", typeWith(vni, "toan1"))
        assertEquals("hòa", typeWith(vni, "hoa2"))
        assertEquals("hoàn", typeWith(vni, "hoa2n"))
        assertEquals("việt", typeWith(vni, "vie65t"))
        assertEquals("đường", typeWith(vni, "d9u7o7ng2"))
        assertEquals("đường", typeWith(vni, "d9uong72"))
        assertEquals("chuối", typeWith(vni, "chuo61i"))
        assertEquals("bao", typeWith(vni, "bao10"))
        assertEquals("toan1", typeWith(vni, "toan11"))

        vni.modernTonePlacement = true
        assertEquals("hoà", typeWith(vni, "hoa2"))
        assertEquals("thuý", typeWith(vni, "thuy1"))
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
