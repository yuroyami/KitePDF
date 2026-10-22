package io.github.yuroyami.kitepdf.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class KiteSearchLocationTest {

    private fun text(): KiteStructuredText = KiteStructuredText(listOf(KiteTextBlock(listOf(
        KiteTextLine("needle", KiteRectangle(10.0, 20.0, 70.0, 30.0), DoubleArray(7) { 10.0 + it * 10.0 }),
    ))))

    @Test
    fun standalone_search_keeps_legacy_indices_without_inventing_a_location() {
        val default = text().search("needle").single()
        assertEquals(-1, default.pageIndex)
        assertNull(default.location)

        val supplied = text().search("needle", ignoreCase = false, pageIndex = 17).single()
        assertEquals(17, supplied.pageIndex)
        assertNull(supplied.location)
    }

    @Test
    fun located_search_keeps_the_callers_index_and_matching_geometry() {
        val structured = text()
        val location = KiteLocation(5, 2)
        val legacy = structured.search("NEEDLE", pageIndex = 17).single()
        val located = structured.search("NEEDLE", pageIndex = 17, location = location).single()
        assertEquals(17, located.pageIndex)
        assertEquals(location, located.location)
        assertEquals(legacy.text, located.text)
        assertEquals(legacy.quads, located.quads)

        val unknownIndex = structured.search("needle", location = location).single()
        assertEquals(-1, unknownIndex.pageIndex)
        assertEquals(location, unknownIndex.location)
    }

    @Test
    fun legacy_constructor_keeps_its_arguments_and_has_no_location() {
        val quads = listOf(KiteRectangle(10.0, 20.0, 70.0, 30.0))
        val hit = KiteSearchHit(pageIndex = 17, quads = quads, text = "needle")
        assertEquals(17, hit.pageIndex)
        assertSame(quads, hit.quads)
        assertEquals("needle", hit.text)
        assertNull(hit.location)
    }

    @Test
    fun location_first_constructor_does_not_guess_a_global_index() {
        val hit = KiteSearchHit(KiteLocation(5, 2), emptyList(), "needle")
        assertEquals(KiteLocation(5, 2), hit.location)
        assertEquals(-1, hit.pageIndex)
    }
}
