package com.truelight.ime.engine

import org.junit.Assert.assertEquals
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
}
