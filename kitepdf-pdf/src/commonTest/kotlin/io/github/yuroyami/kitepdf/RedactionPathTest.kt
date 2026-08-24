package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A signature or a chart drawn as vector art IS its coordinates. Painting a
 * black box over it leaves the shape recoverable from the content stream, so
 * redaction has to remove the path (ledger D-2).
 */
class RedactionPathTest {

    private fun render(pdf: ByteArray): RecordingCanvas {
        val canvas = RecordingCanvas()
        KitePDF.open(pdf).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        return canvas
    }

    /**
     * Fills the page paints, minus the opaque black box redaction lays over each
     * region. That box is appended after the rewrite and is a filled rectangle
     * itself, so a raw fill count would read it as a surviving path and every
     * assertion here would be off by one. Every fixture below paints in colour.
     */
    private fun fills(pdf: ByteArray) = render(pdf).calls
        .filterIsInstance<RecordingCanvas.Call.Fill>()
        .filter { it.color != RgbColor.BLACK }

    private fun strokes(pdf: ByteArray) = render(pdf).calls.filterIsInstance<RecordingCanvas.Call.Stroke>()

    private fun clips(pdf: ByteArray) = render(pdf).calls.filterIsInstance<RecordingCanvas.Call.PushClip>()

    /**
     * The page's content stream after decompression. Every stream this editor
     * writes is Flate-compressed, so a scan of the raw file bytes cannot see into
     * one and would report "gone" for the wrong reason.
     */
    private fun content(pdf: ByteArray): String = KitePDF.open(pdf).pages[0].contentBytes.decodeToString()

    private fun redact(pdf: ByteArray, region: KiteRectangle): ByteArray {
        val doc = KitePDF.open(pdf)
        return doc.edit().apply { redactRegion(doc.pages[0], region) }.saveRewritten()
    }

    /** One filled box high on the page, one low. */
    private fun twoBoxPdf(): ByteArray = RawPdf.page(
        "1 0 0 rg 100 700 80 40 re f\n0 0 1 rg 100 200 80 40 re f\n".encodeToByteArray(),
    )

    private val highRegion = KiteRectangle(left = 90.0, bottom = 690.0, right = 200.0, top = 750.0)

    @Test fun the_fixture_fills_two_boxes() {
        assertEquals(2, fills(twoBoxPdf()).size)
    }

    @Test fun a_filled_path_inside_a_region_is_dropped() {
        val out = redact(twoBoxPdf(), highRegion)
        assertEquals(1, fills(out).size, "the path in the region survived redaction")
    }

    @Test fun a_filled_path_outside_a_region_survives() {
        val emptyCorner = KiteRectangle(left = 400.0, bottom = 400.0, right = 500.0, top = 500.0)
        val out = redact(twoBoxPdf(), emptyCorner)
        assertEquals(2, fills(out).size, "an untouched path was removed")
    }

    @Test fun a_rectangle_reaching_into_a_region_from_outside_is_dropped() {
        // The `re` starts at y=600, below the region, and is 120 tall, so its top
        // edge is at y=720, inside it. Judged by its origin alone it looks clear.
        val pdf = RawPdf.page("1 0 0 rg 100 600 80 120 re f\n".encodeToByteArray())
        assertEquals(1, fills(pdf).size, "fixture is wrong, it must paint one box")
        assertEquals(0, fills(redact(pdf, highRegion)).size, "the rectangle's far corner was never measured")
    }

    @Test fun a_path_drawn_downward_into_a_region_is_dropped() {
        // Nothing says a path starts at its lowest corner. This box is drawn from
        // the top, at y=900, and works down to y=700, inside the region. Bounds that
        // only ever grow away from the first point would never see the 700.
        val pdf = RawPdf.page("1 0 0 rg 100 900 m 180 900 l 180 700 l 100 700 l h f\n".encodeToByteArray())
        assertEquals(1, fills(pdf).size, "fixture is wrong, it must paint one box")
        assertEquals(0, fills(redact(pdf, highRegion)).size, "the path's lowest point was never measured")
    }

