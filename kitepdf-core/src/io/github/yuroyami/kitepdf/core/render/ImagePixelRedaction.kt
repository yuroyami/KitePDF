package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.kitepdf.core.KiteRawApi
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.filters.FilterChain
import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfBoolean
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.parser.PdfStream

/**
 * Blanks the pixels of an image that a redaction region meets, for redaction that keeps the
 * rest of the image (#926). An image paints into the unit square of its own space, row 0 at
 * the top (ISO 32000-1, 8.9.4), so the regions arrive in that square, and each sample whose
 * pixel area meets one, rounded outward, takes the darkest value of the image's colour space.
 */
@KiteRawApi
public object ImagePixelRedaction {

    /** Decoded samples with the regions blanked: [bitsPerComponent] bits a component, rows padded to whole bytes. */
    public class Samples(public val bytes: ByteArray, public val bitsPerComponent: Int)

    /**
     * The samples of the image [stream] with every pixel that meets one of [rects] (in the
     * image's unit square) set to the darkest value of its colour space, or null when this
     * cannot be done safely: a stencil mask, a JPEG 2000 or JBIG2 image, a JPEG of four
     * components, a colour space that does not resolve, or data that does not decode or is
     * short. A soft mask passes [softMask], and its samples go to 0, so no outline survives.
     */
    public fun blank(stream: PdfStream, refs: IndirectResolver, rects: List<KiteRectangle>, softMask: Boolean = false): Samples? = runCatching {
        val dict = stream.dict
        if ((dict["ImageMask"] as? PdfBoolean)?.value == true) return null
        val width = (dict["Width"] as? PdfInt)?.value?.toInt()?.takeIf { it in 1..MAX_SIDE } ?: return null
        val height = (dict["Height"] as? PdfInt)?.value?.toInt()?.takeIf { it in 1..MAX_SIDE } ?: return null
        val space = if (softMask) KiteColorSpace.DeviceGray else KiteColorSpace.resolve(dict["ColorSpace"] ?: return null, refs)
        if (space is KiteColorSpace.Unsupported) return null
        val components = space.componentCount
        val filters = when (val f = dict["Filter"]) {
            null -> emptyList()
            is PdfName -> listOf(f.value)
            is PdfArray -> f.map { (it as? PdfName)?.value ?: return null }
            else -> return null
        }
        val (samples, bpc) = when (filters.lastOrNull()) {
            "JPXDecode", "JBIG2Decode" -> return null
            "DCTDecode", "DCT" -> {
                if (components != 1 && components != 3) return null
                val bitmap = ImageKodec.decode(FilterChain.decodeToTerminal(stream).bytes)
                if (bitmap.width != width || bitmap.height != height) return null
                (if (components == 1) bitmap.toGrayBytes() else bitmap.toRgbBytes()) to 8
            }
            else -> {
                val bpc = (dict["BitsPerComponent"] as? PdfInt)?.value?.toInt() ?: return null
                if (bpc !in setOf(1, 2, 4, 8, 16)) return null
                FilterChain.decode(stream) to bpc
            }
        }
        val rowBytes = (width.toLong() * components * bpc + 7) / 8
        if (samples.size < rowBytes * height) return null
        val out = samples.copyOf((rowBytes * height).toInt())
        val dark = if (softMask) IntArray(1) else darkest(space, bpc, decodeOf(dict, components))
        for (r in rects) {
            val left = kotlin.math.floor(r.left * width).toInt().coerceIn(0, width)
            val right = kotlin.math.ceil(r.right * width).toInt().coerceIn(0, width)
            val top = kotlin.math.floor((1 - r.top) * height).toInt().coerceIn(0, height)
            val bottom = kotlin.math.ceil((1 - r.bottom) * height).toInt().coerceIn(0, height)
            for (row in top until bottom) for (col in left until right) for (c in 0 until components) {
                setSample(out, row * rowBytes * 8 + (col.toLong() * components + c) * bpc, bpc, dark[c])
            }
        }
        Samples(out, bpc)
    }.getOrNull()

    /** The `/Decode` pairs of the image, or null for the default. */
    private fun decodeOf(dict: PdfDictionary, components: Int): DoubleArray? {
        val arr = dict["Decode"] as? PdfArray ?: return null
        val values = arr.map { (it as? PdfReal)?.value ?: (it as? PdfInt)?.value?.toDouble() ?: return null }
        return if (values.size >= 2 * components) values.toDoubleArray() else null
    }

    /**
     * The sample values, one a component, of the darkest colour an image can paint in [space]:
     * the palette entry of least luminance for an Indexed space, and otherwise the darkest
     * corner of the component ranges, each sample mapped through [decode] (ISO 32000-1, 8.9.5.2).
     */
    private fun darkest(space: KiteColorSpace, bpc: Int, decode: DoubleArray?): IntArray {
        val max = (1 shl bpc) - 1
        fun luminance(c: RgbColor) = 0.299 * c.r + 0.587 * c.g + 0.114 * c.b
        if (space is KiteColorSpace.Indexed) {
            val lo = decode?.get(0) ?: 0.0
            val hi = decode?.get(1) ?: max.toDouble()
            val best = (0..minOf(max, 255)).minByOrNull { s -> luminance(space.colorAt(kotlin.math.round(lo + s * (hi - lo) / max).toInt())) } ?: 0
            return intArrayOf(best)
        }
        val n = space.componentCount
        if (n > 4) return IntArray(n)
        var best = 0
        var bestLuminance = Double.MAX_VALUE
        for (corner in 0 until (1 shl n)) {
            val values = DoubleArray(n) { i ->
                val lo = decode?.get(2 * i) ?: space.componentMin(i)
                val hi = decode?.get(2 * i + 1) ?: space.componentMax(i)
                if (corner and (1 shl i) != 0) hi else lo
            }
            val l = runCatching { luminance(space.toRgb(values)) }.getOrDefault(Double.MAX_VALUE)
            if (l < bestLuminance) {
                bestLuminance = l
                best = corner
            }
        }
        return IntArray(n) { i -> if (best and (1 shl i) != 0) max else 0 }
    }

    /** Writes [value] into the [bpc] bits that start at bit [bit] of [out], most significant bit first. */
    private fun setSample(out: ByteArray, bit: Long, bpc: Int, value: Int) {
        when (bpc) {
            8 -> out[(bit / 8).toInt()] = value.toByte()
            16 -> {
                val at = (bit / 8).toInt()
                out[at] = (value ushr 8).toByte()
                out[at + 1] = value.toByte()
            }
            else -> {
                val at = (bit / 8).toInt()
                val shift = 8 - bpc - (bit % 8).toInt()
                val mask = ((1 shl bpc) - 1) shl shift
                out[at] = ((out[at].toInt() and mask.inv()) or ((value shl shift) and mask)).toByte()
            }
        }
    }

    private const val MAX_SIDE = 1 shl 16
}
