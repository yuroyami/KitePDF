package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.css.ComputedStyle
import io.github.yuroyami.kitepdf.epub.css.CssParser
import io.github.yuroyami.kitepdf.epub.css.FontFaceRule
import io.github.yuroyami.kitepdf.epub.css.Origin
import io.github.yuroyami.kitepdf.epub.css.StyleResolver
import io.github.yuroyami.kitepdf.render.BlendMode
import io.github.yuroyami.kitepdf.render.ImageXObject
import io.github.yuroyami.kitepdf.render.Matrix
import io.github.yuroyami.kitepdf.render.PdfCanvas
import io.github.yuroyami.kitepdf.render.PdfPath
import io.github.yuroyami.kitepdf.render.RgbColor

/**
 * A parsed EPUB, reflowed onto fixed-size pages and rendered through the shared
 * [PdfCanvas] the PDF engine uses. The second document handler on :kitepdf-core.
 *
 * Pipeline: [ZipReader] unzips the OCF container; [HtmlParser] builds a DOM per
 * spine document; the CSS cascade ([StyleResolver], via [BoxBuilder]) turns each
 * into a [LayoutBox] tree; [BoxLayout] resolves the box model (margins, borders,
 * padding, width, inline line breaking with justification) into document-space
 * geometry; [Paginator] slices it into pages; and [EpubPage] paints backgrounds,
 * borders, text and images. See EPUB_ROAD_TO_PERFECTION.md.
 */
