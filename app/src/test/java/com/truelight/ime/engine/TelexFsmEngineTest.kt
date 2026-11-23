package com.truelight.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TelexFsmEngineTest {

    private lateinit var engine: TelexFsmEngine

    @Before
    fun setUp() {
        engine = TelexFsmEngine()
    }

    private fun typeWord(word: String): String {
        engine.reset()
        var text = ""
        for (c in word) {
            val res = engine.processChar(c)
            text = res.text
        }
        return text
    }

    @Test
    fun testBasicTonePlacement() {
        assertEquals("toán", typeWord("toans"))
        assertEquals("hòa", typeWord("hoaf"))
        assertEquals("hoàn", typeWord("hoafn"))
        assertEquals("thúy", typeWord("thuys"))
        assertEquals("việt", typeWord("vieejt"))
        assertEquals("đường", typeWord("dduwowngf"))
        assertEquals("đường", typeWord("dduongwf"))
        assertEquals("chuối", typeWord("chuoosi"))
        assertEquals("nguyễn", typeWord("nguyeenx"))
        assertEquals("người", typeWord("nguwowif"))
        assertEquals("người", typeWord("nguoiwf"))
        assertEquals("trời", typeWord("troiwf"))
        assertEquals("gửi", typeWord("guiwr"))
        assertEquals("quá", typeWord("quas"))
        assertEquals("quý", typeWord("quys"))
        assertEquals("giá", typeWord("gias"))
        assertEquals("gì", typeWord("gif"))
    }

    @Test
    fun testVowelModifications() {
        assertEquals("ă", typeWord("aw"))
        assertEquals("â", typeWord("aa"))
        assertEquals("ê", typeWord("ee"))
        assertEquals("ô", typeWord("oo"))
        assertEquals("ơ", typeWord("ow"))
        assertEquals("ư", typeWord("uw"))
        assertEquals("đ", typeWord("dd"))
    }

    @Test
    fun testToneRemovalWithZ() {
        assertEquals("bao", typeWord("baosz"))
        assertEquals("nguyên", typeWord("nguyeenrz"))
    }

    @Test
    fun testReversionOnRepeatKey() {
        // Typing 's' on already accented 'á' reverts to 'as'
        assertEquals("toas", typeWord("toass"))
        // Repeating modifier key reverts circumflex / stroke
        assertEquals("aa", typeWord("aaa"))
        assertEquals("ee", typeWord("eee"))
        assertEquals("oo", typeWord("ooo"))
        assertEquals("dd", typeWord("ddd"))
        assertEquals("uow", typeWord("uoww"))
    }

    @Test
    fun testBackspaceHandling() {
        typeWord("hoa")
        engine.handleBackspace()
        assertEquals("ho", engine.getComposingText())
    }

    @Test
    fun testModernTonePlacementOption() {
        engine.modernTonePlacement = true
        assertEquals("hoà", typeWord("hoaf"))
        assertEquals("hoè", typeWord("hoef"))
        assertEquals("thuý", typeWord("thuys"))
        assertEquals("toán", typeWord("toans"))
    }

    @Test
    fun testCompoundUuHornAndReversion() {
        assertEquals("lưu", typeWord("luuw"))
        assertEquals("hưu", typeWord("huuw"))
        assertEquals("cứu", typeWord("cuuws"))
        assertEquals("cứu", typeWord("cuusw"))
        assertEquals("luuw", typeWord("luuww"))
    }

    @Test
    fun testEnglishAndLoanwordProtection() {
        assertEquals("new", typeWord("new"))
        assertEquals("view", typeWord("view"))
        assertEquals("java", typeWord("java"))
        assertEquals("level", typeWord("level"))
        assertEquals("decide", typeWord("decide"))
        assertEquals("tomato", typeWord("tomato"))
        assertEquals("pokemon", typeWord("pokemon"))
        // Legitimate Vietnamese shortcuts remain intact
        assertEquals("như", typeWord("nhw"))
        assertEquals("đương", typeWord("duongwd"))
        // Multi-syllable loanword with tone-key letter 'r' after V-C-V ("came" + "r" + "a")
        assertEquals("camera", typeWord("camera"))
    }

    @Test
    fun testLongWordOverflowTerminatesCleanly() {
        engine.reset()
        val chunk = "b".repeat(62)
        for (c in chunk) {
            val res = engine.processChar(c)
            assertTrue(res.consumed)
            assertFalse(res.isWordTerminated)
        }
        assertEquals(62, engine.length())

        // 63rd letter must terminate cleanly with the 62-char chunk instead of wiping it
        val overflowRes = engine.processChar('c')
        assertFalse(overflowRes.consumed)
        assertTrue(overflowRes.isWordTerminated)
        assertEquals(chunk, overflowRes.text)
        assertTrue(engine.isEmpty())

        // Re-feeding the incoming character starts a fresh composing token
        val freshRes = engine.processChar('c')
        assertTrue(freshRes.consumed)
        assertFalse(freshRes.isWordTerminated)
        assertEquals("c", freshRes.text)
    }

    @Test
    fun testBackspaceResetsLastToneKey() {
        assertEquals("toán", typeWord("toans"))
        // Backspace removes 'n', leaving open syllable "tóa" (tone re-positioned) and clearing lastToneKey
        assertTrue(engine.handleBackspace())
        assertEquals("tóa", engine.getComposingText())

        // Pressing 's' once after backspace re-applies tone instead of immediately reverting
        val afterFirstS = engine.processChar('s')
        assertEquals("tóa", afterFirstS.text)

        // Pressing 's' a second time now reverts tone to literal 's'
        val afterSecondS = engine.processChar('s')
        assertEquals("toas", afterSecondS.text)
    }
}
