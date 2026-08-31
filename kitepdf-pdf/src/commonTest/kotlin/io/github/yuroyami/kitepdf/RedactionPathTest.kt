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
 * redaction has to remove the path.
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

    @Test fun a_second_segment_is_measured_from_the_first_segments_true_end_not_a_stale_point() {
        // Segment 2 has to start where segment 1 actually ended, (300,900), not
        // wherever the path's current point was before segment 1 ran. Paired
        // with that stale (300,600) instead, segment 2's y-range would sit
        // entirely below the region (600 to 600) and miss it; segment 1 on its
        // own stays at x=300, right of the region, so it never hits either way.
        val pdf = RawPdf.page("1 0 0 rg 300 600 m 300 900 l 50 600 l f\n".encodeToByteArray())
        assertEquals(1, fills(pdf).size, "fixture is wrong, it must paint one path")
        assertEquals(
            0,
            fills(redact(pdf, highRegion)).size,
            "the second segment was measured from a stale current point",
        )
    }

    @Test fun a_line_after_a_curve_is_measured_from_the_curves_true_end_not_a_stale_point() {
        // Same idea as the previous test, but the first segment is a curve: its
        // own control points (300,700)/(300,800) stay at x=300, right of the
        // region, so the curve never hits on its own. The line after it has to
        // start at the curve's actual end point (300,900), not the (300,600) the
        // path's pen was at before the curve ran.
        val pdf = RawPdf.page("1 0 0 rg 300 600 m 300 700 300 800 300 900 c 50 600 l f\n".encodeToByteArray())
        assertEquals(1, fills(pdf).size, "fixture is wrong, it must paint one path")
        assertEquals(
            0,
            fills(redact(pdf, highRegion)).size,
            "the line after the curve was measured from a stale current point",
        )
    }

    @Test fun a_line_after_a_closepath_continues_from_the_subpaths_start_not_a_stale_point() {
        // `h` closes back to the subpath's own start, (300,600), and a line drawn
        // right after it (with no intervening `m`) continues from there (ISO
        // 32000-1, 8.5.2.1), not from wherever the pen was just before the `h`
        // ran. Using that stale point instead would wrongly pull an untouched
        // path into the region and delete it: this is over-removal, the same
        // class of bug as the whole-path bounding box, just from a different
        // cause.
        val pdf = RawPdf.page("1 0 0 rg 300 600 m 300 900 l h 50 600 l f\n".encodeToByteArray())
        assertEquals(1, fills(pdf).size, "fixture is wrong, it must paint one path")
        assertEquals(
            1,
            fills(redact(pdf, highRegion)).size,
            "a line after `h` was measured from a stale current point and the untouched path was wrongly removed",
        )
    }

    @Test fun a_lines_downward_reach_is_measured_even_when_it_starts_above_the_region() {
        // The line starts at y=900, above the region, and reaches down to y=700,
        // inside it. A single segment's own bound has to fall as well as rise:
        // seeding it from the first point and only ever growing upward would
        // report a bound stuck at 900 and miss the region entirely.
        val pdf = RawPdf.page("1 0 0 rg 150 900 m 150 700 l f\n".encodeToByteArray())
        assertEquals(1, fills(pdf).size, "fixture is wrong, it must paint one path")
        assertEquals(0, fills(redact(pdf, highRegion)).size, "the segment's downward reach was never measured")
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
        // `y` (and `v`, its twin) carry two points, not three. Miscount them and
        // the control points vanish from the bounds the same way they would for
        // `c`. The true curve peaks at `600 + 3*(4/27)*200 = 688.9`, just under
        // the region's `bottom = 690`, so its real ink never enters the region:
        // this pins the conservative control-point bound over-covering by 1.1pt,
        // not real ink being caught.
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
        // Positive control on the OUTPUT itself: the black box redaction always
        // paints proves the scan below is reading real content, not an empty or
        // broken rewrite that would make the negative check pass for free.
        assertTrue(
            content(out).contains("90 690 110 60 re"),
            "the redacted output lost its black box, so the scan below cannot be trusted: ${content(out)}",
        )
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

    // ─── Fix round 1: segment testing, not one box for the whole path ──────

    @Test fun a_full_page_background_rectangle_survives_a_region_on_top_of_it() {
        // The background's four edges never enter the region: bottom stays at
        // y=0, top at y=792, left at x=0, right at x=612, and the region sits
        // well inside all four. Testing the whole path's bounding box instead of
        // its own edges would call every point inside a page-spanning rectangle a
        // hit, which erases the background for a redaction anywhere on the page.
        val pdf = RawPdf.page("0.9 0.9 0.9 rg 0 0 612 792 re f\n".encodeToByteArray())
        assertEquals(1, fills(pdf).size, "fixture is wrong, it must paint one background")
        val out = redact(pdf, highRegion)
        assertEquals(1, fills(out).size, "a page-spanning background was erased by a region touching only part of it")
    }

    @Test fun a_page_border_rectangle_survives_a_region_touching_only_its_interior() {
        // Every edge of this border stays outside the region: it runs from
        // (36,36) to (576,756), and the region sits well inside that. The whole
        // rectangle's bounding box does cover the region, but none of its four
        // edges do.
        val pdf = RawPdf.page("1 w 0 0 0 RG 36 36 540 720 re S\n".encodeToByteArray())
        assertEquals(1, strokes(pdf).size, "fixture is wrong, it must paint one border")
        val out = redact(pdf, highRegion)
        assertEquals(1, strokes(out).size, "a page border was erased by a region touching only its interior")
    }

    @Test fun a_grid_of_lines_with_a_gap_in_the_middle_survives_a_region_over_the_gap() {
        // Two horizontal lines, far apart, built as one path with two subpaths
        // and one shared `S`, the way a table grid batches its strokes. The
        // region sits in the empty space between them: inside the path's
        // aggregate bounding box, but on neither actual line.
        val pdf = RawPdf.page(
            "1 w 0 0 0 RG 50 900 m 550 900 l 50 100 m 550 100 l S\n".encodeToByteArray(),
        )
        assertEquals(1, strokes(pdf).size, "fixture is wrong, it must paint one (two-subpath) stroke")
        val region = KiteRectangle(left = 200.0, bottom = 400.0, right = 400.0, top = 600.0)
        assertEquals(
            1,
            strokes(redact(pdf, region)).size,
            "a region touching neither line removed the whole multi-subpath grid",
        )
    }

    @Test fun a_line_continuing_after_a_rectangle_is_measured_from_the_rectangles_own_end_point() {
        // `re` ends with the pen back at the rectangle's own start corner (ISO
        // 32000-1, 8.5.2.1 defines it as ending with an implicit `h`), not
        // wherever the path's current point was before the `re`. This `re` sits
        // far from the region on its own; only a line correctly continuing from
        // its corner reaches in.
        val pdf = RawPdf.page("1 0 0 rg 400 50 10 10 re 100 700 l h f\n".encodeToByteArray())
        assertEquals(1, fills(pdf).size, "fixture is wrong, it must paint one path")
        val region = KiteRectangle(left = 150.0, bottom = 600.0, right = 250.0, top = 750.0)
        assertEquals(
            0,
            fills(redact(pdf, region)).size,
            "the line after `re` was measured from the wrong current point",
        )
    }

    // ─── Fix round 1: a second overlapping call keeps the first black box ──

    @Test fun a_second_overlapping_redaction_repaints_the_first_black_box() {
        // Two overlapping calls on the SAME editor. Call 2's content rewrite
        // re-parses call 1's black box as an ordinary filled path like any
        // other, and it reaches into region B, so if it is not remembered and
        // repainted the part of region A outside region B goes from covered to
        // blank.
        val doc = KitePDF.open(twoBoxPdf())
        val editor = doc.edit()
        val regionA = KiteRectangle(left = 50.0, bottom = 650.0, right = 250.0, top = 750.0)
        val regionB = KiteRectangle(left = 150.0, bottom = 650.0, right = 350.0, top = 750.0)
        editor.redactRegion(doc.pages[0], regionA)
        editor.redactRegion(doc.pages[0], regionB)
        val out = editor.saveRewritten()

        val blackFills = render(out).calls
            .filterIsInstance<RecordingCanvas.Call.Fill>()
            .filter { it.color == RgbColor.BLACK }
        assertEquals(2, blackFills.size, "the first call's black box was erased by the second call's overlapping redaction")
    }

    @Test fun non_overlapping_redactions_do_not_accumulate_duplicate_black_boxes() {
        // Three calls on the SAME editor, three regions that never touch each
        // other or the fixture's own boxes. None of the three calls' rewrites
        // can ever consume an earlier call's box (nothing overlaps), so each
        // region's box is painted exactly once, total three, not one-plus-two-
        // plus-three.
        val doc = KitePDF.open(twoBoxPdf())
        val editor = doc.edit()
        val regionA = KiteRectangle(left = 50.0, bottom = 650.0, right = 90.0, top = 660.0)
        val regionB = KiteRectangle(left = 150.0, bottom = 650.0, right = 190.0, top = 660.0)
        val regionC = KiteRectangle(left = 250.0, bottom = 650.0, right = 290.0, top = 660.0)
        editor.redactRegion(doc.pages[0], regionA)
        editor.redactRegion(doc.pages[0], regionB)
        editor.redactRegion(doc.pages[0], regionC)
        val out = editor.saveRewritten()

        val blackFills = render(out).calls
            .filterIsInstance<RecordingCanvas.Call.Fill>()
            .filter { it.color == RgbColor.BLACK }
        assertEquals(3, blackFills.size, "non-overlapping redactions left duplicate black boxes behind")
    }

    // ─── Fix round 1: malformed operands must not silently under-cover ─────

    @Test fun a_malformed_line_width_does_not_zero_the_pen() {
        // The second `w` has no operand: it reads as 0 through the same `num`
        // helper used everywhere else. `PageRenderer` guards this exact operator
        // and keeps the previous width; without the same guard the engine would
        // believe the pen is zero while the renderer strokes thick ink.
        val pdf = RawPdf.page("40 w w 0 0 0 RG 100 680 m 300 680 l S\n".encodeToByteArray())
        assertEquals(1, strokes(pdf).size, "fixture is wrong, it must paint one stroke")
        val region = KiteRectangle(left = 90.0, bottom = 690.0, right = 320.0, top = 750.0)
        assertEquals(
            0,
            strokes(redact(pdf, region)).size,
            "a malformed w zeroed the pen instead of keeping the previous width",
        )
    }

    @Test fun a_sharp_join_is_padded_by_the_miter_amount_not_just_the_line_width() {
        // The vertex where these two segments meet sits at y=680, ten points
        // below the region. Padding only by half the 10pt line width (5) reaches
        // y=685, still short. A miter join can extend `miterLimit * lineWidth / 2`
        // from a vertex (ISO 32000-1, 8.4.3.5); at the default limit of 10 that is
        // 50, which reaches well past the region.
        val pdf = RawPdf.page("10 w 0 0 0 RG 100 500 m 100 680 l 300 680 l S\n".encodeToByteArray())
        assertEquals(1, strokes(pdf).size, "fixture is wrong, it must paint one stroke")
        val region = KiteRectangle(left = 90.0, bottom = 690.0, right = 320.0, top = 750.0)
        assertEquals(
            0,
            strokes(redact(pdf, region)).size,
            "the join was padded by the line width alone, not the miter limit",
        )
    }

    @Test fun a_malformed_miter_limit_does_not_zero_the_pens_padding() {
        // `M` with no operand reads as 0 through the same `num` helper, mirroring
        // `PageRenderer`, which has the same gap and no guard on `M` either.
        // Multiplying the pad by a 0 miter limit would erase it completely
        // instead of falling back to the plain lineWidth/2 body pad.
        val pdf = RawPdf.page("10 w M 0 0 0 RG 100 686 m 300 686 l S\n".encodeToByteArray())
        assertEquals(1, strokes(pdf).size, "fixture is wrong, it must paint one stroke")
        val region = KiteRectangle(left = 90.0, bottom = 690.0, right = 320.0, top = 750.0)
        assertEquals(
            0,
            strokes(redact(pdf, region)).size,
            "a malformed M zeroed the pad instead of falling back to lineWidth/2",
        )
    }

    // ─── Fix round 1: a close-and-paint clip keeps its implicit close ──────

    @Test fun a_close_and_paint_operator_gets_an_explicit_close_before_the_neutered_paint() {
        // `s` (close-and-stroke) implicitly closes the subpath before painting,
        // and `PageRenderer` closes before computing a pending clip too. A
        // neutered bare `n` would lose that implicit close, so the clip is
        // computed from an open triangle instead of the closed one the original
        // stream asked for.
        val pdf = RawPdf.page(
            "q 100 700 m 180 700 l 140 750 l W s\n0 1 0 rg 100 200 80 40 re f\nQ\n".encodeToByteArray(),
        )
        val out = redact(pdf, highRegion)
        assertTrue(
            content(out).contains("W\nh\nn\n"),
            "a close-and-paint operator lost its implicit close: ${content(out)}",
        )
    }

    @Test fun a_close_fill_and_stroke_operator_also_gets_an_explicit_close_before_the_neutered_paint() {
        // `b` (close, fill and stroke) closes the current subpath the same way
        // `s` does (and so does `b*`). This pins that the closing set covers all
        // three, not just `s`.
        val pdf = RawPdf.page(
            "q 100 700 m 180 700 l 140 750 l W b\n0 1 0 rg 100 200 80 40 re f\nQ\n".encodeToByteArray(),
        )
        val out = redact(pdf, highRegion)
        assertTrue(
            content(out).contains("W\nh\nn\n"),
            "a close-fill-and-stroke operator lost its implicit close: ${content(out)}",
        )
    }

    // ─── Final review: a form's stroke inherits the invoking stream's pen ──

    /** A form invoking `/Fm0 Do` after setting the pen, whose own content never sets one. */
    private fun formStrokePdf(setup: String, formOps: String): ByteArray = RawPdf.page(
        content = "$setup /Fm0 Do\n".encodeToByteArray(),
        resources = "<< /Font << /F1 4 0 R >> /XObject << /Fm0 6 0 R >> >>",
        extra = listOf(
            RawPdf.obj(
                6,
                "<< /Type /XObject /Subtype /Form /BBox [0 0 400 700] /Resources << /Font << /F1 4 0 R >> >> >>",
                formOps.encodeToByteArray(),
            ),
        ),
    )

    @Test fun a_stroke_inside_a_form_inherits_the_invoking_streams_line_width() {
        // A `Do` is a save/restore around the form (ISO 32000-1, 8.10.2): the
        // pen in effect at the Do is what the form's own unset `w` falls back
        // to. A 1.0-default pen pads this stroke by 5 (1 * miterLimit-default
        // 10 / 2), well short of the region; the true 40pt pen pads it by 200.
        val pdf = formStrokePdf("40 w 0 0 0 RG", "100 680 m 300 680 l S\n")
        assertEquals(1, strokes(pdf).size, "fixture is wrong, it must paint one stroke")
        val region = KiteRectangle(left = 90.0, bottom = 690.0, right = 320.0, top = 750.0)
        assertEquals(
            0,
            strokes(redact(pdf, region)).size,
            "the form's stroke was judged at the 1.0 default instead of the invoking stream's 40pt pen",
        )
    }

    @Test fun a_stroke_inside_a_form_inherits_the_invoking_streams_miter_limit() {
        // Isolates the miter limit from the line width: a 2pt pen alone pads
        // by 1 either way, nowhere near the region. Only a miter limit of 20
        // correctly inherited (instead of defaulting to 10 inside the form)
        // pads the join far enough (2 * 20 / 2 = 20) to reach y=690.
        val pdf = formStrokePdf("2 w 20 M 0 0 0 RG", "100 500 m 100 680 l 300 680 l S\n")
        assertEquals(1, strokes(pdf).size, "fixture is wrong, it must paint one stroke")
        val region = KiteRectangle(left = 90.0, bottom = 690.0, right = 320.0, top = 750.0)
        assertEquals(
            0,
            strokes(redact(pdf, region)).size,
            "the form's join was padded by the default miter limit instead of the invoking stream's",
        )
    }

    @Test fun two_invocations_at_the_same_position_with_different_pens_get_separate_redactions() {
        // Both Do's draw Fm0 at the same position, so they map the region to
        // the SAME rectangle in form space: only the pen differs, 1pt before
        // the first Do and 40pt before the second. One cached rewrite cannot
        // be right for both, so the identity behind the cache has to carry the
        // pen too, not just the form and its mapped rectangles.
        val pdf = RawPdf.page(
            content = "1 w 0 0 0 RG /Fm0 Do\n40 w 0 0 0 RG /Fm0 Do\n".encodeToByteArray(),
            resources = "<< /Font << /F1 4 0 R >> /XObject << /Fm0 6 0 R >> >>",
            extra = listOf(
                RawPdf.obj(
                    6,
                    "<< /Type /XObject /Subtype /Form /BBox [0 0 400 700] /Resources << /Font << /F1 4 0 R >> >> >>",
                    "100 680 m 300 680 l S\n".encodeToByteArray(),
                ),
            ),
        )
        assertEquals(2, strokes(pdf).size, "fixture is wrong, it must paint two overlapping strokes")
        val region = KiteRectangle(left = 90.0, bottom = 690.0, right = 320.0, top = 750.0)
        assertEquals(
            1,
            strokes(redact(pdf, region)).size,
            "the second invocation's 40pt pen was judged by the first invocation's cached 1pt redaction",
        )
    }
}