    @Test fun a_curve_that_bulges_into_a_region_is_dropped() {
        // Both ends sit at y=600, below the region. The control points at y=800 are
        // what pull the curve up through it, so a bound taken from the ends alone
        // would call this clear (ISO 32000-1, 8.5.2.2: the control polygon bounds
        // the curve, so reading the control points over-covers and never misses).
        val pdf = RawPdf.page("1 0 0 rg 100 600 m 100 800 300 800 300 600 c f\n".encodeToByteArray())
        assertEquals(1, fills(pdf).size, "fixture is wrong, it must paint one curve")
        val region = KiteRectangle(left = 90.0, bottom = 690.0, right = 320.0, top = 750.0)
        assertEquals(0, fills(redact(pdf, region)).size, "the curve's control points were never measured")
    }

    @Test fun a_short_form_curve_that_bulges_into_a_region_is_dropped() {
        // `y` (and `v`, its twin) carry two points, not three. Miscount them and the
        // bulge disappears from the bounds the same way it does for `c`.
        val pdf = RawPdf.page("1 0 0 rg 100 600 m 100 800 300 600 y f\n".encodeToByteArray())
        assertEquals(1, fills(pdf).size, "fixture is wrong, it must paint one curve")
        val region = KiteRectangle(left = 90.0, bottom = 690.0, right = 320.0, top = 750.0)
        assertEquals(0, fills(redact(pdf, region)).size, "the short-form curve's control point was never measured")
    }

    @Test fun the_dropped_paths_coordinates_leave_the_stream() {
        val base = twoBoxPdf()
        // Positive control: the scan below has to be able to see a `re` at all, and
        // has to match the exact spelling the writer emits, or "absent" means nothing.
        assertTrue(
            content(base).contains("100 700 80 40 re"),
            "fixture is wrong, the scan proves nothing: ${content(base)}",
        )

        val out = redact(base, highRegion)
        assertTrue(
            content(out).contains("100 200 80 40 re"),
            "the surviving box lost its coordinates, so the scan below cannot be trusted",
        )
        assertTrue(
            !content(out).contains("100 700 80 40 re"),
            "the redacted path's coordinates are still in the content stream: ${content(out)}",
        )
    }

    @Test fun a_thick_stroke_just_outside_the_region_is_still_caught() {
        // The line sits at y=680, ten points below the region, but a 40pt pen
        // puts twenty points of ink on either side, so its ink enters the region.
        val pdf = RawPdf.page("40 w 0 0 0 RG 100 680 m 300 680 l S\n".encodeToByteArray())
        assertEquals(1, strokes(pdf).size, "fixture is wrong, it must paint one stroke")
        val region = KiteRectangle(left = 90.0, bottom = 690.0, right = 320.0, top = 750.0)
        assertEquals(0, strokes(redact(pdf, region)).size, "stroke width was not accounted for")
    }

    @Test fun a_thin_stroke_just_outside_the_region_survives() {
        val pdf = RawPdf.page("1 w 0 0 0 RG 100 680 m 300 680 l S\n".encodeToByteArray())
        val region = KiteRectangle(left = 90.0, bottom = 690.0, right = 320.0, top = 750.0)
        assertEquals(1, strokes(redact(pdf, region)).size, "a stroke outside the region was removed")
    }

    @Test fun a_pen_stretched_by_the_ctm_is_measured_in_the_direction_it_stretched() {
        // A line width is a user-space quantity (ISO 32000-1, 8.4.3.2). Under
        // `1 0 0 10 0 0` the 4pt pen lays 20 points of ink above the line on the
        // page, not 4: the line is at page y=680 and its ink reaches y=700, inside
        // the region. Scaling the pen by one factor for both axes (the matrix's
        // area, sqrt(10) here) would call it 6 points and leave the ink behind.
        val pdf = RawPdf.page("q 1 0 0 10 0 0 cm 4 w 0 0 0 RG 100 68 m 300 68 l S Q\n".encodeToByteArray())
        assertEquals(1, strokes(pdf).size, "fixture is wrong, it must paint one stroke")
        val region = KiteRectangle(left = 90.0, bottom = 690.0, right = 320.0, top = 750.0)
        assertEquals(0, strokes(redact(pdf, region)).size, "the pen was measured after the CTM squashed it")
    }

