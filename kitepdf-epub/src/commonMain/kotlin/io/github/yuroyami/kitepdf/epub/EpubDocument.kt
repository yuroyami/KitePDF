package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.font.FontFamily
import io.github.yuroyami.kitepdf.font.FontSpec
import io.github.yuroyami.kitepdf.font.TextGlyph
import io.github.yuroyami.kitepdf.render.BlendMode
import io.github.yuroyami.kitepdf.render.Matrix
import io.github.yuroyami.kitepdf.render.PdfCanvas
import io.github.yuroyami.kitepdf.render.RgbColor

/**
 * A parsed EPUB, reflowed onto fixed-size pages and rendered through the shared
 * [PdfCanvas] the PDF engine uses. This is the second document handler on
 * :kitepdf-core (the first is the PDF engine): it reuses the core font engine,
 * codecs and render backends, and adds only EPUB's own concern -- unzip the
 * OCF container, follow the spine, and flow the text.
 *
 * Scope (deliberately minimal first cut): extracts block-level text from the
 * spine's XHTML and lays it out in a single default serif face with greedy word
 * wrapping and fixed pagination. No CSS cascade, floats, tables, images or
 * embedded fonts yet -- those are the reflow engine's growth path, not the
 * plumbing, which is proven here.
 */
class EpubDocument private constructor(
    private val blocks: List<String>,
    val pageWidth: Double,
    val pageHeight: Double,
    val fontSize: Double,
    val margin: Double,
) {
    private val lineHeight get() = fontSize * 1.4
    private val textWidth get() = pageWidth - 2 * margin
    private val linesPerPage get() = ((pageHeight - 2 * margin) / lineHeight).toInt().coerceAtLeast(1)

    /** The reflowed pages, ready to render. */
    val pages: List<EpubPage> by lazy { paginate() }

    val pageCount: Int get() = pages.size

    private fun paginate(): List<EpubPage> {
        val allLines = ArrayList<String>()
        for (block in blocks) {
            if (block.isBlank()) continue
            allLines.addAll(wrap(block))
            allLines.add("")   // paragraph gap
        }
        if (allLines.isNotEmpty() && allLines.last().isEmpty()) allLines.removeAt(allLines.size - 1)
        if (allLines.isEmpty()) return listOf(EpubPage(emptyList(), this))
        return allLines.chunked(linesPerPage).map { EpubPage(it, this) }
    }

    /** Greedy word wrap of one paragraph to [textWidth], in points. */
    private fun wrap(paragraph: String): List<String> {
        val words = paragraph.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val lines = ArrayList<String>()
        var line = StringBuilder()
        var lineW = 0.0
        val spaceW = advanceEm(' ') * fontSize
        for (w in words) {
            val wordW = w.sumOf { advanceEm(it) } * fontSize
            val add = if (line.isEmpty()) wordW else spaceW + wordW
            if (lineW + add > textWidth && line.isNotEmpty()) {
                lines.add(line.toString())
                line = StringBuilder(w); lineW = wordW
            } else {
                if (line.isNotEmpty()) { line.append(' '); lineW += spaceW }
                line.append(w); lineW += wordW
            }
        }
        if (line.isNotEmpty()) lines.add(line.toString())
        return lines
    }

    internal fun renderLine(canvas: PdfCanvas, text: String, baselineY: Double, deviceCtm: Matrix) {
        if (text.isEmpty()) return
        val glyphs = ArrayList<TextGlyph>(text.length)
        for ((i, ch) in text.withIndex()) {
            glyphs.add(
                TextGlyph(
                    byteOffset = i, byteCount = 1, gid = -1, text = ch.toString(),
                    advanceWidth = advanceEm(ch) * 1000.0,   // 1/1000 em, the width convention
                    outline = null, isWordSpace = ch == ' ',
                ),
            )
        }
        // text-space origin at (margin, baselineY) in y-up page space, then to device.
        val textMatrix = Matrix.translation(margin, baselineY)
        canvas.drawGlyphs(
            glyphs, fontSize, unitsPerEm = 1000, hasOutlines = false, fontSpec = BODY_FONT,
            textToDevice = deviceCtm.concat(textMatrix), color = BLACK,
            alpha = 1.0, blendMode = BlendMode.Normal,
        )
    }

    companion object {
        private val BODY_FONT = FontSpec(FontFamily.Serif, bold = false, italic = false, name = "Times")
        private val BLACK = RgbColor(0.0, 0.0, 0.0)

        /**
         * Open EPUB [bytes]. Returns null if the archive is unreadable or has no
         * OPF / spine. Page geometry defaults to a typical reader page; override
         * for a different viewport.
         */
        fun open(
            bytes: ByteArray,
            pageWidth: Double = 400.0,
            pageHeight: Double = 640.0,
            fontSize: Double = 12.0,
            margin: Double = 36.0,
        ): EpubDocument? {
            val zip = ZipReader(bytes)
            val opfPath = containerOpfPath(zip) ?: return null
            val contentPaths = spineContentPaths(zip, opfPath)
            if (contentPaths.isEmpty()) return null
            val blocks = ArrayList<String>()
            for (path in contentPaths) {
                val xhtml = zip.readText(path) ?: continue
                blocks.addAll(extractBlocks(xhtml))
            }
            if (blocks.isEmpty()) return null
            return EpubDocument(blocks, pageWidth, pageHeight, fontSize, margin)
        }

        /** META-INF/container.xml -> the OPF package path. */
        private fun containerOpfPath(zip: ZipReader): String? {
            val xml = zip.readText("META-INF/container.xml") ?: return null
            for (t in MiniXml.tokenize(xml)) {
                if (t is XmlToken.Open && t.name == "rootfile") {
                    t.attrs["full-path"]?.let { return it }
                }
            }
            return null
        }

        /** OPF manifest + spine -> ordered content-document paths (zip-absolute). */
        private fun spineContentPaths(zip: ZipReader, opfPath: String): List<String> {
            val opf = zip.readText(opfPath) ?: return emptyList()
            val baseDir = opfPath.substringBeforeLast('/', "")
            val manifest = HashMap<String, String>()   // id -> href
            val spine = ArrayList<String>()             // idref order
            for (t in MiniXml.tokenize(opf)) {
                if (t !is XmlToken.Open) continue
                when (t.name) {
                    "item" -> {
                        val id = t.attrs["id"]; val href = t.attrs["href"]
                        if (id != null && href != null) manifest[id] = href
                    }
                    "itemref" -> t.attrs["idref"]?.let { spine.add(it) }
                }
            }
            return spine.mapNotNull { manifest[it] }.map { resolve(baseDir, it) }
        }

        /** Resolve an OPF-relative href against the package directory. */
        private fun resolve(baseDir: String, href: String): String {
            val clean = href.substringBefore('#')
            return if (baseDir.isEmpty()) clean else "$baseDir/$clean"
        }

        /** Block-level text from an XHTML document, one string per block. */
        internal fun extractBlocks(xhtml: String): List<String> {
            val blockTags = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "br", "tr", "title")
            val skip = setOf("script", "style", "head")
            val blocks = ArrayList<String>()
            val cur = StringBuilder()
            var skipDepth = 0
            fun flush() { if (cur.isNotBlank()) blocks.add(cur.toString().trim().replace(Regex("\\s+"), " ")); cur.clear() }
            for (t in MiniXml.tokenize(xhtml)) {
                when (t) {
                    is XmlToken.Open -> {
                        if (t.name in skip) skipDepth++
                        if (t.name in blockTags) flush()
                    }
                    is XmlToken.Close -> {
                        if (t.name in skip && skipDepth > 0) skipDepth--
                        if (t.name in blockTags) flush()
                    }
                    is XmlToken.Text -> if (skipDepth == 0) cur.append(t.text)
                }
            }
            flush()
            return blocks
        }

        /** Rough per-character advance in em (1.0 = one em), for greedy wrapping. */
        internal fun advanceEm(ch: Char): Double = when (ch) {
            'i', 'j', 'l', '.', ',', '\'', '!', ':', ';', '|', ' ' -> 0.28
            'm', 'w', 'M', 'W', '@' -> 0.85
            in 'A'..'Z' -> 0.68
            else -> 0.5
        }
    }
}

/** One reflowed EPUB page: a stack of text lines rendered top-to-bottom. */
class EpubPage internal constructor(
    val lines: List<String>,
    private val doc: EpubDocument,
) {
    val width: Double get() = doc.pageWidth
    val height: Double get() = doc.pageHeight

    /** Draw this page into [canvas]. Mirrors PdfPage.renderTo: the backend maps
     *  y-up user space to the device via [deviceCtm]. */
    fun renderTo(canvas: PdfCanvas, deviceCtm: Matrix = Matrix.IDENTITY) {
        canvas.beginPage(width, height, deviceCtm)
        val lineHeight = doc.fontSize * 1.4
        val topBaseline = height - doc.margin - doc.fontSize
        for ((k, line) in lines.withIndex()) {
            val baselineY = topBaseline - k * lineHeight
            doc.renderLine(canvas, line, baselineY, deviceCtm)
        }
        canvas.endPage()
    }
}
