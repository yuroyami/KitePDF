package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix

/**
 * A standalone `.svg` file read as a one-page document, so a viewer can open
 * it the same way it opens a PDF or a comic.
 *
 * The page is the SVG's own viewport, 1 px = 1 pt; a viewer scales to fit.
 * Everything is drawn as vectors, so zooming stays sharp.
 *
 * ```kotlin
 * val doc = SvgDocument.open(bytes)
 * doc.pages.single().renderTo(canvas, ctm)
 * ```
 */
public class SvgDocument private constructor(
    private val image: SvgImage,
) : KiteDocument {

    override val pages: List<KitePage> = listOf(SvgPage(image))

    override val pageCount: Int get() = 1

    public companion object {

        /**
         * Reads [bytes] as an SVG image.
         *
         * @throws KiteFormatException when there is no `<svg>` element to draw.
         */
        public fun open(bytes: ByteArray): SvgDocument {
            val image = SvgImage.parse(bytes)
                ?: throw KiteFormatException("no <svg> element in ${bytes.size} bytes")
            return SvgDocument(image)
        }

        /** [open], but null instead of an exception on anything unreadable. */
        public fun openOrNull(bytes: ByteArray): SvgDocument? =
            try {
                open(bytes)
            } catch (_: Exception) {
                null
            }
    }
}

/** The single page of an [SvgDocument]: the SVG viewport, painted as vectors. */
public class SvgPage internal constructor(
    private val image: SvgImage,
) : KitePage {

    override val displayWidth: Double get() = image.width
    override val displayHeight: Double get() = image.height

    /** SVG is y-down from the top-left, so the base is a straight vertical flip. */
    override fun displayToDeviceBase(): KiteMatrix =
        KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, displayHeight)

    override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) {
        canvas.beginPage(displayWidth, displayHeight, deviceCtm)
        image.render(canvas, deviceCtm)
        canvas.endPage()
    }
}