class EpubDocument private constructor(
    private val root: BlockBox,
    internal val zip: ZipReader,
    private val fonts: FontRegistry,
    val pageWidth: Double,
    val pageHeight: Double,
    val fontSize: Double,
    val margin: Double,
) {
    private val contentWidth = pageWidth - 2 * margin
    private val pageContentHeight = pageHeight - 2 * margin

    private val pageRenders: List<PageRender> by lazy {
        BoxLayout(::loadImage, pageContentHeight, fonts).layout(root, contentWidth)
        Paginator.paginate(root, pageContentHeight)
    }

    /** The reflowed pages, ready to render. */
    val pages: List<EpubPage> by lazy { pageRenders.map { EpubPage(it, this) } }

    val pageCount: Int get() = pages.size

    private fun loadImage(zipPath: String): ImageXObject? =
        zip.read(zipPath)?.let { ImageXObject.fromEncodedImage(it) }

    companion object {
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

            val contentWidth = pageWidth - 2 * margin
            val docRoots = ArrayList<LayoutBox>()
            val faceRules = ArrayList<Pair<FontFaceRule, String>>() // rule + the doc dir its src resolves against
            for (path in contentPaths) {
                val xhtml = zip.readText(path) ?: continue
                val docDir = path.substringBeforeLast('/', "")
                val tree = HtmlParser.parse(xhtml)
                val parsed = CssParser.parseAll(collectAuthorCss(zip, tree, docDir), Origin.AUTHOR)
                for (face in parsed.fontFaces) faceRules.add(face to docDir)
                val resolver = StyleResolver(parsed.rules, fontSize, contentWidth)
                docRoots.add(BoxBuilder(resolver) { href -> resolvePath(docDir, href) }.build(tree))
            }
            if (docRoots.isEmpty()) return null

            val fonts = buildFontRegistry(zip, opfPath, faceRules)
            val superRoot = BlockBox(ComputedStyle.initial(fontSize), docRoots)
            return EpubDocument(superRoot, zip, fonts, pageWidth, pageHeight, fontSize, margin)
        }

        /** Load every `@font-face`'s TrueType file from the zip (deobfuscating mangled ones). */
        private fun buildFontRegistry(zip: ZipReader, opfPath: String, faceRules: List<Pair<FontFaceRule, String>>): FontRegistry {
            if (faceRules.isEmpty()) return FontRegistry.EMPTY
            val obf = parseEncryption(zip)
            val uid = opfUniqueId(zip, opfPath) ?: ""
            val faces = ArrayList<EmbeddedFace>()
            for ((rule, docDir) in faceRules) {
                val url = rule.srcUrls.firstOrNull { it.endsWith(".ttf", true) || it.endsWith(".otf", true) } ?: rule.srcUrls.firstOrNull() ?: continue
                val zipPath = resolvePath(docDir, url)
                val raw = zip.read(zipPath) ?: continue
                val bytes = obf[zipPath]?.let { Deobfuscate.deobfuscate(raw, it, uid) } ?: raw
                FontRegistry.face(rule.family, rule.bold, rule.italic, bytes)?.let { faces.add(it) }
            }
            return FontRegistry(faces)
        }

        /** META-INF/encryption.xml → obfuscated zip-path → algorithm URI. */
        private fun parseEncryption(zip: ZipReader): Map<String, String> {
            val xml = zip.readText("META-INF/encryption.xml") ?: return emptyMap()
            val map = HashMap<String, String>()
            var algo: String? = null
            for (t in MiniXml.tokenize(xml)) if (t is XmlToken.Open) when (t.name) {
                "encrypteddata" -> algo = null
                "encryptionmethod" -> algo = t.attrs["algorithm"]
                "cipherreference" -> { val uri = t.attrs["uri"]; val a = algo; if (uri != null && a != null) map[resolvePath("", uri)] = a }
            }
            return map
        }

        /** The OPF package's unique identifier value (the font-obfuscation key source). */
        private fun opfUniqueId(zip: ZipReader, opfPath: String): String? {
            val opf = zip.readText(opfPath) ?: return null
            val tokens = MiniXml.tokenize(opf)
            val uidRef = tokens.firstNotNullOfOrNull { (it as? XmlToken.Open)?.takeIf { o -> o.name == "package" }?.attrs?.get("unique-identifier") }
            var capture = false
            for (t in tokens) when {
                t is XmlToken.Open && t.name == "identifier" && (uidRef == null || t.attrs["id"] == uidRef) -> capture = true
                capture && t is XmlToken.Text -> return t.text.trim()
                t is XmlToken.Close && t.name == "identifier" -> capture = false
                else -> {}
            }
            return null
        }

        /** META-INF/container.xml -> the OPF package path. */
        private fun containerOpfPath(zip: ZipReader): String? {
            val xml = zip.readText("META-INF/container.xml") ?: return null
            for (t in MiniXml.tokenize(xml)) {
                if (t is XmlToken.Open && t.name == "rootfile") t.attrs["full-path"]?.let { return it }
            }
            return null
        }

        /** OPF manifest + spine -> ordered content-document paths (zip-absolute). */
        private fun spineContentPaths(zip: ZipReader, opfPath: String): List<String> {
            val opf = zip.readText(opfPath) ?: return emptyList()
            val baseDir = opfPath.substringBeforeLast('/', "")
            val manifest = HashMap<String, String>()
            val spine = ArrayList<String>()
            for (t in MiniXml.tokenize(opf)) {
                if (t !is XmlToken.Open) continue
                when (t.name) {
                    "item" -> { val id = t.attrs["id"]; val href = t.attrs["href"]; if (id != null && href != null) manifest[id] = href }
                    "itemref" -> t.attrs["idref"]?.let { spine.add(it) }
                }
            }
            return spine.mapNotNull { manifest[it] }.map { resolvePath(baseDir, it) }
        }

        /** Gather author CSS in document order: linked stylesheets then `<style>` blocks. */
        private fun collectAuthorCss(zip: ZipReader, tree: HtmlNode.Element, docDir: String): String {
            val sb = StringBuilder()
            fun walk(el: HtmlNode.Element) {
                when (el.tag) {
                    "link" -> {
                        val rel = el.attrs["rel"]?.lowercase() ?: ""
                        val href = el.attrs["href"]
                        if ("stylesheet" in rel && href != null) zip.readText(resolvePath(docDir, href))?.let { sb.append(it).append('\n') }
                    }
                    "style" -> { for (c in el.children) if (c is HtmlNode.Text) sb.append(c.text); sb.append('\n') }
                    else -> for (c in el.children) if (c is HtmlNode.Element) walk(c)
                }
            }
            walk(tree)
            return sb.toString()
        }

        /** Resolve a relative href against [baseDir], normalizing `.`/`..` + percent-decode. */
        internal fun resolvePath(baseDir: String, href: String): String {
            val clean = percentDecode(href.substringBefore('#').substringBefore('?'))
            val stack = ArrayList<String>()
            if (!clean.startsWith("/") && baseDir.isNotEmpty()) for (seg in baseDir.split('/')) if (seg.isNotEmpty()) stack.add(seg)
            for (seg in clean.split('/')) when (seg) {
                "", "." -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                else -> stack.add(seg)
            }
            return stack.joinToString("/")
        }

        private fun percentDecode(s: String): String {
            if ('%' !in s) return s
            val bytes = ArrayList<Byte>(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val hi = hexVal(s[i + 1]); val lo = hexVal(s[i + 2])
                    if (hi >= 0 && lo >= 0) { bytes.add(((hi shl 4) or lo).toByte()); i += 3; continue }
                }
                for (b in c.toString().encodeToByteArray()) bytes.add(b)
                i++
            }
            return bytes.toByteArray().decodeToString()
        }

        private fun hexVal(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'; in 'a'..'f' -> c - 'a' + 10; in 'A'..'F' -> c - 'A' + 10; else -> -1
        }
    }
}

