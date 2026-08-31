package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `sh` paints the current clipping region (ISO 32000-1, 8.7.4.3), so
 * redaction judges it by the clip's boundary, mirroring the path-segment
 * rule: a shading whose visible edge crosses a region is removed, one whose
 * clip lies wholly inside a region is removed, and one that merely surrounds
 * the region survives under the black box, like a full-page background.
 */
class RedactionShadingTest {

    private val shadingResources = """<< /Font << /F1 4 0 R >>
        /Shading << /Sh1 << /ShadingType 2 /ColorSpace /DeviceRGB
          /Coords [0 0 612 792]
          /Function << /FunctionType 2 /Domain [0 1] /C0 [1 0 0] /C1 [0 0 1] /N 1 >> >> >> >>"""

    private fun pdf(content: String): ByteArray =
        RawPdf.page(content.encodeToByteArray(), resources = shadingResources)

    private fun redact(pdf: ByteArray, region: KiteRectangle): ByteArray {
        val doc = KitePDF.open(pdf)
        return doc.edit().apply { redactRegion(doc.pages[0], region) }.saveRewritten()
    }

    /** The rewritten page's decompressed content, where the `sh` op would live. */
    private fun content(pdf: ByteArray): String = KitePDF.open(pdf).pages[0].contentBytes.decodeToString()

    private fun hasSh(pdf: ByteArray): Boolean =
        Regex("""(^|[^A-Za-z])sh($|[^A-Za-z])""").containsMatchIn(content(pdf))

    @Test
    fun a_shading_whose_clip_edge_crosses_the_region_is_removed() {
        // Clip box 100..200 square; region 150..250 crosses its right and top edges.
        val out = redact(
            pdf("q 100 100 100 100 re W n /Sh1 sh Q"),
            KiteRectangle(left = 150.0, bottom = 150.0, right = 250.0, top = 250.0),
        )
        assertFalse(hasSh(out), "the clipped shading's edge crosses the region; sh must go:\n${content(out)}")
    }

    @Test
    fun a_shading_clipped_wholly_inside_the_region_is_removed() {
        val out = redact(
            pdf("q 100 100 100 100 re W n /Sh1 sh Q"),
            KiteRectangle(left = 50.0, bottom = 50.0, right = 300.0, top = 300.0),
        )
        assertFalse(hasSh(out), "a shading entirely inside the region is region content:\n${content(out)}")
    }

    @Test
    fun an_unclipped_page_shading_survives_like_a_background() {
        val out = redact(
            pdf("/Sh1 sh"),
            KiteRectangle(left = 150.0, bottom = 150.0, right = 250.0, top = 250.0),
        )
        assertTrue(hasSh(out), "a page-wide shading is a background; the black box covers the region:\n${content(out)}")
    }

    @Test
    fun a_clip_strictly_containing_the_region_survives() {
        // Clip box 50..550; region 200..300 sits inside with every edge clear.
        val out = redact(
            pdf("q 50 50 500 500 re W n /Sh1 sh Q"),
            KiteRectangle(left = 200.0, bottom = 200.0, right = 300.0, top = 300.0),
        )
        assertTrue(hasSh(out), "the region is interior; edges are clear and the box covers it:\n${content(out)}")
    }

    @Test
    fun a_clip_disjoint_from_the_region_survives() {
        val out = redact(
            pdf("q 100 100 100 100 re W n /Sh1 sh Q"),
            KiteRectangle(left = 400.0, bottom = 400.0, right = 500.0, top = 500.0),
        )
        assertTrue(hasSh(out), "the shading is nowhere near the region:\n${content(out)}")
    }
}
