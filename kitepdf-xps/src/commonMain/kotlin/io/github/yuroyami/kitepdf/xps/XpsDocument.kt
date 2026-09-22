package io.github.yuroyami.kitepdf.xps

import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.core.KiteMetadata
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.KiteStructuredText
import io.github.yuroyami.kitepdf.core.kiteWarn
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RgbColor

/**
 * An XPS or OpenXPS package opened as fixed pages in document-sequence order.
 *
 * The package, sequence, documents and pages follow ECMA-388 §§9, 10. Both
 * Microsoft's 2005 XPS namespace and OpenXPS use this handler. XPS coordinates
 * are 1/96 inch; [XpsPage] exposes points (1/72 inch), as [KitePage] requires.
 *
 * Paths, glyph runs, embedded TrueType/OpenType fonts, image brushes and
 * resource dictionaries render through the same canvas as the other formats.
 * Unsupported or damaged drawing elements are skipped independently, and a
 * missing page stays in its original position as a placeholder. See this
 * module's documentation for the rendering limits; this is not a validator.
 *
 * ```kotlin
 * val document = XpsDocument.open(bytes)
 * document.pages.first().renderTo(canvas)
 * ```
 */
public class XpsDocument private constructor(
    override val pages: List<XpsPage>,
    override val metadata: KiteMetadata,
) : KiteDocument {
    override val pageCount: Int get() = pages.size

    public companion object {
        /**
         * Reads the OPC package and its page references (ECMA-388 §§9.1.2-4).
         * Page markup and fonts are decoded lazily when needed.
         *
         * @throws KiteFormatException when no fixed document sequence or page
         * references can be recovered from the package.
         */
        public fun open(bytes: ByteArray): XpsDocument {
            val packageData = XpsPackage(bytes)
            val sequencePart = packageData.sequencePart()
                ?: throw KiteFormatException("xps: no fixed document sequence")
            val sequence = packageData.xml(sequencePart)
                ?: throw KiteFormatException("xps: unreadable fixed document sequence")
            val pages = ArrayList<XpsPage>()
            for (reference in sequence.elements().filter { it.tag == "documentreference" }) {
                val docPart = resolvePart(sequencePart, reference.attrs["source"].orEmpty()) ?: continue
                val document = packageData.xml(docPart)
                if (document?.tag != "fixeddocument") {
                    kiteWarn { "xps: unreadable fixed document '$docPart'" }
                    continue
                }
                for (page in document.elements().filter { it.tag == "pagecontent" }) {
                    val part = resolvePart(docPart, page.attrs["source"].orEmpty()) ?: continue
                    pages.add(XpsPage(packageData, part, page.number("width", 816.0), page.number("height", 1056.0)))
                }
            }
            if (pages.isEmpty()) throw KiteFormatException("xps: no page references")
            return XpsDocument(pages, KiteMetadata(language = sequence.attrs["lang"]))
        }

        /** [open], but null instead of an exception for an unreadable package. */
        public fun openOrNull(bytes: ByteArray): XpsDocument? = try {
            open(bytes)
        } catch (_: Exception) {
            null
        }

        /**
         * Recognizes an OPC fixed document sequence without loading any pages.
         * A ZIP signature or a filename alone is insufficient (ECMA-388 §9).
         */
        public fun isXps(bytes: ByteArray): Boolean = runCatching {
            XpsPackage(bytes).sequencePart() != null
        }.getOrDefault(false)
    }
}

/** A fixed page, with display coordinates in points (ECMA-388 §10.3). */
public class XpsPage internal constructor(
    private val packageData: XpsPackage,
    private val part: String,
    private val fallbackWidth: Double,
    private val fallbackHeight: Double,
) : KitePage {
    private val root by lazy { packageData.xml(part)?.takeIf { it.tag == "fixedpage" } }
    private val width: Double get() = root?.number("width", fallbackWidth)?.positive() ?: fallbackWidth.positive()
    private val height: Double get() = root?.number("height", fallbackHeight)?.positive() ?: fallbackHeight.positive()
    override val displayWidth: Double get() = width * POINTS_PER_UNIT
    override val displayHeight: Double get() = height * POINTS_PER_UNIT
    override fun displayToDeviceBase(): KiteMatrix = KiteMatrix.IDENTITY

    override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) {
        canvas.beginPage(displayWidth, displayHeight, deviceCtm)
        try {
            val page = root
            if (page == null) {
                val sheet = KitePath.Builder().apply { rectangle(0.0, 0.0, displayWidth, displayHeight) }.build()
                canvas.fillPath(sheet, deviceCtm, RgbColor(0.9, 0.9, 0.9), false)
            } else {
                val ctm = deviceCtm.concat(KiteMatrix.scaling(POINTS_PER_UNIT, POINTS_PER_UNIT))
                val clip = KitePath.Builder().apply { rectangle(0.0, 0.0, width, height) }.build()
                canvas.pushClip(clip, ctm, false)
                try { XpsRenderer(packageData, part).render(page, canvas, ctm) } finally { canvas.popClip() }
            }
        } finally { canvas.endPage() }
    }

    override fun textContent(): KiteStructuredText = root?.let {
        XpsRenderer(packageData, part).text(it, KiteMatrix.scaling(POINTS_PER_UNIT, POINTS_PER_UNIT))
    } ?: KiteStructuredText(emptyList())

    private fun Double.positive(): Double = if (isFinite() && this > 0) this else 1.0

    private companion object { const val POINTS_PER_UNIT = 72.0 / 96.0 }
}

internal fun io.github.yuroyami.kitepdf.core.xml.KiteXmlNode.Element.number(name: String, fallback: Double): Double =
    attrs[name]?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: fallback
