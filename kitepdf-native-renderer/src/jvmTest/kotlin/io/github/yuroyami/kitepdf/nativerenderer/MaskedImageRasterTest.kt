package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end gate for stencil `/Mask` (ISO 32000-1 §8.9.6), built from a
 * synthetic PDF so it never depends on a corpus file.
 *
 * The fixture is a miniature of what MRC scanners emit and what used to render
 * as a solid black page: a small block of pure black ink, and a stencil four
 * times finer that lets the ink through on one half only. Without `/Mask` the
 * ink covers everything, which is exactly the bug, so the same fixture is
 * rendered both ways and the two halves are compared.
 */
class MaskedImageRasterTest {

    @Test
    fun stencil_mask_lets_the_ink_through_on_one_half_only() {
        val img = render(withMask = true)
        assertTrue(isWhite(img, 150, 400), "masked-out half must keep the page background")
        assertTrue(isBlack(img, 450, 400), "unmasked half must be painted with the image's ink")
    }

    @Test
    fun the_same_ink_layer_without_a_mask_covers_everything() {
        val img = render(withMask = false)
        assertTrue(isBlack(img, 150, 400), "without /Mask the ink covers the whole image area")
        assertTrue(isBlack(img, 450, 400))
    }

    @Test
    fun masking_leaves_the_page_outside_the_image_alone() {
        val img = render(withMask = true)
        assertTrue(isWhite(img, 50, 400), "left of the image")
        assertTrue(isWhite(img, 560, 400), "right of the image")
        assertTrue(isWhite(img, 300, 50), "above the image")
    }

    /* ─── Helpers ─────────────────────────────────────────────────────────── */

    private fun render(withMask: Boolean): BufferedImage =
        AwtPdfRasterizer.renderToImage(KitePDF.open(buildPdf(withMask)).pages[0], scale = 1.0)

    private fun isBlack(img: BufferedImage, x: Int, y: Int): Boolean {
        val p = img.getRGB(x, y)
        return ((p shr 16) and 0xFF) < 40 && ((p shr 8) and 0xFF) < 40 && (p and 0xFF) < 40
    }

    private fun isWhite(img: BufferedImage, x: Int, y: Int): Boolean {
        val p = img.getRGB(x, y)
        return ((p shr 16) and 0xFF) > 215 && ((p shr 8) and 0xFF) > 215 && (p and 0xFF) > 215
    }

    /**
     * A one-page PDF drawing an 8×8 all-black DeviceGray image over
     * `100 200 400 400`, optionally through a 32×32 stencil whose left half is
     * masked out (sample 1) and right half painted (sample 0).
     */
    private fun buildPdf(withMask: Boolean): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun w(s: String) = buf.append(s.encodeToByteArray())
        fun obj(body: String) {
            offsets.add(buf.size())
            w(body)
        }

        val ink = ByteArray(8 * 8) // every sample 0 = black
        // 32 rows of 4 bytes: 0xFF 0xFF masks the left 16 columns out, 0x00 0x00
        // lets the right 16 through.
        val stencil = ByteArray(32 * 4) { if (it % 4 < 2) 0xFF.toByte() else 0x00 }
        val content = "q 400 0 0 400 100 200 cm /Im0 Do Q".encodeToByteArray()

        w("%PDF-1.4\n%Äå\n")
        obj("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        obj("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 612 792] >>\nendobj\n")
        obj(
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /Contents 4 0 R " +
                "/Resources << /XObject << /Im0 5 0 R >> >> >>\nendobj\n",
        )
        offsets.add(buf.size())
        w("4 0 obj\n<< /Length ${content.size} >>\nstream\n")
        buf.append(content)
        w("\nendstream\nendobj\n")

        offsets.add(buf.size())
        w(
            "5 0 obj\n<< /Type /XObject /Subtype /Image /Width 8 /Height 8 " +
                "/ColorSpace /DeviceGray /BitsPerComponent 8 " +
                (if (withMask) "/Mask 6 0 R " else "") +
                "/Length ${ink.size} >>\nstream\n",
        )
        buf.append(ink)
        w("\nendstream\nendobj\n")

        offsets.add(buf.size())
        w(
            "6 0 obj\n<< /Type /XObject /Subtype /Image /Width 32 /Height 32 " +
                "/ImageMask true /BitsPerComponent 1 /Length ${stencil.size} >>\nstream\n",
        )
        buf.append(stencil)
        w("\nendstream\nendobj\n")

        val xref = buf.size()
        val count = offsets.size + 1
        w("xref\n0 $count\n0000000000 65535 f \n")
        for (o in offsets) w("${o.toString().padStart(10, '0')} 00000 n \n")
        w("trailer\n<< /Size $count /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }

    @Test
    fun the_masked_image_composites_on_the_stencil_grid() {
        // The stencil is 4× finer than the ink layer, so the composite is built
        // on the stencil's 32×32 grid: downsampling it would blur the edge that
        // divides the two halves. The edge must land within a pixel of the
        // image's midpoint (device x = 300).
        val img = render(withMask = true)
        var edge = -1
        for (x in 100 until 500) if (isBlack(img, x, 400)) { edge = x; break }
        assertEquals(300, edge, "the mask's edge must fall on the image's midpoint")
    }
}
