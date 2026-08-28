package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [PdfPage.rasterGeometry], the single computation every convenience
 * rasterizer (AWT, Android, Apple, Skia) calls for its output size and device
 * matrix. Tested here with no rasterizing at all: only [PdfRasterGeometry]'s
 * numbers, against expected values worked out by hand from the page boxes
 * (ISO 32000-1 7.7.3.3) and `/UserUnit` (14.11.2, Table 30), not by calling
 * [PdfPage.pageToDeviceBase] and trusting it, since that would only prove this
 * function calls that one, not that the composed result is correct.
 *
 * Every fixture omits `/Contents`: geometry never reads content bytes.
 */
class PdfRasterGeometryTest {

    /* ─── rotation: dimensions swap at 90/270, corners land per /Rotate ──── */

    @Test
    fun rotate_0_sizes_to_the_media_box_top_left_origin_y_down() {
        val page = openPdf(mediaBox = "0 0 612 792").pages[0]

        val g1 = page.rasterGeometry()
        assertEquals(612, g1.widthPx)
        assertEquals(792, g1.heightPx)
        assertCorner(0.0, 792.0, g1, 0.0, 0.0, "bottom-left")
        assertCorner(0.0, 0.0, g1, 0.0, 792.0, "top-left")
        assertCorner(612.0, 0.0, g1, 612.0, 792.0, "top-right")
        assertCorner(612.0, 792.0, g1, 612.0, 0.0, "bottom-right")

        // scale composes: everything doubles.
        val g2 = page.rasterGeometry(scale = 2.0)
        assertEquals(1224, g2.widthPx)
        assertEquals(1584, g2.heightPx)
        assertCorner(0.0, 1584.0, g2, 0.0, 0.0, "bottom-left @2x")
        assertCorner(1224.0, 0.0, g2, 612.0, 792.0, "top-right @2x")
    }

    @Test
    fun rotate_90_swaps_dimensions_and_rotates_corners_clockwise() {
        val page = openPdf(mediaBox = "0 0 612 792", rotate = 90).pages[0]

        val g1 = page.rasterGeometry()
        assertEquals(792, g1.widthPx, "width <- unrotated height")
        assertEquals(612, g1.heightPx, "height <- unrotated width")
        assertCorner(0.0, 0.0, g1, 0.0, 0.0, "bottom-left")
        assertCorner(792.0, 0.0, g1, 0.0, 792.0, "top-left")
        assertCorner(792.0, 612.0, g1, 612.0, 792.0, "top-right")
        assertCorner(0.0, 612.0, g1, 612.0, 0.0, "bottom-right")

        val g2 = page.rasterGeometry(scale = 2.0)
        assertEquals(1584, g2.widthPx)
        assertEquals(1224, g2.heightPx)
        assertCorner(1584.0, 1224.0, g2, 612.0, 792.0, "top-right @2x")
    }

    @Test
    fun rotate_180_keeps_dimensions_and_maps_the_opposite_corner_to_the_origin() {
        val page = openPdf(mediaBox = "0 0 612 792", rotate = 180).pages[0]

        val g1 = page.rasterGeometry()
        assertEquals(612, g1.widthPx)
        assertEquals(792, g1.heightPx)
        assertCorner(612.0, 0.0, g1, 0.0, 0.0, "bottom-left")
        assertCorner(612.0, 792.0, g1, 0.0, 792.0, "top-left")
        assertCorner(0.0, 792.0, g1, 612.0, 792.0, "top-right")
        assertCorner(0.0, 0.0, g1, 612.0, 0.0, "bottom-right")
    }

    @Test
    fun rotate_270_swaps_dimensions_and_rotates_corners_the_other_way() {
        val page = openPdf(mediaBox = "0 0 612 792", rotate = 270).pages[0]

        val g1 = page.rasterGeometry()
        assertEquals(792, g1.widthPx)
        assertEquals(612, g1.heightPx)
        assertCorner(792.0, 612.0, g1, 0.0, 0.0, "bottom-left")
        assertCorner(0.0, 612.0, g1, 0.0, 792.0, "top-left")
        assertCorner(0.0, 0.0, g1, 612.0, 792.0, "top-right")
        assertCorner(792.0, 0.0, g1, 612.0, 0.0, "bottom-right")
    }

