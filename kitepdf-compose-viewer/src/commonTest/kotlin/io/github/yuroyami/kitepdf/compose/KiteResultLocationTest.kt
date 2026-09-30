package io.github.yuroyami.kitepdf.compose

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.KiteSearchHit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class KiteResultLocationTest {

    private val location = KiteLocation(4, 2)
    private val quads = listOf(KiteRectangle(10.0, 20.0, 30.0, 40.0))

    @Test
    fun page_hit_keeps_legacy_construction_and_copy_arguments() {
        val legacy = KitePageHit(pageIndex = 17, x = 10.0, y = 20.0)
        assertNull(legacy.location)
        assertEquals(legacy, KitePageHit(17, 10.0, 20.0))
        val (slot, x, y) = legacy
        assertEquals(17, slot)
        assertEquals(10.0, x)
        assertEquals(20.0, y)
        assertEquals(KitePageHit(18, 11.0, 21.0), legacy.copy(18, 11.0, 21.0))
    }

    @Test
    fun page_hit_copies_preserve_or_explicitly_replace_location() {
        val hit = KitePageHit(17, 10.0, 20.0, location)
        assertEquals(hit, hit.copy())
        val moved = hit.copy(pageIndex = 18, x = 11.0)
        assertEquals(location, moved.location)
        assertEquals(18, moved.pageIndex)
        assertEquals(11.0, moved.x)
        assertEquals(20.0, moved.y)

        val replacement = KiteLocation(5, 0)
        val relocated = hit.copy(location = replacement)
        assertEquals(replacement, relocated.location)
        assertEquals(17, relocated.pageIndex)
        assertEquals(10.0, relocated.x)
        assertNull(hit.copy(location = null).location)
    }

    @Test
    fun selection_keeps_legacy_construction_and_copy_arguments() {
        val legacy = KiteTextSelection(pageIndex = 17, start = 1, end = 3, text = "abc", quads = quads)
        assertNull(legacy.location)
        assertEquals(legacy, KiteTextSelection(17, 1, 3, "abc", quads))
        val (slot, start, end, text, rectangles) = legacy
        assertEquals(17, slot)
        assertEquals(1, start)
        assertEquals(3, end)
        assertEquals("abc", text)
        assertSame(quads, rectangles)
        assertEquals(KiteTextSelection(18, 2, 3, "bc", quads), legacy.copy(18, 2, 3, "bc", quads))
    }

    @Test
    fun selection_copies_preserve_or_explicitly_replace_location() {
        val selection = KiteTextSelection(17, 1, 3, "abc", quads, location)
        assertEquals(selection, selection.copy())
        val moved = selection.copy(pageIndex = 18, text = "ABC")
        assertEquals(location, moved.location)
        assertEquals(18, moved.pageIndex)
        assertEquals("ABC", moved.text)
        assertEquals(1, moved.start)
        assertEquals(3, moved.end)
        assertSame(quads, moved.quads)

        val replacement = KiteLocation(5, 0)
        val relocated = selection.copy(location = replacement)
        assertEquals(replacement, relocated.location)
        assertEquals(17, relocated.pageIndex)
        assertEquals("abc", relocated.text)
        assertSame(quads, relocated.quads)
        assertNull(selection.copy(location = null).location)
    }

    @Test
    fun highlight_location_always_comes_from_its_hit() {
        val legacy = KiteHighlight(KiteSearchHit(17, quads, "abc"))
        assertNull(legacy.location)
        val located = legacy.copy(hit = KiteSearchHit(location, quads, "abc"))
        assertEquals(location, located.location)
        assertEquals(location, located.copy(id = "note").location)
        assertNull(located.copy(hit = legacy.hit).location)
    }
}
