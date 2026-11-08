package io.github.yuroyami.kitepdf.core.font

import io.github.yuroyami.kitepdf.core.render.KitePath
import kotlin.test.Test
import kotlin.test.assertEquals

/** A Type 2 moveto closes the open contour, as endchar closes the last (Adobe TN 5177, 4.1; #778). */
class CharstringContourCloseTest {

    private fun n(v: Int): Byte = (v + 139).toByte()

    @Test
    fun everyContourOfATwoContourGlyphIsClosed() {
        // 0 0 rmoveto 10 hlineto 10 vlineto 20 0 rmoveto 10 hlineto 10 vlineto endchar
        val cs = byteArrayOf(
            n(0), n(0), 21, n(10), 6, n(10), 7,
            n(20), n(0), 21, n(10), 6, n(10), 7, 14,
        )
        val path = CharstringInterpreter(cs, emptyList(), emptyList(), 0.0, 0.0).interpret()
        val kinds = path.segments.map {
            when (it) {
                is KitePath.Segment.MoveTo -> "M"
                is KitePath.Segment.LineTo -> "L"
                KitePath.Segment.Close -> "Z"
                else -> "?"
            }
        }
        assertEquals("MLLZMLLZ", kinds.joinToString(""))
    }
}