    @Test fun a_line_width_set_inside_q_and_Q_does_not_leak_past_it() {
        // `w` is graphics state (ISO 32000-1, 8.4.3.2), so `Q` puts the pen back to
        // the 1pt default. Held as a plain field instead, the 40 would survive the
        // `Q` and this thin line would be judged as if it were twenty points wide.
        val pdf = RawPdf.page("q 40 w Q 0 0 0 RG 100 680 m 300 680 l S\n".encodeToByteArray())
        val region = KiteRectangle(left = 90.0, bottom = 690.0, right = 320.0, top = 750.0)
        assertEquals(1, strokes(redact(pdf, region)).size, "a line width restored by Q was still applied")
    }

    @Test fun a_path_the_ctm_moves_into_a_region_is_dropped() {
        // The box's own coordinates put it at y=200, far below the region. The `cm`
        // lifts it by 500 to y=700, inside it. Judged in its own space it looks safe.
        val pdf = RawPdf.page("q 1 0 0 1 0 500 cm 1 0 0 rg 100 200 80 40 re f Q\n".encodeToByteArray())
        assertEquals(1, fills(pdf).size, "fixture is wrong, it must paint one box")
        assertEquals(0, fills(redact(pdf, highRegion)).size, "the path was judged in its own space, not the page's")
    }

    @Test fun a_clipping_path_in_a_region_keeps_clipping() {
        // The clip rectangle sits inside the region, so its PAINT is redacted.
        // The clip itself has to stay: everything up to the matching Q is
        // clipped by it, and dropping it would let that content paint freely.
        // The fill inside the clip is well outside the region, so it survives
        // either way, which is what makes the clip the only thing under test.
        val pdf = RawPdf.page(
            (
                "q 100 700 80 40 re W n\n" +
                    "0 1 0 rg 100 200 80 40 re f\n" +
                    "Q\n"
                ).encodeToByteArray(),
        )
        assertEquals(1, clips(pdf).size, "fixture does not clip, so this test proves nothing")

        val out = redact(pdf, highRegion)
        assertEquals(
            1,
            clips(out).size,
            "the clip was dropped along with its paint, so content after it is no longer clipped",
        )
        assertEquals(1, fills(out).size, "the clipped fill was removed with the clip")
    }

    @Test fun a_clipping_path_that_also_paints_keeps_the_clip_and_loses_the_paint() {
        // `W f` both clips and fills. The clip has to stay for the reason above, but
        // the fill is the thing being redacted, so the painting operator becomes `n`
        // rather than being left alone.
        val pdf = RawPdf.page(
            (
                "q 1 0 0 rg 100 700 80 40 re W f\n" +
                    "0 1 0 rg 100 200 80 40 re f\n" +
                    "Q\n"
                ).encodeToByteArray(),
        )
        assertEquals(1, clips(pdf).size, "fixture does not clip, so this test proves nothing")
        assertEquals(2, fills(pdf).size, "fixture is wrong, it must paint two boxes")

        val out = redact(pdf, highRegion)
        assertEquals(1, clips(out).size, "the clip was dropped, so content after it is no longer clipped")
        assertEquals(1, fills(out).size, "the clipping path went on filling the region it was redacted out of")
    }

    @Test fun a_path_a_truncated_stream_never_painted_still_leaves_the_region() {
        // No painting operator: the stream ends mid-path. Nothing was ever drawn,
        // but the coordinates are in the file all the same, and coordinates are the
        // content this task exists to remove.
        val pdf = RawPdf.page("1 0 0 rg 100 700 80 40 re".encodeToByteArray())
        assertTrue(content(pdf).contains("100 700 80 40 re"), "fixture is wrong, the scan proves nothing")

        val out = redact(pdf, highRegion)
        assertTrue(
            !content(out).contains("100 700 80 40 re"),
            "an unpainted path in the region kept its coordinates: ${content(out)}",
        )
    }

    @Test fun a_path_a_truncated_stream_never_painted_survives_outside_every_region() {
        val pdf = RawPdf.page("0 0 1 rg 100 200 80 40 re".encodeToByteArray())
        val out = redact(pdf, highRegion)
        assertTrue(
            content(out).contains("100 200 80 40 re"),
            "an untouched path was lost because the buffer was never flushed: ${content(out)}",
        )
    }
}
