package io.github.yuroyami.kitepdf.cbz

import io.github.yuroyami.kitepdf.core.KiteCancellation
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteImageIdentity
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
    /** The immutable archive entry, kept distinct from entries of every other comic (#371). */
    private val imageIdentity: KiteImageIdentity = KiteImageIdentity(),
) : KitePage {

    /** Stands in when neither the header nor a full decode yields a size. */
    private val fallback = 800 to 1200

    /**
     * The size from the image header, which reads only the start of the entry, so a fling
     * through a comic does not inflate and check each scan to lay out its slot (#386).
     */
    private val size: Pair<Int, Int> by lazy {
        // A header that does not parse is a placeholder, never a failure of the comic (#760).
        // A photo turned by its EXIF orientation takes its upright size, as it draws upright (#826).
        fun dims(bytes: ByteArray) = runCatching { ImageDims.of(bytes) }.getOrNull()
            ?.let { (w, h) -> if (KiteImageData.swapsAxes(bytes)) h to w else w to h }
        readHeader()?.let(::dims)
            ?: readEntry()?.let { bytes -> dims(bytes) ?: runCatching { KiteImageData.fromEncodedImage(bytes) }.getOrNull()?.let { it.width to it.height } }
            ?: fallback
    }

    override val displayWidth: Double get() = size.first.toDouble()
    override val displayHeight: Double get() = size.second.toDouble()

    override fun displayToDeviceBase(): KiteMatrix = KiteMatrix.IDENTITY

    /** The animation of an animated scan, known once the page drew (#939). It outlives the decoded-scan cache. */
    @kotlin.concurrent.Volatile
    private var animation: io.github.yuroyami.kitepdf.core.render.KiteImageAnimation? = null

    @kotlin.concurrent.Volatile
    private var probed = false

    /** The scan's animation: read from the entry's header the first time, unless a render found it first. */
    override val animations: List<io.github.yuroyami.kitepdf.core.render.KiteImageAnimation>
        get() {
            if (!probed) {
                probed = true
                if (animation == null) {
                    val found = readEntry()?.let { io.github.yuroyami.kitepdf.core.render.KiteImageAnimation.probe(it) }
                    if (animation == null) animation = found
                }
            }
            return animation?.let(::listOf) ?: emptyList()
        }

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
            bytes?.let { KiteImageData.fromEncodedImage(it) }?.withIdentity(imageIdentity)
                ?.also { decoded?.put(entryName, it) }
        }
        // A scan decoded again keeps the animation a player already holds.
        if (animation == null) animation = image?.animation
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
            // An animated scan draws the frame its player is on (#939).
            canvas.drawImage(animation?.currentFrame() ?: image, ctm)
        }
        canvas.endPage()
    }

    private companion object {
        val PLACEHOLDER = RgbColor(0.9, 0.9, 0.9)
    }
}
