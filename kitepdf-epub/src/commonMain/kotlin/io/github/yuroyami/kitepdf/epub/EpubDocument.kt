package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.css.ComputedStyle
import io.github.yuroyami.kitepdf.epub.css.CssParser
import io.github.yuroyami.kitepdf.epub.css.Direction
import io.github.yuroyami.kitepdf.epub.css.FontFaceRule
import io.github.yuroyami.kitepdf.epub.css.Origin
import io.github.yuroyami.kitepdf.epub.css.StyleResolver
import io.github.yuroyami.kitepdf.epub.css.StyleRule
import io.github.yuroyami.kitepdf.KiteDocument
import io.github.yuroyami.kitepdf.KitePage
import io.github.yuroyami.kitepdf.KiteSearchHit
import io.github.yuroyami.kitepdf.KiteStructuredText
import io.github.yuroyami.kitepdf.KiteTextBlock
import io.github.yuroyami.kitepdf.KiteTextLine
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
class EpubDocument internal constructor(
    private val parsed: ParsedEpub,
    /** Page size, font size and margin. Change at runtime via [withSettings]. */
    val settings: EpubSettings,
) : KiteDocument {

    internal val zip: ZipReader get() = parsed.zip

    /** Publication metadata (title, authors, cover, reading direction). */
    val metadata: EpubMetadata get() = parsed.metadata

    /** Navigation tree from EPUB 3 nav.xhtml or EPUB 2 toc.ncx (empty if none). */
    val tableOfContents: TableOfContents get() = parsed.toc

    val pageWidth: Double get() = settings.pageWidth
    val pageHeight: Double get() = settings.pageHeight
    val fontSize: Double get() = settings.fontSize
    val margin: Double get() = settings.margin

    private val contentWidth: Double get() = settings.pageWidth - 2 * settings.margin
    private val pageContentHeight: Double get() = settings.pageHeight - 2 * settings.margin

    /** True for a pre-paginated (fixed-layout) book: one page per spine, no reflow. */
    val isFixedLayout: Boolean get() = parsed.fixedLayout

    // Box tree per spine — depends on font size + column width, so it is rebuilt
    // from the (already parsed) DOM + CSS whenever settings change. The expensive
    // parse (unzip, HTML, CSS, fonts) is done once and lives in ParsedEpub.
    private val docRoots: List<BlockBox> by lazy {
        parsed.spines.map { sp ->
            val layoutWidth = if (parsed.fixedLayout) sp.viewport.first else contentWidth
            val resolver = StyleResolver(sp.rules, settings.fontSize, layoutWidth, parsed.baseDir)
            BoxBuilder(resolver) { href -> resolvePath(sp.docDir, href) }.build(sp.tree)
        }
    }

    private val root: BlockBox by lazy {
        BlockBox(ComputedStyle.initial(settings.fontSize, direction = parsed.baseDir), docRoots)
    }

    private val fixedSpines: List<FixedSpine>? by lazy {
        if (!parsed.fixedLayout) null
        else docRoots.indices.map { FixedSpine(docRoots[it], parsed.spines[it].viewport.first, parsed.spines[it].viewport.second) }
    }

    private val pageRenders: List<PageRender> by lazy {
        val fx = fixedSpines
        if (fx != null) return@lazy fx.map { spine ->
            BoxLayout(::loadImage, ::loadSvg, spine.height, parsed.fonts).layout(spine.root, spine.width)
            Paginator.paginateFixed(spine.root, spine.width, spine.height)
        }
        BoxLayout(::loadImage, ::loadSvg, pageContentHeight, parsed.fonts).layout(root, contentWidth)
        Paginator.paginate(root, settings.pageWidth, settings.pageHeight, settings.margin)
    }

    /** The reflowed pages, ready to render. */
    override val pages: List<EpubPage> by lazy { pageRenders.map { EpubPage(it, this) } }

    override val pageCount: Int get() = pages.size

    /**
     * A copy of this book re-laid-out with new [settings], reusing the parse (no
     * re-unzip / re-parse of HTML, CSS or fonts). Use for reader controls that
     * change font size, margins or page size at runtime — cheap next to [open].
     */
    fun withSettings(settings: EpubSettings): EpubDocument = EpubDocument(parsed, settings)

    /** Shorthand for [withSettings] changing only the body font size (points). */
    fun withFontSize(fontSize: Double): EpubDocument = withSettings(settings.copy(fontSize = fontSize))

    /** Shorthand for [withSettings] changing the page size — e.g. on resize / rotation. */
    fun withPageSize(pageWidth: Double, pageHeight: Double): EpubDocument =
        withSettings(settings.copy(pageWidth = pageWidth, pageHeight = pageHeight))

    /** Shorthand for [withSettings] changing only the page margin (points). */
    fun withMargin(margin: Double): EpubDocument = withSettings(settings.copy(margin = margin))

    /**
     * Find [needle] across the book, lazily page by page (a UI can show
     * incremental results). Same matching rules as [KiteStructuredText.search]:
     * case-insensitive by default, line breaks read as one space, a
     * hyphenated line break joins directly, matches never cross blocks.
     */
    fun search(needle: String, ignoreCase: Boolean = true): Sequence<KiteSearchHit> = sequence {
        if (needle.isEmpty()) return@sequence
        for ((i, page) in pages.withIndex()) {
            yieldAll(page.textContent().search(needle, ignoreCase, pageIndex = i))
        }
    }

    private fun loadImage(zipPath: String): ImageXObject? =
        parsed.zip.read(zipPath)?.let { ImageXObject.fromEncodedImage(it) }

    private fun loadSvg(zipPath: String): SvgImage? =
        parsed.zip.read(zipPath)?.let { SvgImage.parse(it) }

    companion object {
        fun open(
            bytes: ByteArray,
            pageWidth: Double = 400.0,
            pageHeight: Double = 640.0,
            fontSize: Double = 12.0,
            margin: Double = 36.0,
        ): EpubDocument? = open(bytes, EpubSettings(pageWidth, pageHeight, fontSize, margin))

        /** Parse [bytes] and lay out at [settings]. Null on an unreadable / spineless book. */
        fun open(bytes: ByteArray, settings: EpubSettings): EpubDocument? {
            val parsed = parse(bytes, settings) ?: return null
            return EpubDocument(parsed, settings)
        }

        /**
         * The font-size-independent parse: unzip + OPF + per-spine DOM/CSS +
         * fonts + metadata + TOC. Everything here is reused across re-layouts, so
         * [EpubDocument.withSettings] never re-runs it. [settings] is read only
         * for the default viewport fallback of a spine with no `<meta viewport>`.
         */
        private fun parse(bytes: ByteArray, settings: EpubSettings): ParsedEpub? {
            val zip = ZipReader(bytes)
            val opfPath = containerOpfPath(zip) ?: return null
            val opf = Opf.parse(zip, opfPath) ?: return null
            val contentPaths = opf.spineIdrefs.mapNotNull { opf.itemsById[it]?.href }.map { resolvePath(opf.baseDir, it) }
            if (contentPaths.isEmpty()) return null

            val fixedLayout = opf.renditionLayout == "pre-paginated" ||
                contentPaths.indices.all { opf.fixedLayoutAt(it) }
            val baseDir = if (opf.direction?.lowercase() == "rtl") Direction.RTL else Direction.LTR
            val spines = ArrayList<ParsedSpine>()
            val faceRules = ArrayList<Pair<FontFaceRule, String>>() // rule + the doc dir its src resolves against
            for (path in contentPaths) {
                val xhtml = zip.readText(path) ?: continue
                val docDir = path.substringBeforeLast('/', "")
                val tree = HtmlParser.parse(xhtml)
                val css = CssParser.parseAll(collectAuthorCss(zip, tree, docDir), Origin.AUTHOR)
                for (face in css.fontFaces) faceRules.add(face to docDir)
                val vp = parseViewport(tree) ?: (settings.pageWidth to settings.pageHeight)
                spines.add(ParsedSpine(tree, css.rules, docDir, vp))
            }
            if (spines.isEmpty()) return null

            return ParsedEpub(
                spines = spines,
                zip = zip,
                fonts = buildFontRegistry(zip, opf, faceRules),
                metadata = buildMetadata(opf),
                toc = TocParser.parse(zip, opf, contentPaths) { base, href -> resolvePath(base, href) },
                baseDir = baseDir,
                fixedLayout = fixedLayout,
            )
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

/**
 * Reader layout settings. All values in points. Change them at runtime with
 * [EpubDocument.withSettings] (or the `withFontSize`/`withPageSize`/`withMargin`
 * shorthands) to re-flow without re-parsing the book.
 */
data class EpubSettings(
    val pageWidth: Double = 400.0,
    val pageHeight: Double = 640.0,
    /** Body font size in points; author CSS scales relative to it. */
    val fontSize: Double = 12.0,
    /** Uniform page margin in points (reflowable books only). */
    val margin: Double = 36.0,
)

/** One spine document's font-size-independent parse: its DOM, author CSS rules, base dir and viewport. */
internal class ParsedSpine(
    val tree: HtmlNode.Element,
    val rules: List<StyleRule>,
    val docDir: String,
    val viewport: Pair<Double, Double>,
)

/**
 * The reusable, font-size-independent parse of a book (unzip + per-spine DOM/CSS
 * + fonts + metadata + TOC). One [ParsedEpub] backs any number of [EpubDocument]s
 * at different [EpubSettings], so re-flowing on a settings change is just a
 * re-layout, never a re-parse.
 */
internal class ParsedEpub(
    val spines: List<ParsedSpine>,
    val zip: ZipReader,
    val fonts: FontRegistry,
    val metadata: EpubMetadata,
    val toc: TableOfContents,
    val baseDir: Direction,
    val fixedLayout: Boolean,
)

/** One reflowed EPUB page: paints backgrounds/borders, then text lines and images. */
class EpubPage internal constructor(
    private val page: PageRender,
    private val doc: EpubDocument,
) : KitePage {
    val width: Double get() = page.pageWidth
    val height: Double get() = page.pageHeight

    override val displayWidth: Double get() = width
    override val displayHeight: Double get() = height

    /** EPUB is y-down from top-left, so the base is a straight vertical flip. */
    override fun displayToDeviceBase(): Matrix = Matrix(1.0, 0.0, 0.0, -1.0, 0.0, height)

    /**
     * Display-space (top-left, y-down) y of a document-space y. The single
     * source of the page's vertical mapping: painting ([renderTo]) flips it
     * to y-up, extraction ([textContent]) uses it directly.
     */
    private fun displayY(docY: Double): Double = page.margin + (docY - page.startY)

    override fun renderTo(canvas: PdfCanvas, deviceCtm: Matrix) {
        canvas.beginPage(width, height, deviceCtm)
        val margin = page.margin
        val startY = page.startY
        val bandBottom = startY + (height - 2 * margin)
        fun yUp(docY: Double) = height - displayY(docY)

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

    /* ── structured text (extraction / search) ───────────────────────────── */

    private val structured: KiteStructuredText by lazy { buildStructuredText() }

    override fun textContent(): KiteStructuredText = structured

    /**
     * Blocks = consecutive page lines sharing one owning [TextBlockBox];
     * lines rebuild their text from the placed runs (in x order, ruby
     * overlays excluded), restoring the collapsed inter-word spaces from the
     * pen gaps, since spaces are never drawn as glyphs.
     */
    private fun buildStructuredText(): KiteStructuredText {
        val blocks = ArrayList<KiteTextBlock>()
        var curOwner: TextBlockBox? = null
        var curLines = ArrayList<KiteTextLine>()
        fun flush() {
            if (curLines.isNotEmpty()) { blocks.add(KiteTextBlock(curLines)); curLines = ArrayList() }
        }
        for (line in page.lines) {
            if (line.owner !== curOwner) { flush(); curOwner = line.owner }
            extractLine(line)?.let(curLines::add)
        }
        flush()
        return KiteStructuredText(blocks)
    }

    private fun extractLine(line: PositionedLine): KiteTextLine? {
        val runs = line.runs
            .filter { !it.isAnnotation && it.glyphs.isNotEmpty() }
            .sortedBy { it.x }
        if (runs.isEmpty()) return null
        val sb = StringBuilder()
        val edges = ArrayList<Double>()
        var penEnd = Double.NaN
        for (run in runs) {
            var x = page.margin + run.x
            // Words are separate runs with a pen gap where the collapsed space
            // was; restore it as one space char spanning the gap.
            if (!penEnd.isNaN() && x - penEnd > run.fontSize * SPACE_GAP_EM && sb.isNotEmpty() && sb.last() != ' ') {
                edges.add(penEnd); sb.append(' ')
            }
            for (g in run.glyphs) {
                val gw = g.advanceWidth * run.fontSize / 1000.0
                val t = g.text
                // A ligature glyph carries several chars: split its advance evenly.
                for (k in t.indices) { edges.add(x + gw * k / t.length); sb.append(t[k]) }
                x += gw
            }
            penEnd = x
        }
        if (sb.isEmpty()) return null
        edges.add(penEnd)
        val top = displayY(line.yTop)
        return KiteTextLine(
            text = sb.toString(),
            // Display-space rect: y-min lives in [Rectangle.bottom] (see KiteStructuredText).
            bounds = io.github.yuroyami.kitepdf.Rectangle(edges.first(), top, edges.last(), top + line.height),
            charEdges = edges.toDoubleArray(),
        )
    }

    private companion object {
        /** Pen-gap threshold (in em) that reads as a collapsed word space. */
        const val SPACE_GAP_EM = 0.15
    }
}
