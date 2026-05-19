package com.yuroyami.kitepdf

import com.yuroyami.kitepdf.core.ByteArrayBuilder
import com.yuroyami.kitepdf.core.PdfFormatException
import com.yuroyami.kitepdf.filters.FilterChain
import com.yuroyami.kitepdf.parser.PdfArray
import com.yuroyami.kitepdf.parser.PdfDictionary
import com.yuroyami.kitepdf.parser.PdfInt
import com.yuroyami.kitepdf.parser.PdfReal
import com.yuroyami.kitepdf.parser.PdfReference
import com.yuroyami.kitepdf.parser.PdfStream
import com.yuroyami.kitepdf.render.Matrix
import com.yuroyami.kitepdf.render.PageRenderer
import com.yuroyami.kitepdf.render.PdfCanvas
import com.yuroyami.kitepdf.text.TextExtractor

/**
 * A single PDF page. Built by [PdfDocument]; not meant to be constructed directly.
 */
class PdfPage internal constructor(
    private val document: PdfDocument,
    private val node: PdfDictionary,
    private val inherited: PageInheritable,
) {

    /** Page box in PDF user-space units (1/72 inch). [left, bottom, right, top]. */
    val mediaBox: Rectangle by lazy {
        val arr = node.getArray("MediaBox") ?: inherited.mediaBox
            ?: throw PdfFormatException("Page has no /MediaBox")
        Rectangle.fromPdfArray(arr)
    }

    val cropBox: Rectangle by lazy {
        val arr = node.getArray("CropBox") ?: inherited.cropBox
        arr?.let(Rectangle::fromPdfArray) ?: mediaBox
    }

    val rotation: Int get() = (node.getInt("Rotate") ?: inherited.rotate ?: 0).toInt()

    val resources: PdfDictionary? get() = node.getDict("Resources", document) ?: inherited.resources

    val width: Double get() = mediaBox.right - mediaBox.left
    val height: Double get() = mediaBox.top - mediaBox.bottom

    /**
     * Decoded content-stream bytes (FlateDecode etc. applied). May be empty.
     * If /Contents is an array, the chunks are concatenated with a separating
     * newline (per ISO 32000-1 §7.8.2: "The effect is as if all of the streams
     * in the array were concatenated").
     */
    val contentBytes: ByteArray by lazy {
        when (val c = node["Contents"]) {
            null -> ByteArray(0)
            is PdfReference -> streamBytesOf(c)
            is PdfStream -> FilterChain.decode(c)
            is PdfArray -> {
                val buf = ByteArrayBuilder(4096)
                for ((i, part) in c.withIndex()) {
                    if (i > 0) buf.append('\n'.code.toByte())
                    val ref = part as? PdfReference
                        ?: throw PdfFormatException("/Contents array must contain references")
                    buf.append(streamBytesOf(ref))
                }
                buf.toByteArray()
            }
            else -> throw PdfFormatException("/Contents must be stream, ref, or array")
        }
    }

    private fun streamBytesOf(ref: PdfReference): ByteArray {
        val stream = document.resolve(ref) as? PdfStream
            ?: throw PdfFormatException("/Contents ref ${ref.objectNumber} not a stream")
        return FilterChain.decode(stream)
    }

    /** Extract page text using the naive Tj/TJ/' / " operator scan. */
    fun extractText(): String = TextExtractor.extract(this)

    /**
     * Annotations attached to this page (links, highlights, etc.). Parsed from
     * the page's `/Annots` array; empty when the page has none.
     */
    val annotations: List<PdfAnnotation> by lazy {
        val arr = node.getArray("Annots", document) ?: return@lazy emptyList()
        arr.mapNotNull { item ->
            val dict = when (item) {
                is PdfDictionary -> item
                is PdfReference -> document.resolve(item) as? PdfDictionary
                else -> null
            } ?: return@mapNotNull null
            PdfAnnotation.parse(dict, document)
        }
    }

    /**
     * Render this page into a [PdfCanvas] (typically the Compose binding's
     * `ComposeCanvas`). [deviceCtm] is applied on top of user-space — pass an
     * identity matrix for a 1pt = 1pt rendering, or a scaled / Y-flipped
     * matrix to fit a UI surface.
     */
    fun renderTo(canvas: PdfCanvas, deviceCtm: Matrix = Matrix.IDENTITY) {
        PageRenderer(canvas, document).render(this, deviceCtm)
    }
}

/** PDF rectangle: [left, bottom, right, top] in user-space units. */
data class Rectangle(val left: Double, val bottom: Double, val right: Double, val top: Double) {
    val width get() = right - left
    val height get() = top - bottom

    companion object {
        fun fromPdfArray(arr: PdfArray): Rectangle {
            require(arr.size >= 4) { "MediaBox needs 4 numbers, got ${arr.size}" }
            fun n(i: Int): Double = when (val v = arr[i]) {
                is PdfReal -> v.value
                is PdfInt -> v.value.toDouble()
                else -> error("MediaBox entry $i is not a number: $v")
            }
            return Rectangle(n(0), n(1), n(2), n(3))
        }
    }
}
