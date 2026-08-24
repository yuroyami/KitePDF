package io.github.yuroyami.kitepdf.cbz

import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteMatrix

/**
 * One comic page: one image entry. Sized 1 px = 1 pt (a comic has no physical
 * size; the viewer scales to fit). Decodes at render time; the viewer's page
 * bitmap cache absorbs repeats.
 */
public class CbzPage internal constructor(
    private val readEntry: () -> ByteArray?,
    internal val entryName: String,
) : KitePage {

    /** Stands in when neither the header nor a full decode yields a size. */
    private val fallback = 800 to 1200

    private val size: Pair<Int, Int> by lazy {
        val bytes = readEntry()
        bytes?.let(ImageDims::of)
            ?: bytes?.let { KiteImageData.fromEncodedImage(it) }?.let { it.width to it.height }
            ?: fallback
    }

    override val displayWidth: Double get() = size.first.toDouble()
    override val displayHeight: Double get() = size.second.toDouble()

    override fun displayToDeviceBase(): KiteMatrix = KiteMatrix.IDENTITY

    override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) {
        val image = readEntry()?.let { KiteImageData.fromEncodedImage(it) } ?: return
        // Backends map the image's unit square (row 0 at v=1) through the CTM,
        // so an upright page in this y-down display space needs the y-flip form.
        val ctm = deviceCtm.concat(
            KiteMatrix(displayWidth, 0.0, 0.0, -displayHeight, 0.0, displayHeight)
        )
        canvas.drawImage(image, ctm)
    }
}
