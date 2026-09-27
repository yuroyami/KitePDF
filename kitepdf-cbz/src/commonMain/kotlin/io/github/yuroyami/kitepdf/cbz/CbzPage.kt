package io.github.yuroyami.kitepdf.cbz

import io.github.yuroyami.kitepdf.core.KiteCancellation
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RgbColor

/**
 * One comic page: one image entry. Sized 1 px = 1 pt (a comic has no physical
 * size; the viewer scales to fit). Decodes at render time, and keeps the decoded
 * scan in [decoded] for the next render.
 */
public class CbzPage internal constructor(
    private val readEntry: () -> ByteArray?,
    internal val entryName: String,
    /** The first bytes of the entry, enough for its image header. */
    private val readHeader: () -> ByteArray? = readEntry,
    /** The comic's decoded scans, so a page drawn again does not decode again. */
    private val decoded: CbzImageCache? = null,
) : KitePage {

    /** Stands in when neither the header nor a full decode yields a size. */
    private val fallback = 800 to 1200

    /**
     * The size from the image header, which reads only the start of the entry, so a fling
     * through a comic does not inflate and check each scan to lay out its slot (#386).
     */
    private val size: Pair<Int, Int> by lazy {
        readHeader()?.let(ImageDims::of)
            ?: readEntry()?.let { bytes -> ImageDims.of(bytes) ?: KiteImageData.fromEncodedImage(bytes)?.let { it.width to it.height } }
            ?: fallback
    }

    override val displayWidth: Double get() = size.first.toDouble()
    override val displayHeight: Double get() = size.second.toDouble()

    override fun displayToDeviceBase(): KiteMatrix = KiteMatrix.IDENTITY

    override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix): Unit = render(canvas, deviceCtm, null)

    /** [renderTo] that stops between reading the entry and decoding it once [cancellation] reads true. */
    override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix, cancellation: KiteCancellation): Unit =
        render(canvas, deviceCtm, cancellation)

    private fun render(canvas: KiteCanvas, deviceCtm: KiteMatrix, cancellation: KiteCancellation?) {
        canvas.beginPage(displayWidth, displayHeight, deviceCtm)
        val image = decoded?.get(entryName) ?: run {
            val bytes = readEntry()
            if (cancellation?.isCancelled() == true) {
                canvas.endPage()
                return
            }
            bytes?.let { KiteImageData.fromEncodedImage(it) }?.also { decoded?.put(entryName, it) }
        }
        if (image == null) {
            // A page that cannot decode shows a grey sheet rather than nothing (#129).
            val sheet = KitePath.Builder().apply { rectangle(0.0, 0.0, displayWidth, displayHeight) }.build()
            canvas.fillPath(sheet, deviceCtm, PLACEHOLDER, evenOdd = false)
        } else {
            // Backends map the image's unit square (row 0 at v=1) through the CTM,
            // so an upright page in this y-down display space needs the y-flip form.
            val ctm = deviceCtm.concat(
                KiteMatrix(displayWidth, 0.0, 0.0, -displayHeight, 0.0, displayHeight)
            )
            canvas.drawImage(image, ctm)
        }
        canvas.endPage()
    }

    private companion object {
        val PLACEHOLDER = RgbColor(0.9, 0.9, 0.9)
    }
}