    /* ─── /CropBox smaller than /MediaBox: size and origin follow the crop ─ */

    @Test
    fun crop_box_sizes_output_to_the_crop_and_puts_content_outside_it_off_canvas() {
        val page = openPdf(mediaBox = "0 0 300 300", cropBox = "50 80 250 220").pages[0]

        val g1 = page.rasterGeometry()
        assertEquals(200, g1.widthPx, "200 = crop width, not the 300 media width")
        assertEquals(140, g1.heightPx, "140 = crop height, not the 300 media height")
        assertCorner(0.0, 140.0, g1, 50.0, 80.0, "crop bottom-left")
        assertCorner(0.0, 0.0, g1, 50.0, 220.0, "crop top-left")
        assertCorner(200.0, 0.0, g1, 250.0, 220.0, "crop top-right")

        // Two media-box corners outside the crop rectangle: both land outside
        // [0,widthPx] x [0,heightPx], so a canvas sized to the crop never
        // paints them.
        val mediaBottomLeft = g1.deviceCtm.transformPoint(0.0, 0.0)
        assertTrue(mediaBottomLeft.first < 0.0, "media bottom-left x is left of the canvas: $mediaBottomLeft")
        val mediaTopRight = g1.deviceCtm.transformPoint(300.0, 300.0)
        assertTrue(mediaTopRight.first > g1.widthPx, "media top-right x is right of the canvas: $mediaTopRight")
        assertTrue(mediaTopRight.second < 0.0, "media top-right y is above the canvas: $mediaTopRight")

        val g2 = page.rasterGeometry(scale = 2.0)
        assertEquals(400, g2.widthPx)
        assertEquals(280, g2.heightPx)
        assertCorner(0.0, 280.0, g2, 50.0, 80.0, "crop bottom-left @2x")
    }

    /* ─── non-zero /MediaBox origin: no offset ─────────────────────────── */

    @Test
    fun non_zero_media_box_origin_introduces_no_offset() {
        // Same 612x792 sheet as the rotate-0 test, just moved to start at (20,30)
        // instead of (0,0). Every device corner below is identical to that
        // test's, on purpose: shifting the box must not shift the output.
        val page = openPdf(mediaBox = "20 30 632 822").pages[0]

        val g1 = page.rasterGeometry()
        assertEquals(612, g1.widthPx)
        assertEquals(792, g1.heightPx)
        assertCorner(0.0, 792.0, g1, 20.0, 30.0, "bottom-left")
        assertCorner(0.0, 0.0, g1, 20.0, 822.0, "top-left")
        assertCorner(612.0, 0.0, g1, 632.0, 822.0, "top-right")
        assertCorner(612.0, 792.0, g1, 632.0, 30.0, "bottom-right")

        val g2 = page.rasterGeometry(scale = 2.0)
        assertEquals(1224, g2.widthPx)
        assertEquals(1584, g2.heightPx)
        assertCorner(0.0, 1584.0, g2, 20.0, 30.0, "bottom-left @2x")
    }

    /* ─── /UserUnit: Table 30, a positive multiplier on the 1/72in unit ──── */

    @Test
    fun user_unit_two_doubles_output_dimensions_at_the_same_scale() {
        val page = openPdf(mediaBox = "0 0 612 792", userUnit = 2.0).pages[0]

        val g1 = page.rasterGeometry()
        assertEquals(1224, g1.widthPx)
        assertEquals(1584, g1.heightPx)
        assertCorner(0.0, 1584.0, g1, 0.0, 0.0, "bottom-left")
        assertCorner(1224.0, 0.0, g1, 612.0, 792.0, "top-right")

        // Composes with scale: userUnit 2 * scale 2 = 4x the unscaled page.
        val g2 = page.rasterGeometry(scale = 2.0)
        assertEquals(2448, g2.widthPx)
        assertEquals(3168, g2.heightPx)
        assertCorner(2448.0, 3168.0, g2, 612.0, 0.0, "bottom-right @4x")
    }

