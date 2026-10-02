package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.core.KiteLock
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.withLock
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The coverage of glyphs drawn from their own outlines, kept across rasters, as MuPDF keeps
 * its glyph cache (#382). A raster filled one path for every glyph; now it fills one for each
 * glyph, size and position class, and a page drawn again, after a zoom settles or on a page
 * turn back, fills none.
 *
 * A mask is kept per outline, per linear part of the glyph's device matrix and per position
 * class of its origin. As in MuPDF's `fz_subpixel_adjust`, the origin rounds to a quarter of a
 * pixel below 24 pixels to the em, to a half below 48, and to a whole pixel above, so a glyph
 * moves by an eighth of a pixel at most. A glyph wider or taller than [MAX_MASK_SIDE] pixels
 * is not kept, and draws as a path.
 *
 * The cache holds at most [budgetBytes] of coverage, a byte a pixel, and drops the mask used
 * least recently first. A key holds its outline, a small vector a font already holds, and no
 * document. Map operations are locked, so two rasters can share it; a mask is built outside
 * the lock.
 */
internal class GlyphMaskCache(private val budgetBytes: Long = DEFAULT_BUDGET_BYTES) {

    /**
     * A glyph's coverage, a byte a pixel, row by row, [width] by [height], whose top left lies
     * [left] and [top] pixels from the whole pixel the glyph is drawn at.
     */
    class Mask(val coverage: ByteArray, val width: Int, val height: Int, val left: Int, val top: Int)

    /** Where a glyph lands: the whole pixel its origin rounds to, and its mask. */
    class Placement(val x: Int, val y: Int, val mask: Mask)

    private class Key(
        val outline: KitePath,
        val a: Double,
        val b: Double,
        val c: Double,
        val d: Double,
        val qx: Int,
        val qy: Int,
    ) {
        // The outline by identity: a font hands out one object per glyph, and comparing two
        // outlines segment by segment costs about as much as drawing one.
        override fun equals(other: Any?): Boolean = other is Key && other.outline === outline &&
            other.a == a && other.b == b && other.c == c && other.d == d && other.qx == qx && other.qy == qy

        override fun hashCode(): Int {
            var h = outline.segments.size * 31 + (outline.segments.firstOrNull()?.hashCode() ?: 0)
            h = h * 31 + a.hashCode()
            h = h * 31 + b.hashCode()
            h = h * 31 + c.hashCode()
            h = h * 31 + d.hashCode()
            return (h * 31 + qx) * 31 + qy
        }
    }

    /** In order from the mask used least recently to the one used most recently. */
    private val entries = LinkedHashMap<Key, Mask>()
    private val lock = KiteLock()
    private var bytes = 0L

    /** How many masks this cache built. For tests. */
    internal var built: Int = 0
        private set

    /** The bytes of coverage that the cache holds. */
    val heldBytes: Long get() = lock.withLock { bytes }

    /**
     * Where [outline] lands under [glyphMatrix], from glyph space to device pixels, for a font of
     * [unitsPerEm] units, or null for a glyph too large or too odd for a mask, which draws as a
     * path instead.
     */
    fun place(outline: KitePath, glyphMatrix: KiteMatrix, unitsPerEm: Int): Placement? {
        val m = glyphMatrix
        val pixelsPerEm = sqrt(abs(m.a * m.d - m.b * m.c)) * unitsPerEm
        if (!pixelsPerEm.isFinite() || pixelsPerEm <= 0.0) return null
        val steps = if (pixelsPerEm < 24.0) 4 else if (pixelsPerEm < 48.0) 2 else 1
        // The origin rounds to the nearest of [steps] positions a pixel.
        val nx = floor(m.e * steps + 0.5)
        val ny = floor(m.f * steps + 0.5)
        if (!(abs(nx) < LIMIT && abs(ny) < LIMIT)) return null
        val x = floorDiv(nx.toInt(), steps)
        val y = floorDiv(ny.toInt(), steps)
        val qx = nx.toInt() - x * steps
        val qy = ny.toInt() - y * steps
        // The position classes need not carry the step: it follows from the matrix, which is in the key.
        val key = Key(outline, m.a, m.b, m.c, m.d, qx, qy)
        lock.withLock {
            entries.remove(key)?.let { mask ->
                // Put it back at the end, as the mask used most recently.
                entries[key] = mask
                return Placement(x, y, mask)
            }
        }
        val mask = build(outline, KiteMatrix(m.a, m.b, m.c, m.d, qx.toDouble() / steps, qy.toDouble() / steps)) ?: return null
        val size = mask.coverage.size.toLong()
        val kept = lock.withLock {
            built++
            entries.remove(key)?.let { other ->
                entries[key] = other
                return@withLock other
            }
            if (budgetBytes <= 0L || size > budgetBytes) return@withLock mask
            val oldest = entries.values.iterator()
            while (bytes > budgetBytes - size && oldest.hasNext()) {
                bytes -= oldest.next().coverage.size
                oldest.remove()
            }
            entries[key] = mask
            bytes += size
            mask
        }
        return Placement(x, y, kept)
    }

    /** The coverage of [outline] under [matrix], whose translation is the origin's offset inside its pixel. */
    private fun build(outline: KitePath, matrix: KiteMatrix): Mask? {
        // Device pixels run y down, so the box's bottom is its least y. A pixel of margin each
        // side holds the antialiased edge.
        val bounds = outline.bounds(matrix) ?: return null
        val left = floor(bounds.left).toInt() - 1
        val top = floor(bounds.bottom).toInt() - 1
        val width = ceil(bounds.right).toInt() + 1 - left
        val height = ceil(bounds.top).toInt() + 1 - top
        if (width <= 0 || height <= 0 || width > MAX_MASK_SIDE || height > MAX_MASK_SIDE) return null
        val drawn = ImageBitmap(width, height)
        val path = toComposePath(outline, KiteMatrix.translation(-left.toDouble(), -top.toDouble()).concat(matrix))
            .apply { fillType = PathFillType.NonZero }
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(drawn), Size(width.toFloat(), height.toFloat())) {
            drawPath(path, Color.Black)
        }
        val argb = IntArray(width * height)
        drawn.readPixels(argb)
        return Mask(ByteArray(argb.size) { (argb[it] ushr 24).toByte() }, width, height, left, top)
    }

    private fun floorDiv(n: Int, d: Int): Int {
        val q = n / d
        return if (n % d != 0 && (n < 0) != (d < 0)) q - 1 else q
    }

    companion object {
        /** The largest side of a mask, as MuPDF's `MAX_GLYPH_SIZE`: a larger glyph draws as a path. */
        const val MAX_MASK_SIDE = 256

        /** The default budget: 4 MB of coverage, some ten thousand masks of body text. */
        const val DEFAULT_BUDGET_BYTES: Long = 4L * 1024 * 1024

        /** Origins past this many pixels, in steps, are not placed: a page is never that large. */
        private const val LIMIT = (1 shl 28).toDouble()
    }
}
