package com.truelight.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    private fun typeWordWithSpace(word: String): String {
        engine.reset()
        for (c in word) {
            val res = engine.processChar(c)
            assertTrue("Expected letter '$c' in '$word' to be consumed", res.consumed)
            assertFalse("Expected letter '$c' in '$word' not to terminate word", res.isWordTerminated)
        }
        val endRes = engine.processChar(' ')
        assertFalse("Expected space after '$word' not to be consumed", endRes.consumed)
        assertTrue("Expected space after '$word' to terminate word", endRes.isWordTerminated)
        assertTrue("Expected engine to reset after space", engine.isEmpty())
        return endRes.text
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
        // Repeating modifier key reverts circumflex / stroke / horn
        assertEquals("aa", typeWord("aaa"))
        assertEquals("ee", typeWord("eee"))
        assertEquals("oo", typeWord("ooo"))
        assertEquals("dd", typeWord("ddd"))
        assertEquals("uow", typeWord("uoww"))
    }

    @Test
    fun testStandaloneWAndDoubleWReversion() {
        assertEquals("w", typeWord("ww"))
        assertEquals("ww", typeWord("www"))
        assertEquals("W", typeWord("WW"))
        assertEquals("như", typeWord("nhw"))
        assertEquals("nhw", typeWord("nhww"))
        assertEquals("ư", typeWord("uw"))
        assertEquals("uw", typeWord("uww"))
        assertEquals("uow", typeWord("uoww"))
        assertEquals("lưu", typeWord("luuw"))
        assertEquals("luuw", typeWord("luuww"))
        assertEquals("quw", typeWord("quw"))
        assertEquals("cứu", typeWord("cuuws"))
        assertEquals("cứu", typeWord("cuusw"))
        assertEquals("khuỷu", typeWord("khuyur"))
        assertEquals("ngoằn", typeWord("ngoawnf"))
    }

    @Test
    fun testEnglishAndForeignAutoRestoreWithSpace() {
        engine.autoRestoreEnabled = true
        val foreignWords = listOf(
            "window", "word", "work", "web", "wifi",
            "user", "uses", "cases", "software", "camera",
            "chrome", "github", "debug", "release", "android",
            "kotlin", "facebook", "new", "view", "java",
            "level", "left", "soft", "port", "next", "text"
        )
        for (word in foreignWords) {
            assertEquals("Auto-restore failed for '$word'", word, typeWordWithSpace(word))
        }
    }

    @Test
    fun testVietnameseWordsAccurateWithAutoRestoreEnabled() {
        engine.autoRestoreEnabled = true
        engine.modernTonePlacement = false

        val cases = listOf(
            "tieengs" to "tiếng",
            "vieetj" to "việt",
            "vieejt" to "việt",
            "truwowngf" to "trường",
            "truongwf" to "trường",
            "nguwowif" to "người",
            "nguoiwf" to "người",
            "nghiax" to "nghĩa",
            "khuyeen" to "khuyên",
            "quynhf" to "quỳnh",
            "giuwax" to "giữa",
            "giuawx" to "giữa",
            "giuaxw" to "giữa",
            "muaw" to "mưa",
            "muaww" to "muaw",
            "suawx" to "sữa",
            "quawn" to "quăn",
            "dduwowcj" to "được",
            "dduocwj" to "được",
            "hoaf" to "hòa",
            "toans" to "toán",
            "xoong" to "xông",
            "xooong" to "xoong",
            "toass" to "toas",
            "aaa" to "aa",
            "ddd" to "dd"
        )
        for ((input, expected) in cases) {
            assertEquals("Vietnamese word failed for '$input'", expected, typeWordWithSpace(input))
        }

        engine.modernTonePlacement = true
        assertEquals("hoà", typeWordWithSpace("hoaf"))
    }

    @Test
    fun testAutoRestoreDisabledLeavesTelexTransformations() {
        engine.autoRestoreEnabled = false
        // With auto-restore OFF, Telex keeps raw transformations on English words
        assertNotEquals("window", typeWordWithSpace("window"))
        assertNotEquals("word", typeWordWithSpace("word"))
        assertNotEquals("web", typeWordWithSpace("web"))
        assertNotEquals("soft", typeWordWithSpace("soft"))
        assertNotEquals("port", typeWordWithSpace("port"))
        assertNotEquals("next", typeWordWithSpace("next"))
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
