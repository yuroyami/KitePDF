package io.github.yuroyami.kitepdf.nativerenderer.difftest

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.abs

/**
 * Pixel-level comparison between a KitePDF raster and a reference raster
 * (typically MuPDF's `mutool draw` output).
 *
 * Both inputs are first flattened onto an opaque white background so that
 * alpha-vs-no-alpha differences between the two engines don't pollute the
 * score, then the reference is resized to the KitePDF dimensions if the two
 * engines disagree by a rounding pixel.
 *
 * The score is a normalized mean-absolute-error over the RGB channels:
 *   0.0  → byte-identical
 *   1.0  → maximally different (e.g. solid black vs solid white)
 * It is the primary ranking key — "show me the worst-rendering pages first."
 */
object ImageDiff {

    /** Per-channel delta above which a pixel counts toward [DiffResult.diffFraction]. */
    private const val PERCEPTUAL_THRESHOLD = 16

    data class DiffResult(
        val width: Int,
        val height: Int,
        /** Normalized mean absolute error over RGB, 0.0 (identical) .. 1.0 (max). */
        val meanAbsError: Double,
        /** Fraction of pixels whose worst channel delta exceeds [PERCEPTUAL_THRESHOLD]. */
        val diffFraction: Double,
        /** Worst single-channel delta seen, 0..255. */
        val maxChannelDelta: Int,
        /** Red-on-white heatmap: white = identical, deeper red = larger local error. */
        val heatmap: BufferedImage,
    ) {
        /** Primary ranking key. */
        val score: Double get() = meanAbsError
    }

    fun compare(kite: BufferedImage, reference: BufferedImage, amplify: Int = 6): DiffResult {
        val a = flattenOntoWhite(kite)
        val refSized =
            if (reference.width != a.width || reference.height != a.height)
                resizeTo(reference, a.width, a.height)
            else reference
        val b = flattenOntoWhite(refSized)

        val w = a.width
        val h = a.height
        val heatmap = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)

        var sumErr = 0L
        var diffPixels = 0L
        var maxDelta = 0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val pa = a.getRGB(x, y)
                val pb = b.getRGB(x, y)
                val dr = abs(((pa ushr 16) and 0xFF) - ((pb ushr 16) and 0xFF))
                val dg = abs(((pa ushr 8) and 0xFF) - ((pb ushr 8) and 0xFF))
                val db = abs((pa and 0xFF) - (pb and 0xFF))

                sumErr += (dr + dg + db).toLong()
                val pixMax = maxOf(dr, dg, db)
                if (pixMax > maxDelta) maxDelta = pixMax
                if (pixMax > PERCEPTUAL_THRESHOLD) diffPixels++

                // White where identical; intensifying red as the local error grows.
                val mean = (dr + dg + db) / 3
                val intensity = (mean * amplify).coerceAtMost(255)
                val gb = 255 - intensity
                heatmap.setRGB(x, y, (255 shl 16) or (gb shl 8) or gb)
            }
        }

        val totalPix = (w.toLong() * h.toLong()).coerceAtLeast(1L)
        val mae = sumErr.toDouble() / (totalPix.toDouble() * 3.0 * 255.0)
        return DiffResult(
            width = w,
            height = h,
            meanAbsError = mae,
            diffFraction = diffPixels.toDouble() / totalPix.toDouble(),
            maxChannelDelta = maxDelta,
            heatmap = heatmap,
        )
    }

    /** Count of pixels that differ from the (white) page background — used for blank-render detection. */
    fun nonBackgroundPixels(img: BufferedImage): Long {
        val rgb = flattenOntoWhite(img)
        var count = 0L
        for (y in 0 until rgb.height) {
            for (x in 0 until rgb.width) {
                val p = rgb.getRGB(x, y)
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                // Anything meaningfully darker/coloured than near-white counts as content.
                if (r < 250 || g < 250 || b < 250) count++
            }
        }
        return count
    }

    private fun flattenOntoWhite(src: BufferedImage): BufferedImage {
        if (src.type == BufferedImage.TYPE_INT_RGB && !src.colorModel.hasAlpha()) return src
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, src.width, src.height)
            g.drawImage(src, 0, 0, null)
        } finally {
            g.dispose()
        }
        return out
    }

    private fun resizeTo(src: BufferedImage, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            g.color = Color.WHITE
            g.fillRect(0, 0, w, h)
            g.drawImage(src, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return out
    }
}
