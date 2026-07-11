package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.Rectangle

import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.KiteStructuredText

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.PdfFormatException
import io.github.yuroyami.kitepdf.core.filters.FilterChain
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.render.Matrix
import io.github.yuroyami.kitepdf.render.PageRenderer
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.text.TextExtractor

/**
 * A single PDF page. Obtained from [PdfDocument.pages]; not constructed directly.
 *
 * ```
 * val page = doc.pages[0]
 * println("${page.width} x ${page.height} pt, rotation ${page.rotation}°")
 * println(page.extractText())
 * for (link in page.annotations) { /* … */ }
 *
 * // Paint into any backend implementing KiteCanvas (see :kitepdf-compose etc.):
 * page.renderTo(canvas, deviceCtm = Matrix.IDENTITY)
 * ```
 */
public class PdfPage internal constructor(
    private val document: PdfDocument,
    private val node: PdfDictionary,
    private val inherited: PageInheritable,
    /** Zero-based position in the document's flat page list. */
    public val index: Int,
    /**
     * The page's own indirect reference (its entry in the parent's `/Kids`).
     * `null` only for the unusual case of a page dict inlined directly in
     * `/Kids`. The writer needs this to target the page object for edits.
     */
    public val reference: PdfReference? = null,
) : KitePage {

    /** The raw page dictionary — used by the writer to rebuild the page on edit. */
    internal val dictionary: PdfDictionary get() = node

    /**
     * Display label from `/PageLabels` (e.g. "iii", "A-1", "12"). Falls back
     * to the one-based index as a string when the document doesn't define
     * its own labels.
     */
    public val label: String
        get() = document.pageLabels.labelOf(index) ?: (index + 1).toString()

    /**
     * Tolerant box reader: resolves the named array through the document
     * resolver (so an INDIRECT /MediaBox etc. is honoured), then resolves each
     * of the four coordinate entries through the resolver to a number. A
     * missing or non-numeric coordinate defaults to 0.0 rather than throwing —
     * a single garbage entry must not sink a whole page (lenient-salvage).
     */
    private fun readBox(key: String): Rectangle? {
        val arr = node.getArray(key, document) ?: return null
        if (arr.size < 4) return null
        fun coord(i: Int): Double {
            val raw = arr[i]
            val v = if (raw is PdfReference) document.resolve(raw) else raw
            return when (v) {
                is PdfReal -> v.value
                is PdfInt -> v.value.toDouble()
                else -> 0.0
            }
        }
        return Rectangle(coord(0), coord(1), coord(2), coord(3))
    }

    /** Page box in PDF user-space units (1/72 inch). [left, bottom, right, top]. */
    public val mediaBox: Rectangle by lazy {
        readBox("MediaBox") ?: inherited.mediaBox?.let(Rectangle::fromPdfArray)
            ?: throw PdfFormatException("Page has no /MediaBox")
    }

    public val cropBox: Rectangle by lazy {
        readBox("CropBox") ?: inherited.cropBox?.let(Rectangle::fromPdfArray) ?: mediaBox
    }

    /**
     * Region the page is to be clipped to for production-style output
     * (printing with bleed). Defaults to [cropBox] when `/BleedBox` is
     * absent. ISO 32000-1 §14.11.2.
     */
    public val bleedBox: Rectangle by lazy {
        readBox("BleedBox") ?: cropBox
    }

    /** Intended dimensions of the finished page after trimming. Defaults to [cropBox]. */
    public val trimBox: Rectangle by lazy {
        readBox("TrimBox") ?: cropBox
    }

    /** Extent of meaningful content (excluding margins, crop marks). Defaults to [cropBox]. */
    public val artBox: Rectangle by lazy {
        readBox("ArtBox") ?: cropBox
    }

    public val rotation: Int get() = (node.getInt("Rotate") ?: inherited.rotate ?: 0).toInt()

    /**
     * [rotation] normalised into `{0, 90, 180, 270}`: reduced modulo 360 into
     * `[0, 360)` then snapped to the nearest right angle (defensive — in
     * practice the value is already a multiple of 90).
     */
    public val rotationNormalized: Int
        get() {
            val r = ((rotation % 360) + 360) % 360
            return (((r + 45) / 90) * 90) % 360
        }

    public val resources: PdfDictionary? get() = node.getDict("Resources", document) ?: inherited.resources

    /**
     * Multiplier for user-space units on this page (PDF 1.6+, §14.8.1). Default
     * 1.0 — each unit is 1/72 inch. A `/UserUnit` of 2.0 means each unit is
     * 2/72 inch, doubling the effective page size for the same coordinate
     * stream. Useful for very large pages (architectural drawings, posters).
     */
    public val userUnit: Double get() = node.getReal("UserUnit") ?: 1.0

    public val width: Double get() = mediaBox.right - mediaBox.left
    public val height: Double get() = mediaBox.top - mediaBox.bottom

    /**
     * The region to display: [cropBox] clamped to [mediaBox] bounds (their
     * intersection). Falls back to [mediaBox] when the intersection is empty or
     * degenerate (zero/negative width or height).
     */
    public val displayBox: Rectangle by lazy {
        val m = mediaBox
        val c = cropBox
        val left = maxOf(m.left, c.left)
        val bottom = maxOf(m.bottom, c.bottom)
        val right = minOf(m.right, c.right)
        val top = minOf(m.top, c.top)
        if (right > left && top > bottom) Rectangle(left, bottom, right, top) else m
    }

    /** Width of [displayBox] after applying [rotationNormalized] (dimensions swap at 90/270). */
    public val rotatedWidth: Double
        get() = if (rotationNormalized == 90 || rotationNormalized == 270) displayBox.height else displayBox.width

    /** Height of [displayBox] after applying [rotationNormalized] (dimensions swap at 90/270). */
    public val rotatedHeight: Double
        get() = if (rotationNormalized == 90 || rotationNormalized == 270) displayBox.width else displayBox.height

    // KitePage: display geometry is the rotated box, and the base transform is
    // the already-verified pageToDeviceBase().
    override val displayWidth: Double get() = rotatedWidth
    override val displayHeight: Double get() = rotatedHeight
    override fun displayToDeviceBase(): Matrix = pageToDeviceBase()

    /**
     * The unscaled user-space -> device base transform, with the device origin
     * at the top-left and y growing downward. The resulting device box is
     * `[0, rotatedWidth] x [0, rotatedHeight]`. Compose additional scaling on
     * top of this to fit a target surface.
     *
     * Verified against pdf.js viewport math for [Matrix]`(a, b, c, d, e, f)`.
     */
    public fun pageToDeviceBase(): Matrix {
        val b = displayBox
        val x0 = b.left
        val y0 = b.bottom
        val x1 = b.right
        val y1 = b.top
        return when (rotationNormalized) {
            90 -> Matrix(0.0, 1.0, 1.0, 0.0, -y0, -x0)
            180 -> Matrix(-1.0, 0.0, 0.0, 1.0, x1, -y0)
            270 -> Matrix(0.0, -1.0, -1.0, 0.0, y1, x1)
            else -> Matrix(1.0, 0.0, 0.0, -1.0, -x0, y1)
        }
    }

    /**
     * Decoded content-stream bytes (FlateDecode etc. applied). May be empty.
     * If /Contents is an array, the chunks are concatenated with a separating
     * newline (per ISO 32000-1 §7.8.2: "The effect is as if all of the streams
     * in the array were concatenated").
     */
    public val contentBytes: ByteArray by lazy {
        when (val c = node["Contents"]) {
            null -> ByteArray(0)
            is PdfReference -> streamBytesOf(c) ?: ByteArray(0)
            // Lenient salvage: an undecodable stream (bad flate data, or one
            // tripping the decompression-bomb cap) yields a blank page, not a
            // crash — matching MuPDF's broken-content behaviour.
            is PdfStream -> runCatching { FilterChain.decode(c) }.getOrNull() ?: ByteArray(0)
            is PdfArray -> {
                val buf = ByteArrayBuilder(4096)
                var first = true
                for (part in c) {
                    // Lenient salvage: skip members that aren't references or
                    // don't resolve to a stream; one bad chunk must not kill
                    // the whole page.
                    val ref = part as? PdfReference ?: continue
                    val bytes = streamBytesOf(ref) ?: continue
                    if (!first) buf.append('\n'.code.toByte())
                    buf.append(bytes)
                    first = false
                }
                buf.toByteArray()
            }
            else -> throw PdfFormatException("/Contents must be stream, ref, or array")
        }
    }

    /**
     * Decoded stream bytes for a content reference, or null if it doesn't
     * resolve to a stream or its data cannot be decoded (including streams
     * rejected by the decompression-bomb cap).
     */
    private fun streamBytesOf(ref: PdfReference): ByteArray? {
        val stream = document.resolve(ref) as? PdfStream ?: return null
        return runCatching { FilterChain.decode(stream) }.getOrNull()
    }

    /** Extract page text using the naive Tj/TJ/' / " operator scan. */
    public fun extractText(): String = TextExtractor.extract(this)

    /**
     * Structured text — spans clustered into lines, lines clustered into
     * blocks. Use this when you need geometry (selection rectangles,
     * search highlights) alongside the text. For a plain string, prefer
     * [extractText].
     */
    public val structuredText: io.github.yuroyami.kitepdf.text.PdfStructuredText by lazy {
        io.github.yuroyami.kitepdf.text.StructuredTextExtractor.extract(this)
    }

    /**
     * Format-neutral structured text in DISPLAY space (y-down, rotation
     * folded in): [structuredText] pushed through [pageToDeviceBase] by the
     * T-81 adapter. Built once and memoized; powers the shared search /
     * selection / copy path a viewer uses for every format.
     */
    private val kiteTextContent: KiteStructuredText by lazy {
        io.github.yuroyami.kitepdf.text.KiteTextAdapter.toKite(structuredText, pageToDeviceBase())
    }

    override fun textContent(): KiteStructuredText = kiteTextContent

    /** Internal accessor used by the structured-text extractor to reach the document resolver. */
    internal val internalDocument: PdfDocument get() = document

    /**
     * Annotations attached to this page (links, highlights, etc.). Parsed from
     * the page's `/Annots` array; empty when the page has none.
     */
    public val annotations: List<PdfAnnotation> by lazy {
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
     * Render this page into a [KiteCanvas] (typically the Compose binding's
     * `ComposeCanvas`). [deviceCtm] is applied on top of user-space — pass an
     * identity matrix for a 1pt = 1pt rendering, or a scaled / Y-flipped
     * matrix to fit a UI surface.
     */
    override fun renderTo(canvas: KiteCanvas, deviceCtm: Matrix) {
        PageRenderer(canvas, document).render(this, deviceCtm)
    }
}
