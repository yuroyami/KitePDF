package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlinx.browser.document
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Canvas2D gives back the pixels of each layer it is done with, and a layer the browser will not
 * give, a null 2D context as WebKit returns past its canvas memory, paints without its layer
 * instead of throwing out of the draw (#889).
 */
class Canvas2dLayerReleaseTest {

    /** A group at half alpha, a soft-masked square and a raw image, which each need a canvas of their own. */
    private val page = testPage(
        "q /Half gs /Fm0 Do Q q /GS1 gs 1 0 0 rg 20 20 60 60 re f Q q 40 0 0 40 120 120 cm /Im0 Do Q",
        "/ExtGState << /Half << /ca 0.5 /CA 0.5 >> /GS1 << /SMask << /S /Alpha /G 5 0 R >> >> >> " +
            "/XObject << /Fm0 6 0 R /Im0 7 0 R >>",
        listOf(
            // 5: the mask, which lets the square through; 6: the group, blue on the top left.
            groupForm("0 g 0 0 100 100 re f"),
            groupForm("0 0 1 rg 0 120 100 80 re f"),
            // 7: a black image; the page is encoded as UTF-8, so its samples are bytes below 0x80.
            "<< /Type /XObject /Subtype /Image /Width 2 /Height 2 /ColorSpace /DeviceGray /BitsPerComponent 8 /Length 4 >>\n" +
                "stream\n\u0000\u0000\u0000\u0000\nendstream",
        ),
    )

    private fun hostCanvas(): CanvasRenderingContext2D {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.width = SIDE
        canvas.height = SIDE
        val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
        ctx.fillStyle = "white"
        ctx.fillRect(0.0, 0.0, SIDE.toDouble(), SIDE.toDouble())
        return ctx
    }

    private fun render(ctx: CanvasRenderingContext2D) {
        PdfDocument.open(page).pages[0].renderTo(Canvas2dCanvas(ctx), KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, SIDE.toDouble()))
    }

    @Test
    fun every_layer_canvas_gives_its_pixels_back_once_it_has_composited() {
        val ctx = hostCanvas()
        val made: dynamic = js("[]")
        val original: dynamic = js("document.createElement")
        document.asDynamic().createElement = { name: String ->
            val element = original.call(document, name)
            if (name == "canvas") made.push(element)
            element
        }
        try {
            render(ctx)
        } finally {
            document.asDynamic().createElement = original
        }
        val count = made.length as Int
        assertTrue(count >= 3, "the page made $count canvases")
        // The cache keeps the image's canvas for its next draw; every layer is back at no size.
        val held = (0 until count).count { (made[it].width as Int) > 0 }
        assertEquals(1, held, "canvases still holding pixels")
        // The group at half alpha, the mask and the image still draw.
        ctx.assertPixel(50, 150, listOf(128, 128, 255), tolerance = 4)
        ctx.assertPixel(50, 50, listOf(255, 0, 0))
        ctx.assertPixel(140, 140, listOf(0, 0, 0))
    }

    @Test
    fun a_layer_the_browser_will_not_give_paints_without_it_and_an_image_shows_its_placeholder() {
        val ctx = hostCanvas()
        val proto: dynamic = js("HTMLCanvasElement.prototype")
        val original: dynamic = proto.getContext
        proto.getContext = js("(function () { return null; })")
        try {
            render(ctx)
        } finally {
            proto.getContext = original
        }
        // The group paints straight on the page at full strength, the soft-masked square shows unmasked, and the image is its placeholder.
        ctx.assertPixel(50, 150, listOf(0, 0, 255))
        ctx.assertPixel(50, 50, listOf(255, 0, 0))
        ctx.assertPixel(140, 140, listOf(0xE0, 0xE0, 0xE0))
    }
}