    @Test
    fun nonsense_user_unit_degrades_to_the_spec_default_of_one() {
        // Table 30 requires a positive number. 0 and a negative value are
        // malformed input (R6: lenient salvage), not a license to invert or
        // collapse the page, so both must render exactly as if /UserUnit were
        // absent: the same 612x792 as the plain rotate-0 fixture.
        for (nonsense in listOf(0.0, -3.0)) {
            val page = openPdf(mediaBox = "0 0 612 792", userUnit = nonsense).pages[0]
            val g = page.rasterGeometry()
            assertEquals(612, g.widthPx, "UserUnit $nonsense")
            assertEquals(792, g.heightPx, "UserUnit $nonsense")
            assertCorner(612.0, 0.0, g, 612.0, 792.0, "top-right, UserUnit $nonsense")
        }
    }

    /* ─── pixel sizing rounds UP, matching the Skia reference's ceil() ───── */

    @Test
    fun fractional_page_dimensions_round_up_not_down() {
        // 100.5 and 200.25 are exact in binary floating point, so this isolates
        // rounding direction from float noise: truncation would give 100x200,
        // ceiling gives 101x201.
        val page = openPdf(mediaBox = "0 0 100.5 200.25").pages[0]
        val g = page.rasterGeometry()
        assertEquals(101, g.widthPx)
        assertEquals(201, g.heightPx)
    }

    @Test
    fun invalid_scales_are_rejected_instead_of_building_broken_matrices() {
        val page = openPdf(mediaBox = "0 0 100 100").pages[0]
        for (scale in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("scale $scale") { page.rasterGeometry(scale) }
        }
    }

    @Test
    fun raster_allocation_has_a_configurable_pixel_ceiling() {
        val page = openPdf(mediaBox = "0 0 100 100").pages[0]
        assertFailsWith<IllegalArgumentException> { page.rasterGeometry(scale = 10.0, maxPixels = 999_999) }
        val allowed = page.rasterGeometry(scale = 10.0, maxPixels = 1_000_000)
        assertEquals(1_000, allowed.widthPx)
        assertEquals(1_000, allowed.heightPx)
    }

    /* ─── helpers ─────────────────────────────────────────────────────────── */

    /** `expected` is the device point [g]'s matrix maps user-space ([x], [y]) onto. */
    private fun assertCorner(expectedX: Double, expectedY: Double, g: PdfRasterGeometry, x: Double, y: Double, label: String) {
        val (ax, ay) = g.deviceCtm.transformPoint(x, y)
        assertEquals(expectedX, ax, 1e-9, "$label x")
        assertEquals(expectedY, ay, 1e-9, "$label y")
    }

    private fun openPdf(
        mediaBox: String,
        cropBox: String? = null,
        rotate: Int? = null,
        userUnit: Double? = null,
    ): PdfDocument {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun write(s: String) = buf.append(s.encodeToByteArray())

        write("%PDF-1.6\n%Äå\n")
        offsets.add(buf.size())
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets.add(buf.size())
        write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        offsets.add(buf.size())
        write("3 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >>")
        write(" /MediaBox [$mediaBox]")
        if (cropBox != null) write(" /CropBox [$cropBox]")
        if (rotate != null) write(" /Rotate $rotate")
        if (userUnit != null) write(" /UserUnit $userUnit")
        write(" >>\nendobj\n")

        val xref = buf.size()
        write("xref\n0 4\n0000000000 65535 f \n")
        for (off in offsets) write("${off.toString().padStart(10, '0')} 00000 n \n")
        write("trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(buf.toByteArray())
    }
}
