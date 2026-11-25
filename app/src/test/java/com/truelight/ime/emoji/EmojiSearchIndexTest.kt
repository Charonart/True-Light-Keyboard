package com.truelight.ime.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiSearchIndexTest {

    @Test
    fun testCommonEnglishSearches() {
        val results = ArrayList<String>()

        EmojiSearchIndex.search("fire", results)
        assertTrue("Expected fire emoji in results: $results", results.contains("🔥"))

        EmojiSearchIndex.search("laugh", results)
        assertTrue("Expected joy emoji in results: $results", results.contains("😂"))

        EmojiSearchIndex.search("heart", results)
        assertTrue("Expected red heart emoji in results: $results", results.contains("❤️"))

        EmojiSearchIndex.search("cat", results)
        assertTrue("Expected cat emoji in results: $results", results.contains("🐱"))
    }

    @Test
    fun testCaseInsensitiveAndWhitespaceTrim() {
        val results = ArrayList<String>()

        EmojiSearchIndex.search("  FIRE  ", results)
        assertTrue(results.contains("🔥"))

        EmojiSearchIndex.search("ReD HeArT", results)
        assertTrue(results.contains("❤️"))
    }

    @Test
    fun testEmptyAndBlankQueryReturnsEmpty() {
        val results = ArrayList<String>()
        results.add("🔥")

        EmojiSearchIndex.search("", results)
        assertTrue(results.isEmpty())

        results.add("🔥")
        EmojiSearchIndex.search("   ", results)
        assertTrue(results.isEmpty())
    }

    @Test
    fun testMaxResultsCapAndRanking() {
        val results = ArrayList<String>()
        EmojiSearchIndex.search("heart", results, maxResults = 5)
        assertEquals(5, results.size)

        // Exact word match should rank before prefix/substring match
        val fireResults = ArrayList<String>()
        EmojiSearchIndex.search("fire", fireResults)
        assertTrue(fireResults.isNotEmpty())
        assertTrue("🔥" in fireResults.take(3))
    }

    @Test
    fun testReleaseAndLazyReload() {
        val results = ArrayList<String>()
        EmojiSearchIndex.search("rocket", results)
        assertTrue(results.contains("🚀"))

        EmojiSearchIndex.release()

        EmojiSearchIndex.search("rocket", results)
        assertTrue("Index should reload lazily after release()", results.contains("🚀"))
    }

    @Test
    fun testSkinToneVariantsForEligibleAndNonEligibleEmojis() {
        val thumbs = EmojiSearchIndex.getSkinToneVariants("👍")
        assertNotNull(thumbs)
        assertEquals(6, thumbs!!.size)
        assertEquals("👍", thumbs[0])
        assertEquals("👍\uD83C\uDFFB", thumbs[1])
        assertEquals("👍\uD83C\uDFFC", thumbs[2])
        assertEquals("👍\uD83C\uDFFD", thumbs[3])
        assertEquals("👍\uD83C\uDFFE", thumbs[4])
        assertEquals("👍\uD83C\uDFFF", thumbs[5])

        // Variation selector \uFE0F must be stripped before appending modifier
        val peace = EmojiSearchIndex.getSkinToneVariants("✌️")
        assertNotNull(peace)
        assertEquals(6, peace!!.size)
        assertEquals("✌️", peace[0])
        assertEquals("\u270C\uD83C\uDFFB", peace[1])

        // ZWJ person sequence inserts skin tone right after base person code point
        val dev = EmojiSearchIndex.getSkinToneVariants("🧑‍💻")
        assertNotNull(dev)
        assertEquals(6, dev!!.size)
        assertEquals("🧑‍💻", dev[0])
        assertEquals("🧑\uD83C\uDFFB\u200D💻", dev[1])

        // Non-skin-tone emojis return null
        assertNull(EmojiSearchIndex.getSkinToneVariants("🔥"))
        assertNull(EmojiSearchIndex.getSkinToneVariants("😀"))
        assertNull(EmojiSearchIndex.getSkinToneVariants("🐱"))
        assertNull(EmojiSearchIndex.getSkinToneVariants(""))
    }
}
