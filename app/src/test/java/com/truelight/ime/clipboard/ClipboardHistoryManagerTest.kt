package com.truelight.ime.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClipboardHistoryManagerTest {

    private lateinit var manager: ClipboardHistoryManager

    @Before
    fun setUp() {
        manager = ClipboardHistoryManager()
    }

    @Test
    fun testPinAndUnpinOrdering() {
        val base = System.currentTimeMillis()
        manager.addTextClip("first", base)
        manager.addTextClip("second", base + 10)
        manager.addTextClip("third", base + 20)

        // Initial newest-first order
        assertEquals(listOf("third", "second", "first"), manager.getClips())

        // Pin "first" (index 2) -> moves to top
        assertTrue(manager.togglePin(2))
        assertEquals(listOf("first", "third", "second"), manager.getClips())
        assertTrue(manager.getClipItems()[0].isPinned)
        assertFalse(manager.getClipItems()[1].isPinned)

        // Pin "second" (index 2) -> moves right after existing pinned item
        assertTrue(manager.togglePin(2))
        assertEquals(listOf("first", "second", "third"), manager.getClips())
        assertTrue(manager.getClipItems()[0].isPinned)
        assertTrue(manager.getClipItems()[1].isPinned)
        assertFalse(manager.getClipItems()[2].isPinned)

        // New unpinned copy inserts below pinned block
        manager.addTextClip("fourth", base + 30)
        assertEquals(listOf("first", "second", "fourth", "third"), manager.getClips())

        // Unpin "first" (index 0) -> returns to unpinned block in chronological position
        assertFalse(manager.togglePin(0))
        assertEquals(listOf("second", "fourth", "third", "first"), manager.getClips())
        assertTrue(manager.getClipItems()[0].isPinned)
        assertFalse(manager.getClipItems()[1].isPinned)

        // Out-of-bounds index safely returns false
        assertFalse(manager.togglePin(-1))
        assertFalse(manager.togglePin(99))
    }

    @Test
    fun testDuplicateCopyOfPinnedItemStaysPinned() {
        val base = System.currentTimeMillis()
        manager.addTextClip("keep-me", base)
        manager.addTextClip("other", base + 10)
        assertTrue(manager.togglePin(1)) // pin "keep-me" at index 0

        manager.addTextClip("keep-me", base + 500)
        val items = manager.getClipItems()
        assertEquals(2, items.size)
        assertEquals("keep-me", items[0].text)
        assertTrue(items[0].isPinned)
        assertEquals(base + 500, items[0].timestampMs)
    }

    @Test
    fun testClearPreservesPinnedUnlessIncludePinned() {
        val base = System.currentTimeMillis()
        manager.addTextClip("alpha", base)
        manager.addTextClip("beta", base + 10)
        manager.addTextClip("gamma", base + 20)
        assertTrue(manager.togglePin(1)) // pin "beta"

        // Default clear (includePinned = false) keeps pinned items
        manager.clear()
        assertEquals(listOf("beta"), manager.getClips())
        assertTrue(manager.getClipItems()[0].isPinned)

        // Full wipe (includePinned = true) removes everything
        manager.clear(includePinned = true)
        assertTrue(manager.getClipItems().isEmpty())
    }

    @Test
    fun testRemoveAtSingleItem() {
        val base = System.currentTimeMillis()
        manager.addTextClip("one", base)
        manager.addTextClip("two", base + 10)
        manager.addTextClip("three", base + 20)
        assertTrue(manager.togglePin(2)) // ["one"(pinned), "three", "two"]

        // Remove unpinned item at index 1 ("three")
        assertTrue(manager.removeAt(1))
        assertEquals(listOf("one", "two"), manager.getClips())

        // Remove pinned item at index 0 ("one")
        assertTrue(manager.removeAt(0))
        assertEquals(listOf("two"), manager.getClips())

        // Invalid indices
        assertFalse(manager.removeAt(-1))
        assertFalse(manager.removeAt(5))
    }

    @Test
    fun testPruneExpiredRemovesOnlyExpiredUnpinned() {
        val now = System.currentTimeMillis()
        manager.addTextClip("pinned-old", now - 60 * 60_000L)
        assertTrue(manager.togglePin(0))
        manager.addTextClip("expired-unpinned", now - 20 * 60_000L)
        manager.addTextClip("fresh-unpinned", now - 5 * 60_000L)

        // Enable 15-minute auto-clear -> expires 20m item immediately, keeps pinned & 5m item
        manager.setAutoClearMinutes(15)
        assertEquals(listOf("pinned-old", "fresh-unpinned"), manager.getClips())

        // Advance clock by 11 minutes -> 5m item is now 16m old and pruned; pinned survives
        manager.pruneExpired(now + 11 * 60_000L)
        assertEquals(listOf("pinned-old"), manager.getClips())
        assertTrue(manager.getClipItems()[0].isPinned)
    }

    @Test
    fun testEvictionNeverDropsPinnedItems() {
        val base = System.currentTimeMillis()
        manager.addTextClip("pinned-1", base)
        manager.addTextClip("pinned-2", base + 1)
        assertTrue(manager.togglePin(1)) // pinned-1
        assertTrue(manager.togglePin(1)) // pinned-2

        // Add 12 more unpinned items to trigger MAX_CLIPS (10) eviction repeatedly
        for (i in 1..12) {
            manager.addTextClip("clip-$i", base + 10 + i)
        }

        val items = manager.getClipItems()
        assertEquals(10, items.size)
        assertEquals("pinned-1", items[0].text)
        assertTrue(items[0].isPinned)
        assertEquals("pinned-2", items[1].text)
        assertTrue(items[1].isPinned)
        // Newest 8 unpinned items remain ("clip-12" down to "clip-5")
        assertEquals("clip-12", items[2].text)
        assertEquals("clip-5", items[9].text)
    }

    @Test
    fun testImageEvictionPreservesPinnedImages() {
        val base = System.currentTimeMillis()
        manager.addImageClip("content://img/pinned", "image/png", base)
        assertTrue(manager.togglePin(0))

        // Add 6 unpinned images -> MAX_IMAGE_CLIPS = 5, pinned image must survive
        for (i in 1..6) {
            manager.addImageClip("content://img/$i", "image/jpeg", base + i)
        }

        val items = manager.getClipItems()
        assertEquals(5, items.size)
        assertEquals("content://img/pinned", items[0].imageUri)
        assertTrue(items[0].isPinned)
        assertEquals("content://img/6", items[1].imageUri)
        assertEquals("content://img/3", items[4].imageUri)
    }
}
