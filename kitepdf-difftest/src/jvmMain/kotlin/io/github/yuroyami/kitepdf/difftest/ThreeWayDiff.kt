package io.github.yuroyami.kitepdf.difftest

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.abs

/**
 * Compares one page as KitePDF, MuPDF and PDFium render it, and names the renderer that
 * differs from the other two.
 *
 * MuPDF and PDFium disagree with each other in known ways, such as anti-aliasing, the
 * weight of thin lines and the fonts that replace the standard 14 fonts. So a difference
 * between KitePDF and one reference proves nothing by itself. When the two references
 * agree and KitePDF differs from both, KitePDF is the odd one out.
 *
 * A renderer is the odd one out when its distance to each of the other two is more than
 * [RATIO] times their distance to each other, plus a margin. The test runs on the whole
 * page and on each tile of [TILE] pixels, so a small missing element counts as well as a
 * colour shift across the page. Distances are the mean absolute error over RGB, from 0
 * for identical to 1 for black against white.
 */
public object ThreeWayDiff {

    public const val TILE: Int = 24

    private const val RATIO = 2.0

    /** The margin on a tile: about 5 levels a channel. */
    private const val TILE_MARGIN = 0.02

    /** The margin on the whole page: about a quarter of a level a channel. */
    private const val PAGE_MARGIN = 0.001

    public enum class Engine { KITE, MUPDF, PDFIUM }

    public data class Tile(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val kiteMupdf: Double,
        val kitePdfium: Double,
        val mupdfPdfium: Double,
    )

    public data class Result(
        val width: Int,
        val height: Int,
        val kiteMupdf: Double,
        val kitePdfium: Double,
        val mupdfPdfium: Double,
        /** The engine that differs from the other two on the whole page, or null. */
        val pageOutlier: Engine?,
        /** For each engine, the tiles where it alone differs. */
        val outlierTiles: Map<Engine, Int>,
        val tiles: Int,
        /** The tile where KitePDF alone differs the most, or null when it never does. */
        val worstKiteTile: Tile?,
        /** Red where KitePDF alone differs, blue where PDFium alone differs, green where MuPDF alone differs. */
        val map: BufferedImage,
    ) {
        /** True when KitePDF alone differs, on some tile or on the whole page. */
        val kiteIsOutlier: Boolean get() = pageOutlier == Engine.KITE || outlierTiles.getValue(Engine.KITE) > 0
    }

    /**
     * The odd one out of three renderers, from their three pairwise distances, or null when
     * none is clearly apart. At most one engine can qualify.
     */
    public fun outlier(kiteMupdf: Double, kitePdfium: Double, mupdfPdfium: Double, margin: Double): Engine? = when {
        minOf(kiteMupdf, kitePdfium) > RATIO * mupdfPdfium + margin -> Engine.KITE
        minOf(kitePdfium, mupdfPdfium) > RATIO * kiteMupdf + margin -> Engine.PDFIUM
        minOf(kiteMupdf, mupdfPdfium) > RATIO * kitePdfium + margin -> Engine.MUPDF
        else -> null
    }

    /**
     * Compares the three renders of one page. Each reference may differ from the KitePDF
     * size by one pixel, for rounding, and is then scaled to it.
     */
    public fun compare(kite: BufferedImage, mupdf: BufferedImage, pdfium: BufferedImage): Result {
        val w = kite.width
        val h = kite.height
        for ((name, image) in listOf("MuPDF" to mupdf, "PDFium" to pdfium)) {
            require(abs(image.width - w) <= 1 && abs(image.height - h) <= 1) {
                "page dimensions differ: KitePDF=${w}x$h, $name=${image.width}x${image.height}"
            }
        }
        val k = rgb(kite, w, h)
        val m = rgb(mupdf, w, h)
        val p = rgb(pdfium, w, h)
        val map = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val row = IntArray(w)
        var sumKm = 0L
        var sumKp = 0L
        var sumMp = 0L
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val km = distance(k[i], m[i])
                val kp = distance(k[i], p[i])
                val mp = distance(m[i], p[i])
                sumKm += km
                sumKp += kp
                sumMp += mp
                row[x] = mapColour(km, kp, mp)
            }
            map.setRGB(0, y, w, 1, row, 0, w)
        }
        val pixels = w.toDouble() * h * 765.0
        val kiteMupdf = sumKm / pixels
        val kitePdfium = sumKp / pixels
        val mupdfPdfium = sumMp / pixels

        val counts = Engine.entries.associateWith { 0 }.toMutableMap()
        var worst: Tile? = null
        var worstExcess = 0.0
        var tiles = 0
        for (ty in 0 until h step TILE) {
            for (tx in 0 until w step TILE) {
                val tw = minOf(TILE, w - tx)
                val th = minOf(TILE, h - ty)
                var a = 0L
                var b = 0L
                var c = 0L
                for (y in ty until ty + th) {
                    for (x in tx until tx + tw) {
                        val i = y * w + x
                        a += distance(k[i], m[i])
                        b += distance(k[i], p[i])
                        c += distance(m[i], p[i])
                    }
                }
                val n = tw.toDouble() * th * 765.0
                val tile = Tile(tx, ty, tw, th, a / n, b / n, c / n)
                tiles++
                val odd = outlier(tile.kiteMupdf, tile.kitePdfium, tile.mupdfPdfium, TILE_MARGIN) ?: continue
                counts[odd] = counts.getValue(odd) + 1
                if (odd == Engine.KITE) {
                    val excess = minOf(tile.kiteMupdf, tile.kitePdfium) - RATIO * tile.mupdfPdfium
                    if (excess > worstExcess) {
                        worstExcess = excess
                        worst = tile
                    }
                }
            }
        }
        return Result(
            width = w,
            height = h,
            kiteMupdf = kiteMupdf,
            kitePdfium = kitePdfium,
            mupdfPdfium = mupdfPdfium,
            pageOutlier = outlier(kiteMupdf, kitePdfium, mupdfPdfium, PAGE_MARGIN),
            outlierTiles = counts,
            tiles = tiles,
            worstKiteTile = worst,
            map = map,
        )
    }

    /** Sum of the absolute differences of the three channels, 0 to 765. */
    private fun distance(a: Int, b: Int): Int =
        abs(((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF)) +
            abs(((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF)) +
            abs((a and 0xFF) - (b and 0xFF))

    /** White where the three agree, else the colour of the engine that alone differs, darker when it differs more. */
    private fun mapColour(km: Int, kp: Int, mp: Int): Int {
        val kite = minOf(km, kp) - mp
        val pdfium = minOf(kp, mp) - km
        val mupdf = minOf(km, mp) - kp
        val strongest = maxOf(kite, pdfium, mupdf)
        if (strongest <= 0) return 0xFFFFFF
        val fade = 255 - (strongest * 2).coerceAtMost(255)
        return when (strongest) {
            kite -> (0xFF shl 16) or (fade shl 8) or fade
            pdfium -> (fade shl 16) or (fade shl 8) or 0xFF
            else -> (fade shl 16) or (0xFF shl 8) or fade
        }
    }

    /** The pixels of [src] flattened onto white at [w] by [h], as packed RGB. */
    private fun rgb(src: BufferedImage, w: Int, h: Int): IntArray {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.color = Color.WHITE
            g.fillRect(0, 0, w, h)
            g.drawImage(src, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return out.getRGB(0, 0, w, h, null, 0, w)
    }
}
