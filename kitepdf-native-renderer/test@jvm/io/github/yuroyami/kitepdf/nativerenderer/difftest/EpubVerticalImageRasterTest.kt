package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pixel regressions for physical replaced images and object-fit:cover (#100, #170). */
class EpubVerticalImageRasterTest {
    private val modes = listOf("horizontal-tb", "vertical-rl", "vertical-lr")
    private val settings = EpubSettings(pageWidth = 300.0, pageHeight = 400.0, margin = 20.0, fontSize = 10.0)

    private fun raster(mode: String, display: String, image: ByteArray, width: Int, height: Int, cover: Boolean = false, svg: Boolean = false): BufferedImage {
        val name = if (svg) "pic.svg" else "pic.png"
        val body = """
            <style>html{writing-mode:$mode}body,p{margin:0;padding:0;text-indent:0}</style>
            <p><img src="$name" style="display:$display;width:${width}pt;height:${height}pt;object-fit:${if (cover) "cover" else "fill"}"/></p>
        """.trimIndent()
        val doc = EpubDocument.open(EpubCorpus.epub(body, listOf("OEBPS/$name" to image)), settings)
        assertEquals(1, doc.pages.size, "$mode $display image fits one page")
        return EpubCorpus.rasterize(doc.pages.single())
    }

    // In vertical-lr the line's over side is its block-end side (CSS Writing Modes 4, 6.3),
    // so an inline image sits past the line's under side: 0.6 em of this 10pt text (#261).
    private fun left(mode: String, display: String, width: Int): Int = when {
        mode == "vertical-rl" -> 280 - width
        mode == "vertical-lr" && display == "inline" -> 26
        else -> 20
    }

    private fun assertPixel(image: BufferedImage, x: Int, y: Int, expected: Int, label: String) {
        assertEquals(expected, image.getRGB(x, y) and 0xffffff, "$label pixel at ($x,$y)")
    }

    @Test
    fun non_square_quadrants_keep_their_physical_position_in_every_writing_mode() {
        for (mode in modes) for (display in listOf("block", "inline")) {
            val image = raster(mode, display, EpubCorpus.quadrantPng(), 80, 40)
            val x = left(mode, display, 80)
            val label = "$mode $display"
            assertPixel(image, x + 20, 30, 0xff0000, "$label top-left")
            assertPixel(image, x + 60, 30, 0x00ff00, "$label top-right")
            assertPixel(image, x + 20, 50, 0x0000ff, "$label bottom-left")
            assertPixel(image, x + 60, 50, 0xffffff, "$label bottom-right")
            assertPixel(image, x - 2, 30, 0xffffff, "$label outside left")
            assertPixel(image, x + 82, 30, 0xffffff, "$label outside right")
            assertPixel(image, x + 20, 18, 0xffffff, "$label outside top")
            assertPixel(image, x + 20, 62, 0xffffff, "$label outside bottom")
        }
    }

    @Test
    fun landscape_cover_crops_side_bands_and_does_not_leak_outside_the_box() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" width="90" height="30">
                <rect width="30" height="30" fill="#ff0000"/>
                <rect x="30" width="30" height="30" fill="#00ff00"/>
                <rect x="60" width="30" height="30" fill="#0000ff"/>
            </svg>
        """.trimIndent().encodeToByteArray()
        for (mode in modes) for (display in listOf("block", "inline")) for (vector in listOf(false, true)) {
            val image = raster(mode, display, if (vector) svg else EpubCorpus.bandPng(), 60, 60, cover = true, svg = vector)
            val x = left(mode, display, 60)
            val label = "$mode $display ${if (vector) "SVG" else "PNG"} landscape cover"
            for (dx in listOf(5, 30, 54)) for (dy in listOf(5, 30, 54)) {
                assertPixel(image, x + dx, 20 + dy, 0x00ff00, label)
            }
            assertPixel(image, x - 3, 40, 0xffffff, "$label clipped left")
            assertPixel(image, x + 63, 40, 0xffffff, "$label clipped right")
            assertPixel(image, x + 30, 17, 0xffffff, "$label outside top")
            assertPixel(image, x + 30, 83, 0xffffff, "$label outside bottom")
        }
    }

    @Test
    fun portrait_cover_crops_top_and_bottom_in_physical_coordinates() {
        for (mode in modes) for (display in listOf("block", "inline")) {
            val image = raster(mode, display, EpubCorpus.bandPng(tall = true), 60, 40, cover = true)
            val x = left(mode, display, 60)
            val label = "$mode $display portrait cover"
            for (dx in listOf(5, 30, 54)) for (dy in listOf(5, 20, 34)) {
                assertPixel(image, x + dx, 20 + dy, 0x00ff00, label)
            }
            assertPixel(image, x - 3, 40, 0xffffff, "$label outside left")
            assertPixel(image, x + 63, 40, 0xffffff, "$label outside right")
            assertPixel(image, x + 30, 17, 0xffffff, "$label clipped top")
            assertPixel(image, x + 30, 63, 0xffffff, "$label clipped bottom")
        }
    }
}
