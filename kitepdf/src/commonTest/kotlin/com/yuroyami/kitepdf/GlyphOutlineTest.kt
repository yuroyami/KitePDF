package com.yuroyami.kitepdf

import com.yuroyami.kitepdf.font.Contour
import com.yuroyami.kitepdf.font.GlyphBbox
import com.yuroyami.kitepdf.font.GlyphOutline
import com.yuroyami.kitepdf.font.GlyphPoint
import com.yuroyami.kitepdf.render.PdfPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GlyphOutlineTest {

    @Test
    fun empty_glyph_yields_empty_path() {
        val outline = GlyphOutline(emptyList(), GlyphBbox(0, 0, 0, 0))
        val path = outline.toPdfPath()
        assertTrue(path.isEmpty())
    }

    @Test
    fun all_on_curve_contour_yields_polygon() {
        // A square (0,0) → (100,0) → (100,100) → (0,100) — all on-curve.
        val outline = GlyphOutline(
            listOf(Contour(listOf(
                GlyphPoint(0, 0, onCurve = true),
                GlyphPoint(100, 0, onCurve = true),
                GlyphPoint(100, 100, onCurve = true),
                GlyphPoint(0, 100, onCurve = true),
            ))),
            GlyphBbox(0, 0, 100, 100),
        )
        val path = outline.toPdfPath()
        // Expect MoveTo + 3 LineTo (or 4 if the loop emits one extra) + Close.
        val moves = path.segments.filterIsInstance<PdfPath.Segment.MoveTo>()
        val lines = path.segments.filterIsInstance<PdfPath.Segment.LineTo>()
        val closes = path.segments.filterIsInstance<PdfPath.Segment.Close>()
        assertEquals(1, moves.size)
        assertEquals(1, closes.size)
        // Three LineTos after the implicit MoveTo to the first on-curve point,
        // plus one more closing the loop back to start.
        assertTrue(lines.size >= 3, "Expected ≥3 LineTos for a square, got ${lines.size}")
    }

    @Test
    fun off_curve_between_on_curves_yields_quad_to() {
        // (0,0) on, (50, 100) off, (100, 0) on — single quadratic.
        val outline = GlyphOutline(
            listOf(Contour(listOf(
                GlyphPoint(0, 0, onCurve = true),
                GlyphPoint(50, 100, onCurve = false),
                GlyphPoint(100, 0, onCurve = true),
            ))),
            GlyphBbox(0, 0, 100, 100),
        )
        val path = outline.toPdfPath()
        val quads = path.segments.filterIsInstance<PdfPath.Segment.QuadTo>()
        assertTrue(quads.isNotEmpty(), "Expected ≥1 QuadTo for off-curve point")
        val quad = quads.first()
        assertEquals(50.0, quad.x1)
        assertEquals(100.0, quad.y1)
    }

    @Test
    fun two_consecutive_off_curves_imply_midpoint() {
        // (0,0) on, (50, 100) off, (100, 100) off, (100, 0) on
        // Per TTF spec: an implied on-curve point appears at the midpoint
        // between the two off-curves — (75, 100).
        val outline = GlyphOutline(
            listOf(Contour(listOf(
                GlyphPoint(0, 0, onCurve = true),
                GlyphPoint(50, 100, onCurve = false),
                GlyphPoint(100, 100, onCurve = false),
                GlyphPoint(100, 0, onCurve = true),
            ))),
            GlyphBbox(0, 0, 100, 100),
        )
        val path = outline.toPdfPath()
        val quads = path.segments.filterIsInstance<PdfPath.Segment.QuadTo>()
        assertTrue(quads.size >= 2, "Expected ≥2 QuadTos with implied midpoint, got ${quads.size}")
        // Second quad's start (= first quad's end) should land at the midpoint
        // (75, 100) — but Builder only exposes segments so we read it from
        // the first QuadTo's end coords.
        assertEquals(75.0, quads[0].x2)
        assertEquals(100.0, quads[0].y2)
    }
}
