package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.Rectangle

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the production-style page boxes (BleedBox/TrimBox/ArtBox) and the
 * UserUnit scaling factor.
 *
 * The PDF spec (ISO 32000-1 §14.11.2) defines a nesting hierarchy: every box
 * other than MediaBox is optional, and an absent one falls back to CropBox
 * (which itself falls back to MediaBox).
 */
class PageBoxesTest {

    @Test
    fun absent_boxes_fall_back_to_crop_box() {
        // Page with MediaBox + CropBox but no Bleed/Trim/Art.
        val doc = KitePDF.open(
            buildPdf(
                mediaBox = "0 0 612 792",
                cropBox = "10 10 600 780",
                bleedBox = null,
                trimBox = null,
                artBox = null,
            ),
        )
        val p = doc.pages[0]
        assertEquals(Rectangle(0.0, 0.0, 612.0, 792.0), p.mediaBox)
        assertEquals(Rectangle(10.0, 10.0, 600.0, 780.0), p.cropBox)
        // Bleed/Trim/Art should default to the cropBox.
        assertEquals(p.cropBox, p.bleedBox)
        assertEquals(p.cropBox, p.trimBox)
        assertEquals(p.cropBox, p.artBox)
    }

    @Test
    fun crop_box_falls_back_to_media_box_when_absent() {
        val doc = KitePDF.open(
            buildPdf(
                mediaBox = "0 0 612 792",
                cropBox = null,
                bleedBox = null,
                trimBox = null,
                artBox = null,
            ),
        )
        val p = doc.pages[0]
        assertEquals(p.mediaBox, p.cropBox)
        assertEquals(p.mediaBox, p.trimBox)
    }

    @Test
    fun explicit_production_boxes_parse_correctly() {
        val doc = KitePDF.open(
            buildPdf(
                mediaBox = "0 0 612 792",
                cropBox = "10 10 600 780",
                bleedBox = "5 5 605 785",
                trimBox = "20 20 590 770",
                artBox = "40 40 570 750",
            ),
        )
        val p = doc.pages[0]
        assertEquals(Rectangle(5.0, 5.0, 605.0, 785.0), p.bleedBox)
        assertEquals(Rectangle(20.0, 20.0, 590.0, 770.0), p.trimBox)
        assertEquals(Rectangle(40.0, 40.0, 570.0, 750.0), p.artBox)
    }

    @Test
    fun user_unit_defaults_to_one() {
        val doc = KitePDF.open(MetadataPdfBuilder.simpleTwoPagePdf())
        assertEquals(1.0, doc.pages[0].userUnit)
    }

    @Test
    fun user_unit_parses_real_value() {
        val doc = KitePDF.open(
            buildPdf(
                mediaBox = "0 0 612 792",
                userUnit = 2.5,
            ),
        )
        assertEquals(2.5, doc.pages[0].userUnit)
    }

    /* ─── Helper ──────────────────────────────────────────────────────────── */

    private fun buildPdf(
        mediaBox: String,
        cropBox: String? = null,
        bleedBox: String? = null,
        trimBox: String? = null,
        artBox: String? = null,
        userUnit: Double? = null,
    ): ByteArray {
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
        if (bleedBox != null) write(" /BleedBox [$bleedBox]")
        if (trimBox != null) write(" /TrimBox [$trimBox]")
        if (artBox != null) write(" /ArtBox [$artBox]")
        if (userUnit != null) write(" /UserUnit $userUnit")
        write(" >>\nendobj\n")

        val xref = buf.size()
        write("xref\n0 4\n0000000000 65535 f \n")
        for (off in offsets) write("${off.toString().padStart(10, '0')} 00000 n \n")
        write("trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
