package com.truelight.ime.suggest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionManagerTest {

    @Test
    fun testEnglishSuggestionsAndAutoCorrectToggles() {
        val sm = SuggestionManager(context = null)
        sm.loadForTest("en", listOf("the", "hello", "help", "world", "keyboard", "true", "light"))

        // Toggle 1 ON, Toggle 2 OFF (suggestions only, no auto-correct)
        sm.setTestFlags(suggestionsOn = true, autoCorrectOn = false)
        sm.updateForWord("hel")
        assertTrue(sm.activeSlotCount >= 2)
        assertEquals("hello", sm.slots[0])
        assertNull(sm.getAutoCorrectReplacement("hel"))

        // Typo with Auto-Correct OFF -> shows correction in strip, does NOT auto-replace
        sm.updateForWord("teh")
        assertEquals("teh", sm.slots[0])
        assertEquals("the", sm.slots[1])
        assertNull(sm.getAutoCorrectReplacement("teh"))

        // Toggle 2 ON -> auto-correct fires and preserves casing
        sm.setTestFlags(suggestionsOn = true, autoCorrectOn = true)
        sm.updateForWord("Teh")
        assertEquals("The", sm.getAutoCorrectReplacement("Teh"))

        sm.updateForWord("helo")
        assertEquals("hello", sm.getAutoCorrectReplacement("helo"))

        // Toggle 1 OFF -> unloads dictionary and disables both suggestions and auto-correct
        sm.setTestFlags(suggestionsOn = false, autoCorrectOn = true)
        sm.updateForWord("teh")
        assertEquals(0, sm.activeSlotCount)
        assertNull(sm.getAutoCorrectReplacement("teh"))
    }

    @Test
    fun testVietnameseAccentFoldedSuggestionsAndEnglishCodeSwitchGuard() {
        val sm = SuggestionManager(context = null)
        sm.loadForTest(
            dictKey = "vi",
            words = listOf("trường", "trước", "truyền", "việt", "viết", "bục", "bức"),
            enGuardWords = listOf("fix", "bug", "test", "ok")
        )
        sm.setTestFlags(suggestionsOn = true, autoCorrectOn = true)

        // Accent-folded prefix matching works mid-syllable
        sm.updateForWord("trư")
        assertTrue(sm.activeSlotCount >= 2)
        assertTrue(sm.slots.contains("trường") || sm.slots.contains("trước"))

        // Code-switching guard: English word "bug" in Vietnamese mode is NOT auto-corrected to "bục"
        sm.updateForWord("bug")
        assertNull(sm.getAutoCorrectReplacement("bug"))
    }
}
