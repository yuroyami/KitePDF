package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.difftest.ImageDiff
import io.github.yuroyami.kitepdf.difftest.MuPdfOracle
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The renderer fills a mesh shading into one image, so the page does not show
 * through the seams between its cells (#126, #196). Each fixture mixes red,
 * green and blue.
 */
class MeshSeamTest {

    /** A free-form mesh of one triangle: (20, 20) red, (180, 30) green, (100, 180) blue. */
    private fun triangle(): ByteArray = page(
        4,
        bytes(0, 20, 20, 255, 0, 0, 0, 180, 30, 0, 255, 0, 0, 100, 180, 0, 0, 255),
    )

    /**
     * A Coons patch with straight, slanted edges, corners (20, 30) red, (170, 20) green,
     * (180, 170) blue and (30, 180) half red and half green.
     */
    private fun patch(): ByteArray = page(
        6,
        bytes(
            0,
            20, 30, 70, 27, 120, 23, 170, 20, 173, 70, 177, 120,
            180, 170, 130, 173, 80, 177, 30, 180, 27, 130, 23, 80,
            255, 0, 0, 0, 255, 0, 0, 0, 255, 128, 127, 0,
        ),
    )

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /**
     * A 200 by 200 page that paints a mesh shading of [type] from [mesh]: one byte per
     * flag, coordinate and colour component, decoded to 0..255 and 0..1.
     */
    private fun page(type: Int, mesh: ByteArray): ByteArray {
        val content = "q 0 0 200 200 re W n /Sh1 sh Q".toByteArray()
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>".toByteArray(),
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".toByteArray(),
            ("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] " +
                "/Resources << /Shading << /Sh1 5 0 R >> >> /Contents 4 0 R >>").toByteArray(),
            "<< /Length ${content.size} >>\nstream\n".toByteArray() + content + "\nendstream".toByteArray(),
            ("<< /ShadingType $type /ColorSpace /DeviceRGB /BitsPerCoordinate 8 /BitsPerComponent 8 /BitsPerFlag 8 " +
                "/Decode [0 255 0 255 0 1 0 1 0 1] /Length ${mesh.size} >>\nstream\n").toByteArray() +
                mesh + "\nendstream".toByteArray(),
        )
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.7\n".toByteArray())
        val offsets = objects.mapIndexed { i, body ->
            out.size().also {
                out.write("${i + 1} 0 obj\n".toByteArray()); out.write(body); out.write("\nendobj\n".toByteArray())
            }
        }
        val xref = out.size()
        out.write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n".toByteArray())
        for (o in offsets) out.write("${o.toString().padStart(10, '0')} 00000 n \n".toByteArray())
        out.write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray())
        return out.toByteArray()
    }

    /**
     * The rectangle from (70, 50) to (130, 100) in page space, which lies inside the
     * triangle, as image rows 100 to 149 and columns 70 to 129.
     */
    private fun interior(image: BufferedImage): BufferedImage = image.getSubimage(70, 100, 60, 50)

    /**
     * Counts the pixels of [image] where the page shows through. Every colour of a
     * fixture mixes red, green and blue, so its channels add up to 255. Where the
     * white page shows through, the sum rises by 510 times the part that shows.
     */
    private fun pale(image: BufferedImage): Int {
        var pale = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val c = image.getRGB(x, y)
            if (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF) > 285) pale++
        }
        return pale
    }

    @Test
    fun no_page_shows_between_the_triangles() {
        val inside = interior(AwtPdfRasterizer.renderToImage(KitePDF.open(triangle()).pages[0]))
        assertEquals(0, pale(inside), "pixels where the page shows through a seam")
    }

    @Test
    fun no_page_shows_between_the_patch_cells() {
        // The rectangle from (50, 50) to (150, 150) in page space lies inside the patch.
        val inside = AwtPdfRasterizer.renderToImage(KitePDF.open(patch()).pages[0]).getSubimage(50, 50, 100, 100)
        assertEquals(0, pale(inside), "pixels where the page shows through a seam")
    }

    @Test
    fun the_inside_of_the_triangle_is_close_to_mutool() {
        assumeTrue("mutool not found, skipping.", MuPdfOracle.binary != null)
        val bytes = triangle()
        val file = File.createTempFile("kite-mesh-seam", ".pdf").apply { deleteOnExit(); writeBytes(bytes) }
        val reference = MuPdfOracle.render(file, 1, 72)!!
        val kite = AwtPdfRasterizer.renderToImage(KitePDF.open(bytes).pages[0])
        val mae = ImageDiff.compare(interior(kite), interior(reference)).meanAbsError
        println("mesh interior vs mutool: MAE=${"%.5f".format(mae)}")
        assertTrue(mae < 0.03, "the inside of the triangle differs from mutool (MAE=$mae)")
    }
}
