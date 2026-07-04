package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.css.ComputedStyle
import io.github.yuroyami.kitepdf.epub.css.CssParser
import io.github.yuroyami.kitepdf.epub.css.Direction
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
    /** Publication metadata (title, authors, cover, reading direction). */
    val metadata: EpubMetadata,
    /** Navigation tree from EPUB 3 nav.xhtml or EPUB 2 toc.ncx (empty if none). */
    val tableOfContents: TableOfContents,
    val pageWidth: Double,
    val pageHeight: Double,
    val fontSize: Double,
    val margin: Double,
    /** Fixed-layout spines (one page each at its viewport size); null = reflowable. */
    private val fixedSpines: List<FixedSpine>? = null,
) {
    private val contentWidth = pageWidth - 2 * margin
    private val pageContentHeight = pageHeight - 2 * margin

    /** True for a pre-paginated (fixed-layout) book: one page per spine, no reflow. */
    val isFixedLayout: Boolean get() = fixedSpines != null

    private val pageRenders: List<PageRender> by lazy {
        val fx = fixedSpines
        if (fx != null) return@lazy fx.map { spine ->
            BoxLayout(::loadImage, ::loadSvg, spine.height, fonts).layout(spine.root, spine.width)
            Paginator.paginateFixed(spine.root, spine.width, spine.height)
        }
        BoxLayout(::loadImage, ::loadSvg, pageContentHeight, fonts).layout(root, contentWidth)
        Paginator.paginate(root, pageWidth, pageHeight, margin)
    }

    /** The reflowed pages, ready to render. */
    val pages: List<EpubPage> by lazy { pageRenders.map { EpubPage(it, this) } }

    val pageCount: Int get() = pages.size

    private fun loadImage(zipPath: String): ImageXObject? =
        zip.read(zipPath)?.let { ImageXObject.fromEncodedImage(it) }

    private fun loadSvg(zipPath: String): SvgImage? =
        zip.read(zipPath)?.let { SvgImage.parse(it) }

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
            val opf = Opf.parse(zip, opfPath) ?: return null
            val contentPaths = opf.spineIdrefs.mapNotNull { opf.itemsById[it]?.href }.map { resolvePath(opf.baseDir, it) }
            if (contentPaths.isEmpty()) return null

            val fixedLayout = opf.renditionLayout == "pre-paginated" ||
                contentPaths.indices.all { opf.fixedLayoutAt(it) }
            // Fixed-layout content is laid out at its own viewport, not the reflow column.
            val contentWidth = pageWidth - 2 * margin
            val baseDir = if (opf.direction?.lowercase() == "rtl") Direction.RTL else Direction.LTR
            val docRoots = ArrayList<BlockBox>()
            val viewports = ArrayList<Pair<Double, Double>>()
            val faceRules = ArrayList<Pair<FontFaceRule, String>>() // rule + the doc dir its src resolves against
            for (path in contentPaths) {
                val xhtml = zip.readText(path) ?: continue
                val docDir = path.substringBeforeLast('/', "")
                val tree = HtmlParser.parse(xhtml)
                val parsed = CssParser.parseAll(collectAuthorCss(zip, tree, docDir), Origin.AUTHOR)
                for (face in parsed.fontFaces) faceRules.add(face to docDir)
                val vp = parseViewport(tree) ?: (pageWidth to pageHeight)
                viewports.add(vp)
                // Fixed pages lay out at their full viewport width (no page margin).
                val layoutWidth = if (fixedLayout) vp.first else contentWidth
                val resolver = StyleResolver(parsed.rules, fontSize, layoutWidth, baseDir)
                docRoots.add(BoxBuilder(resolver) { href -> resolvePath(docDir, href) }.build(tree))
            }
            if (docRoots.isEmpty()) return null

            val fonts = buildFontRegistry(zip, opf, faceRules)
            val metadata = buildMetadata(opf)
            val toc = TocParser.parse(zip, opf, contentPaths) { base, href -> resolvePath(base, href) }
            val superRoot = BlockBox(ComputedStyle.initial(fontSize, direction = baseDir), docRoots)
            val fixed = if (fixedLayout) {
                docRoots.indices.map { FixedSpine(docRoots[it], viewports[it].first, viewports[it].second) }
            } else null
            return EpubDocument(superRoot, zip, fonts, metadata, toc, pageWidth, pageHeight, fontSize, margin, fixed)
        }

        private fun buildMetadata(opf: OpfPackage): EpubMetadata {
            val coverHref = opf.items.firstOrNull { it.hasProperty("cover-image") }?.href
                ?: opf.metaCoverId?.let { opf.itemsById[it]?.href }
            return EpubMetadata(
                title = opf.title,
                creators = opf.creators,
                language = opf.language,
                identifier = opf.uniqueId,
                coverImagePath = coverHref?.let { resolvePath(opf.baseDir, it) },
                rightToLeft = opf.direction?.lowercase() == "rtl",
            )
        }

        /** Load every `@font-face`'s TrueType file from the zip (deobfuscating mangled ones). */
        private fun buildFontRegistry(zip: ZipReader, opf: OpfPackage, faceRules: List<Pair<FontFaceRule, String>>): FontRegistry {
            if (faceRules.isEmpty()) return FontRegistry.EMPTY
            val obf = parseEncryption(zip)
            val uid = opf.uniqueId ?: ""
            val faces = ArrayList<EmbeddedFace>()
            for ((rule, docDir) in faceRules) {
                // Prefer a format we can parse: raw SFNT (.ttf/.otf), then WOFF 1.0
                // (.woff, zlib-unwrapped). A .woff2-only face falls through and is
                // skipped (brotli not supported). Fall back to the first src otherwise.
                val url = rule.srcUrls.firstOrNull { it.endsWith(".ttf", true) || it.endsWith(".otf", true) }
                    ?: rule.srcUrls.firstOrNull { it.endsWith(".woff", true) }
                    ?: rule.srcUrls.firstOrNull { !it.endsWith(".woff2", true) }
                    ?: continue
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

        /** META-INF/container.xml -> the OPF package path. */
        private fun containerOpfPath(zip: ZipReader): String? {
            val xml = zip.readText("META-INF/container.xml") ?: return null
            for (t in MiniXml.tokenize(xml)) {
                if (t is XmlToken.Open && t.name == "rootfile") t.attrs["full-path"]?.let { return it }
            }
            return null
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

        /** Fixed-layout page size: the `<meta name=viewport>` width/height, else a root `<svg>`'s. */
        private fun parseViewport(tree: HtmlNode.Element): Pair<Double, Double>? {
            var result: Pair<Double, Double>? = null
            var svgSize: Pair<Double, Double>? = null
            fun px(s: String?) = s?.trim()?.removeSuffix("px")?.toDoubleOrNull()
            fun walk(el: HtmlNode.Element) {
                if (el.tag == "meta" && el.attrs["name"]?.lowercase() == "viewport") {
                    var w: Double? = null; var h: Double? = null
                    for (part in (el.attrs["content"] ?: "").split(',', ';')) {
                        val kv = part.split('=')
                        if (kv.size == 2) when (kv[0].trim().lowercase()) {
                            "width" -> w = px(kv[1]); "height" -> h = px(kv[1])
                        }
                    }
                    if (w != null && h != null && w > 0 && h > 0) result = w to h
                }
                if (svgSize == null && el.tag.equals("svg", true)) {
                    val w = px(el.attrs["width"]); val h = px(el.attrs["height"])
                    if (w != null && h != null && w > 0 && h > 0) svgSize = w to h
                }
                for (c in el.children) if (c is HtmlNode.Element) walk(c)
            }
            walk(tree)
            return result ?: svgSize
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

/** One fixed-layout spine: its box tree plus the declared viewport it renders at. */
internal class FixedSpine(val root: BlockBox, val width: Double, val height: Double)

/** One reflowed EPUB page: paints backgrounds/borders, then text lines and images. */
class EpubPage internal constructor(
    private val page: PageRender,
    private val doc: EpubDocument,
) {
    val width: Double get() = page.pageWidth
    val height: Double get() = page.pageHeight

    fun renderTo(canvas: PdfCanvas, deviceCtm: Matrix = Matrix.IDENTITY) {
        canvas.beginPage(width, height, deviceCtm)
        val margin = page.margin
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
            val svg = box.svg
            if (svg != null) {
                // Map the SVG viewport (origin top-left, y-down) onto the box's device
                // rect (y-up): negative y-scale, translate to the box's top edge.
                val m = Matrix(
                    box.drawWidth / svg.width, 0.0, 0.0, -box.drawHeight / svg.height,
                    margin + box.x, yUp(box.bottom) + box.drawHeight,
                )
                svg.render(canvas, deviceCtm.concat(m))
                continue
            }
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