/** One reflowed EPUB page: paints backgrounds/borders, then text lines and images. */
class EpubPage internal constructor(
    private val page: PageRender,
    private val doc: EpubDocument,
) {
    val width: Double get() = doc.pageWidth
    val height: Double get() = doc.pageHeight

    fun renderTo(canvas: PdfCanvas, deviceCtm: Matrix = Matrix.IDENTITY) {
        canvas.beginPage(width, height, deviceCtm)
        val margin = doc.margin
        val startY = page.startY
        val contentTopYUp = height - margin
        val bandBottom = startY + (height - 2 * margin)
        fun yUp(docY: Double) = contentTopYUp - (docY - startY)

        for (box in page.decoBoxes) paintBox(box, canvas, deviceCtm, margin, startY, bandBottom, ::yUp)

        for (line in page.lines) {
            val base = yUp(line.yTop + line.ascent)
            for (run in line.runs) {
                val tm = Matrix.translation(margin + run.x, base + run.baselineShift)
                canvas.drawGlyphs(
                    run.glyphs, run.fontSize, unitsPerEm = run.unitsPerEm, hasOutlines = run.hasOutlines,
                    fontSpec = run.fontSpec, textToDevice = deviceCtm.concat(tm),
                    color = run.color, alpha = 1.0, blendMode = BlendMode.Normal,
                )
            }
        }

        for (box in page.images) {
            val img = box.image ?: continue
            val m = Matrix(box.drawWidth, 0.0, 0.0, box.drawHeight, margin + box.x, yUp(box.bottom))
            canvas.drawImage(img, deviceCtm.concat(m))
        }
        canvas.endPage()
    }

    private fun paintBox(
        box: LayoutBox, canvas: PdfCanvas, ctm: Matrix, margin: Double,
        startY: Double, bandBottom: Double, yUp: (Double) -> Double,
    ) {
        val s = box.style
        val xDev = margin + box.x
        val w = box.borderBoxWidth
        val topDoc = maxOf(box.y, startY)
        val botDoc = minOf(box.bottom, bandBottom)
        if (botDoc <= topDoc || w <= 0.0) return

        s.backgroundColor?.let { rectFill(canvas, ctm, xDev, yUp(botDoc), w, yUp(topDoc) - yUp(botDoc), it) }

        val eT = s.borderTop.effective; val eB = s.borderBottom.effective
        val eL = s.borderLeft.effective; val eR = s.borderRight.effective
        if (eT > 0) horizontalEdge(canvas, ctm, xDev, w, box.y, box.y + eT, startY, bandBottom, yUp, s.borderTop.color)
        if (eB > 0) horizontalEdge(canvas, ctm, xDev, w, box.bottom - eB, box.bottom, startY, bandBottom, yUp, s.borderBottom.color)
        if (eL > 0) rectFill(canvas, ctm, xDev, yUp(botDoc), eL, yUp(topDoc) - yUp(botDoc), s.borderLeft.color)
        if (eR > 0) rectFill(canvas, ctm, xDev + w - eR, yUp(botDoc), eR, yUp(topDoc) - yUp(botDoc), s.borderRight.color)
    }

    private fun horizontalEdge(
        canvas: PdfCanvas, ctm: Matrix, xDev: Double, w: Double, y0Doc: Double, y1Doc: Double,
        startY: Double, bandBottom: Double, yUp: (Double) -> Double, color: RgbColor,
    ) {
        val t = maxOf(y0Doc, startY); val b = minOf(y1Doc, bandBottom)
        if (b <= t) return
        rectFill(canvas, ctm, xDev, yUp(b), w, yUp(t) - yUp(b), color)
    }

    private fun rectFill(canvas: PdfCanvas, ctm: Matrix, x: Double, yBottom: Double, w: Double, h: Double, color: RgbColor) {
        if (w <= 0.0 || h <= 0.0) return
        val path = PdfPath.Builder().apply { rectangle(x, yBottom, w, h) }.build()
        canvas.fillPath(path, ctm, color, evenOdd = false, alpha = 1.0, blendMode = BlendMode.Normal)
    }
}
