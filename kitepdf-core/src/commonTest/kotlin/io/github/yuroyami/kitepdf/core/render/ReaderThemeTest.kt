package io.github.yuroyami.kitepdf.core.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ReaderThemeTest {

    @Test
    fun equal_themes_are_one_cache_key() {
        val map: (RgbColor) -> RgbColor = { it }
        val a = ReaderTheme(RgbColor(0.1, 0.2, 0.3), map)
        val b = ReaderTheme(RgbColor(0.1, 0.2, 0.3), map)
        assertEquals(a, b, "same paper and the same colour function")
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, ReaderTheme(RgbColor(0.1, 0.2, 0.4), map), "different paper")
    }
}
