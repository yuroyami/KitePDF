package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.css.ComputedStyle
import io.github.yuroyami.kitepdf.epub.css.CssParser
import io.github.yuroyami.kitepdf.epub.css.Direction
import io.github.yuroyami.kitepdf.epub.css.FontFaceRule
import io.github.yuroyami.kitepdf.epub.css.Origin
import io.github.yuroyami.kitepdf.epub.css.StyleResolver
import io.github.yuroyami.kitepdf.epub.css.StyleRule
import io.github.yuroyami.kitepdf.KiteDocument
import io.github.yuroyami.kitepdf.KiteMetadata
import io.github.yuroyami.kitepdf.KiteOutlineItem
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
public class EpubDocument internal constructor(
    private val parsed: ParsedEpub,
    /** Page size, font size and margin. Change at runtime via [withSettings]. */
    public val settings: EpubSettings,
) : KiteDocument {

    internal val zip: ZipReader get() = parsed.zip

    /** EPUB-specific metadata (title, authors, cover path, reading direction). */
    public val epubMetadata: EpubMetadata get() = parsed.metadata

    /** Navigation tree from EPUB 3 nav.xhtml or EPUB 2 toc.ncx (empty if none). */
    public val tableOfContents: TableOfContents get() = parsed.toc

    /** Format-neutral title/authors/language for [KiteDocument] viewers. */
    override val metadata: KiteMetadata
        get() = KiteMetadata(
            title = parsed.metadata.title,
            authors = parsed.metadata.creators,
            language = parsed.metadata.language,
            rightToLeft = parsed.metadata.rightToLeft,
        )

    /**
     * Format-neutral outline for [KiteDocument] viewers: [tableOfContents]
     * with each href resolved to a zero-based page index through the
     * anchor/pagination map (null for grouping labels and unknown targets).
     */
    override val outline: List<KiteOutlineItem> by lazy {
        fun map(e: TocEntry): KiteOutlineItem = KiteOutlineItem(
            title = e.label,
            pageIndex = e.href?.let { h ->
                pageIndexOfHref(if (e.fragment != null) "$h#${e.fragment}" else h)
            },
            children = e.children.map(::map),
        )
        parsed.toc.entries.map(::map)
    }

    public val pageWidth: Double get() = settings.pageWidth
    public val pageHeight: Double get() = settings.pageHeight
    public val fontSize: Double get() = settings.fontSize
    public val margin: Double get() = settings.margin

    private val contentWidth: Double get() = settings.pageWidth - 2 * settings.margin
    private val pageContentHeight: Double get() = settings.pageHeight - 2 * settings.margin

    /** True for a pre-paginated (fixed-layout) book: one page per spine, no reflow. */
    public val isFixedLayout: Boolean get() = parsed.fixedLayout

    /**
     * The reader-origin cascade layer built from [settings]: universal rules
     * that outrank author-important, so the user's font/color/justify choice
     * always wins. Empty for all-default settings (zero cascade impact).
     */
    private val readerRules: List<StyleRule> by lazy {
        val css = buildString {
            settings.fontFamily?.let {
                val fam = when (it) {
                    ReaderFontFamily.SERIF -> "serif"
                    ReaderFontFamily.SANS_SERIF -> "sans-serif"
                    ReaderFontFamily.MONOSPACE -> "monospace"
                }
                append("*{font-family:$fam}")
            }
            settings.textColor?.let { append("*{color:${cssColor(it)}}") }
            settings.justify?.let { append("*{text-align:${if (it) "justify" else "left"}}") }
        }
        if (css.isEmpty()) emptyList() else CssParser.parse(css, Origin.READER)
    }

    private fun cssColor(c: RgbColor): String {
        fun hex(v: Double) = (v.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
            .toString(16).padStart(2, '0')
        return "#${hex(c.r)}${hex(c.g)}${hex(c.b)}"
    }

    // Box tree per spine — depends on font size + column width, so it is rebuilt
    // from the (already parsed) DOM + CSS whenever settings change. The expensive
    // parse (unzip, HTML, CSS, fonts) is done once and lives in ParsedEpub.
    private val docRoots: List<BlockBox> by lazy {
        parsed.spines.map { sp ->
            val layoutWidth = if (parsed.fixedLayout) sp.viewport.first else contentWidth
            val layoutHeight = if (parsed.fixedLayout) sp.viewport.second else pageContentHeight
            val resolver = StyleResolver(
                sp.rules, settings.fontSize, layoutWidth, parsed.baseDir, layoutHeight,
                readerRules = readerRules, useAuthorCss = settings.usePublisherCss,
            )
            BoxBuilder(resolver, sp.path) { href -> resolvePath(sp.docDir, href) }.build(sp.tree)
        }
    }

    private val root: BlockBox by lazy {
        BlockBox(ComputedStyle.initial(settings.fontSize, direction = parsed.baseDir), docRoots)
    }

    /**
     * Vertical writing (T-72): true when the first spine root resolves
     * `writing-mode: vertical-rl` (Japanese tategaki). One mode per document;
     * mixed horizontal/vertical spines follow the first (a noted limit).
     * Fixed-layout books stay on the pre-paginated path regardless.
     */
    internal val isVertical: Boolean by lazy {
        if (parsed.fixedLayout) return@lazy false
        // The spine root box wraps the document node (initial style); the html
        // element's computed style sits one level down and body's below that,
        // so walk the first-child chain a few levels.
        var box: LayoutBox? = docRoots.firstOrNull()
        var depth = 0
        while (box != null && depth < 4) {
            val s = when (box) {
                is BlockBox -> box.style
                is TextBlockBox -> box.style
                else -> null
            }
            if (s?.writingMode == io.github.yuroyami.kitepdf.epub.css.WritingMode.VERTICAL_RL) return@lazy true
            box = (box as? BlockBox)?.children?.firstOrNull()
            depth++
        }
        false
    }

    private val fixedSpines: List<FixedSpine>? by lazy {
        if (!parsed.fixedLayout) null
        else docRoots.indices.map { FixedSpine(docRoots[it], parsed.spines[it].viewport.first, parsed.spines[it].viewport.second) }
    }

    /**
     * The document's dominant language for hyphenation pattern selection:
     * the first spine whose `<html>` or `<body>` carries `xml:lang`/`lang`
     * (the parser folds both onto the `lang` key), else the OPF
     * `dc:language`. Null falls back to the en-US patterns in [BoxLayout].
     * One language per document; per-spine switching is a noted follow-up.
     */
    internal val documentLanguage: String? by lazy {
        for (sp in parsed.spines) {
            val html = sp.tree.children.filterIsInstance<HtmlNode.Element>()
                .firstOrNull { it.tag == "html" }
            val body = html?.children?.filterIsInstance<HtmlNode.Element>()
                ?.firstOrNull { it.tag == "body" }
            val lang = html?.attrs?.get("lang")?.takeIf { it.isNotBlank() }
                ?: body?.attrs?.get("lang")?.takeIf { it.isNotBlank() }
            if (lang != null) return@lazy lang
        }
        parsed.metadata.language?.takeIf { it.isNotBlank() }
    }

    private val pageRenders: List<PageRender> by lazy {
        val fx = fixedSpines
        if (fx != null) return@lazy fx.map { spine ->
            BoxLayout(::loadImage, ::loadSvg, spine.height, parsed.fonts, documentLanguage, settings.lineHeightScale)
                .layout(spine.root, spine.width)
            Paginator.paginateFixed(spine.root, spine.width, spine.height)
        }
        // Vertical writing swaps the budgets: the inline (line-length) budget is
        // the page content HEIGHT and each page holds contentWidth of columns.
        val inlineBudget = if (isVertical) pageContentHeight else contentWidth
        val blockBudget = if (isVertical) contentWidth else pageContentHeight
        BoxLayout(
            ::loadImage, ::loadSvg, blockBudget, parsed.fonts, documentLanguage,
            settings.lineHeightScale, vertical = isVertical,
        ).layout(root, inlineBudget)
        Paginator.paginate(root, settings.pageWidth, settings.pageHeight, settings.margin, vertical = isVertical)
    }

    /** The reflowed pages, ready to render. */
    override val pages: List<EpubPage> by lazy { pageRenders.map { EpubPage(it, this) } }

    override val pageCount: Int get() = pages.size

    /**
     * A copy of this book re-laid-out with new [settings], reusing the parse (no
     * re-unzip / re-parse of HTML, CSS or fonts). Use for reader controls that
     * change font size, margins or page size at runtime — cheap next to [open].
     */
    public fun withSettings(settings: EpubSettings): EpubDocument = EpubDocument(parsed, settings)

    /** Shorthand for [withSettings] changing only the body font size (points). */
    public fun withFontSize(fontSize: Double): EpubDocument = withSettings(settings.copy(fontSize = fontSize))

    /** Shorthand for [withSettings] changing the page size — e.g. on resize / rotation. */
    public fun withPageSize(pageWidth: Double, pageHeight: Double): EpubDocument =
        withSettings(settings.copy(pageWidth = pageWidth, pageHeight = pageHeight))

    /** Shorthand for [withSettings] changing only the page margin (points). */
    public fun withMargin(margin: Double): EpubDocument = withSettings(settings.copy(margin = margin))

    /**
     * Find [needle] across the book, lazily page by page (a UI can show
     * incremental results). Same matching rules as [KiteStructuredText.search]:
     * case-insensitive by default, line breaks read as one space, a
     * hyphenated line break joins directly, matches never cross blocks.
     */
    public fun search(needle: String, ignoreCase: Boolean = true): Sequence<KiteSearchHit> = sequence {
        if (needle.isEmpty()) return@sequence
        for ((i, page) in pages.withIndex()) {
            yieldAll(page.textContent().search(needle, ignoreCase, pageIndex = i))
        }
    }

    /* ── href -> page navigation ─────────────────────────────────────────── */

    /**
     * `spinePath` and `spinePath#id` -> zero-based page index. Spine starts map
     * to the page holding the spine root's top; anchors to the page holding
     * their box's top (inline ids anchor to their enclosing block).
     */
    private val anchorPages: Map<String, Int> by lazy {
        val renders = pageRenders // force layout + pagination first
        val map = HashMap<String, Int>()
        if (parsed.fixedLayout) {
            // One page per spine document.
            parsed.spines.forEachIndexed { i, sp ->
                map.getOrPut(sp.path) { i }
                collectAnchors(docRoots[i]) { id, _ -> map.getOrPut("${sp.path}#$id") { i } }
            }
        } else {
            val starts = renders.map { it.startY }
            fun pageOf(y: Double): Int {
                var p = 0
                for (k in starts.indices) if (starts[k] <= y + 1e-9) p = k else break
                return p
            }
            parsed.spines.forEachIndexed { i, sp ->
                map.getOrPut(sp.path) { pageOf(docRoots[i].y) }
                collectAnchors(docRoots[i]) { id, y -> map.getOrPut("${sp.path}#$id") { pageOf(y) } }
            }
        }
        map
    }

    private fun collectAnchors(box: LayoutBox, sink: (String, Double) -> Unit) {
        when (box) {
            is BlockBox -> {
                for (id in box.anchors) sink(id, box.y)
                for (c in box.children) collectAnchors(c, sink)
            }
            is TableBox -> for (r in box.rows) for (cell in r.cells) collectAnchors(cell, sink)
            else -> {}
        }
    }

    /**
     * Zero-based page of an internal href: `path.xhtml`, `path.xhtml#id`
     * (paths zip-root-relative, as [EpubPage.links] and [TocEntry] carry
     * them). Null for unknown targets and external URLs. An unknown fragment
     * falls back to its document's first page.
     */
    internal fun pageIndexOfHref(href: String): Int? {
        val clean = href.trim()
        val path = clean.substringBefore('#')
        val frag = clean.substringAfter('#', "")
        return if (frag.isNotEmpty()) anchorPages["$path#$frag"] ?: anchorPages[path]
        else anchorPages[path]
    }

    /**
     * Zero-based page of an internal href: `path.xhtml` or `path.xhtml#id`,
     * zip-root-relative, exactly as [EpubPage.links] and [TocEntry] carry
     * them. Null for unknown targets and external URLs; an unknown fragment
     * falls back to its document's first page. This is the navigation half
     * of a link tap: viewers scroll to the returned page.
     */
    public fun pageOf(href: String): Int? = pageIndexOfHref(href)

    private fun loadImage(zipPath: String): ImageXObject? =
        parsed.zip.read(zipPath)?.let { ImageXObject.fromEncodedImage(it) }

    private fun loadSvg(zipPath: String): SvgImage? =
        parsed.zip.read(zipPath)?.let { SvgImage.parse(it) }

    public companion object {
        public fun open(
            bytes: ByteArray,
            pageWidth: Double = 400.0,
            pageHeight: Double = 640.0,
            fontSize: Double = 12.0,
            margin: Double = 36.0,
        ): EpubDocument = open(bytes, EpubSettings(pageWidth, pageHeight, fontSize, margin))

        /**
         * Parse [bytes] and lay out at [settings].
         *
         * @throws EpubFormatException when the bytes are not a readable EPUB,
         *   with a message naming the first structural failure (missing
         *   container.xml, missing OPF, empty spine, no readable documents).
         */
        public fun open(bytes: ByteArray, settings: EpubSettings): EpubDocument =
            EpubDocument(parse(bytes, settings), settings)

        /** [open], but null instead of [EpubFormatException] on a malformed book. */
        public fun openOrNull(bytes: ByteArray, settings: EpubSettings = EpubSettings()): EpubDocument? =
            try { open(bytes, settings) } catch (_: EpubFormatException) { null }

        /**
         * The font-size-independent parse: unzip + OPF + per-spine DOM/CSS +
         * fonts + metadata + TOC. Everything here is reused across re-layouts, so
         * [EpubDocument.withSettings] never re-runs it. [settings] is read only
         * for the default viewport fallback of a spine with no `<meta viewport>`.
         */
        private fun parse(bytes: ByteArray, settings: EpubSettings): ParsedEpub {
            val zip = ZipReader(bytes)
            val opfPath = containerOpfPath(zip)
                ?: throw EpubFormatException("META-INF/container.xml missing or unreadable")
            val opf = Opf.parse(zip, opfPath)
                ?: throw EpubFormatException("OPF not found at $opfPath")
            val contentPaths = opf.spineIdrefs.mapNotNull { opf.itemsById[it]?.href }.map { resolvePath(opf.baseDir, it) }
            if (contentPaths.isEmpty()) throw EpubFormatException("spine is empty in $opfPath")

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
                spines.add(ParsedSpine(tree, css.rules, docDir, vp, path))
            }
            if (spines.isEmpty()) throw EpubFormatException("spine has no readable documents")

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
                // Prefer the cheapest format to unpack: raw SFNT (.ttf/.otf), then
                // WOFF 1.0 (zlib tables), then WOFF2 (brotli + glyf transform).
                // Fall back to the first src otherwise and let signature sniffing
                // in FontRegistry.face sort it out.
                val url = rule.srcUrls.firstOrNull { it.endsWith(".ttf", true) || it.endsWith(".otf", true) }
                    ?: rule.srcUrls.firstOrNull { it.endsWith(".woff", true) }
                    ?: rule.srcUrls.firstOrNull { it.endsWith(".woff2", true) }
                    ?: rule.srcUrls.firstOrNull()
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
                        if ("stylesheet" in rel && href != null) {
                            val path = resolvePath(docDir, href)
                            zip.readText(path)?.let {
                                sb.append(inlineImports(zip, it, dirOf(path), 0, hashSetOf(path))).append('\n')
                            }
                        }
                    }
                    "style" -> {
                        val text = buildString { for (c in el.children) if (c is HtmlNode.Text) append(c.text) }
                        sb.append(inlineImports(zip, text, docDir, 0, HashSet())).append('\n')
                    }
                    else -> for (c in el.children) if (c is HtmlNode.Element) walk(c)
                }
            }
            walk(tree)
            return sb.toString()
        }

        /**
         * Replace `@import url(...)` / `@import "..."` with the imported
         * sheet's content, resolved zip-relative, recursively (depth cap 8,
         * visited-set cycle guard). Media conditions after the target are
         * ignored, matching the parser's always-on `@media` flattening.
         */
        private fun inlineImports(zip: ZipReader, css: String, baseDir: String, depth: Int, visited: MutableSet<String>): String {
            if (depth >= 8 || "@import" !in css) return css
            return IMPORT_RE.replace(css) { m ->
                val path = resolvePath(baseDir, m.groupValues[1])
                if (!visited.add(path)) ""
                else zip.readText(path)?.let { inlineImports(zip, it, dirOf(path), depth + 1, visited) } ?: ""
            }
        }

        private fun dirOf(path: String): String = path.substringBeforeLast('/', "")

        private val IMPORT_RE = Regex(
            """@import\s+(?:url\(\s*)?["']?([^"')\s;]+)["']?\s*\)?[^;{]*;""",
            RegexOption.IGNORE_CASE,
        )

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
 * A tappable link region on an [EpubPage]. [rect] is in display space (y-down;
 * y-min stored in [Rectangle.bottom]). [href] is either `zipPath#fragment`
 * (internal, resolve with the document's href navigation) or an external URL.
 */
public class EpubLink internal constructor(
    public val rect: io.github.yuroyami.kitepdf.Rectangle,
    public val href: String,
)

/** Generic font family a reader app can force via [EpubSettings.fontFamily]. */
public enum class ReaderFontFamily { SERIF, SANS_SERIF, MONOSPACE }

/**
 * Reader layout settings. All values in points. Change them at runtime with
 * [EpubDocument.withSettings] (or the `withFontSize`/`withPageSize`/`withMargin`
 * shorthands) to re-flow without re-parsing the book.
 *
 * The typography overrides (font family, colors, justification) are applied
 * as a reader-origin cascade layer that outranks author-important CSS: the
 * user's explicit preference beats the publisher's stylesheet. All-default
 * settings change nothing.
 */
public data class EpubSettings(
    val pageWidth: Double = 400.0,
    val pageHeight: Double = 640.0,
    /** Body font size in points; author CSS scales relative to it. */
    val fontSize: Double = 12.0,
    /** Uniform page margin in points (reflowable books only). */
    val margin: Double = 36.0,
    /** Force every run onto a generic family (null = publisher fonts). */
    val fontFamily: ReaderFontFamily? = null,
    /** Multiplies every line's height (1.0 = as authored). */
    val lineHeightScale: Double = 1.0,
    /** Force all text to this color (night mode); null = as authored. */
    val textColor: RgbColor? = null,
    /** Painted under everything on every page; null = no page background. */
    val backgroundColor: RgbColor? = null,
    /** true forces justify, false forces left-align; null = as authored. */
    val justify: Boolean? = null,
    /** False drops the publisher's CSS (author rules + inline styles): UA + reader layers only. */
    val usePublisherCss: Boolean = true,
)

/** One spine document's font-size-independent parse: its DOM, author CSS rules, base dir and viewport. */
internal class ParsedSpine(
    val tree: HtmlNode.Element,
    val rules: List<StyleRule>,
    val docDir: String,
    val viewport: Pair<Double, Double>,
    /** Zip path of this spine document — the key for href -> page navigation. */
    val path: String,
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
public class EpubPage internal constructor(
    private val page: PageRender,
    private val doc: EpubDocument,
) : KitePage {
    public val width: Double get() = page.pageWidth
    public val height: Double get() = page.pageHeight

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
        if (page.vertical) {
            renderVerticalTo(canvas, deviceCtm)
            return
        }
        canvas.beginPage(width, height, deviceCtm)
        val margin = page.margin
        val startY = page.startY
        val bandBottom = startY + (height - 2 * margin)
        fun yUp(docY: Double) = height - displayY(docY)

        // Reader background (night mode): under everything, full page.
        doc.settings.backgroundColor?.let { bg ->
            val rect = PdfPath.Builder().apply {
                moveTo(0.0, 0.0); lineTo(width, 0.0); lineTo(width, height); lineTo(0.0, height); close()
            }.build()
            canvas.fillPath(rect, deviceCtm, bg, evenOdd = false)
        }

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
            // Inline images: bottom on the baseline, next to the text runs.
            for (im in line.images) {
                val svg = im.svg
                if (svg != null && svg.width > 0 && svg.height > 0) {
                    val m = Matrix(
                        im.width / svg.width, 0.0, 0.0, -im.height / svg.height,
                        margin + im.x, base + im.height,
                    )
                    svg.render(canvas, deviceCtm.concat(m))
                } else if (im.image != null) {
                    val m = Matrix(im.width, 0.0, 0.0, im.height, margin + im.x, base)
                    canvas.drawImage(im.image, deviceCtm.concat(m))
                }
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
            // object-fit: cover — scale to FILL the box preserving aspect,
            // center, and clip the overflow to the box rect.
            if (box.style.objectFit == io.github.yuroyami.kitepdf.epub.css.ObjectFit.COVER &&
                img.width > 0 && img.height > 0
            ) {
                val scale = maxOf(box.drawWidth / img.width, box.drawHeight / img.height)
                val dw = img.width * scale
                val dh = img.height * scale
                val dx = (box.drawWidth - dw) / 2.0
                val dy = (box.drawHeight - dh) / 2.0
                val clip = PdfPath.Builder()
                    .apply { rectangle(margin + box.x, yUp(box.bottom), box.drawWidth, box.drawHeight) }
                    .build()
                canvas.pushClip(clip, deviceCtm, evenOdd = false)
                val m = Matrix(dw, 0.0, 0.0, dh, margin + box.x + dx, yUp(box.bottom) + dy)
                canvas.drawImage(img, deviceCtm.concat(m))
                canvas.popClip()
                continue
            }
            val m = Matrix(box.drawWidth, 0.0, 0.0, box.drawHeight, margin + box.x, yUp(box.bottom))
            canvas.drawImage(img, deviceCtm.concat(m))
        }
        canvas.endPage()
    }

    /**
     * Vertical-rl painting (T-72): the logical layout maps onto physical
     * columns advancing right-to-left, the inline axis running down the page.
     * Full-width glyphs stand upright, centred on the column's em axis;
     * everything else rotates 90 degrees clockwise around the shared baseline.
     */
    private fun renderVerticalTo(canvas: PdfCanvas, deviceCtm: Matrix) {
        canvas.beginPage(width, height, deviceCtm)
        val margin = page.margin
        val startY = page.startY
        val bandBottom = startY + (width - 2 * margin)
        // Logical block position -> the column's physical x (canvas space).
        fun colX(v: Double) = width - margin - (v - startY)

        doc.settings.backgroundColor?.let { bg ->
            val rect = PdfPath.Builder().apply {
                moveTo(0.0, 0.0); lineTo(width, 0.0); lineTo(width, height); lineTo(0.0, height); close()
            }.build()
            canvas.fillPath(rect, deviceCtm, bg, evenOdd = false)
        }

        for (box in page.decoBoxes) paintBoxVertical(box, canvas, deviceCtm, margin, startY, bandBottom, ::colX)

        for (line in page.lines) {
            for (run in line.runs) {
                // The horizontal baseline maps to a vertical em axis at this x
                // (a positive baselineShift moves toward the line-over side, so
                // ruby lands to the RIGHT of its base column).
                val xAxis = colX(line.yTop + line.ascent - run.baselineShift)
                var pen = margin + run.x // display-y pen, running down the page
                var k = 0
                while (k < run.glyphs.size) {
                    val g = run.glyphs[k]
                    if (isUpright(g)) {
                        val advPt = g.advanceWidth * run.fontSize / 1000.0
                        // Counter-rotated in place: centred on the em axis, the em
                        // box straddling the axis by the nominal ascent/descent.
                        val x0 = xAxis + UPRIGHT_CENTER * run.fontSize - advPt / 2.0
                        val baseline = pen + advPt / 2.0 + UPRIGHT_CENTER * run.fontSize
                        canvas.drawGlyphs(
                            run.glyphs.subList(k, k + 1), run.fontSize, unitsPerEm = run.unitsPerEm,
                            hasOutlines = run.hasOutlines, fontSpec = run.fontSpec,
                            textToDevice = deviceCtm.concat(Matrix.translation(x0, height - baseline)),
                            color = run.color, alpha = 1.0, blendMode = BlendMode.Normal,
                        )
                        pen += advPt
                        k++
                    } else {
                        // Rotated segment: one call whose pen advances down the page.
                        var j = k
                        var segAdv = 0.0
                        while (j < run.glyphs.size && !isUpright(run.glyphs[j])) {
                            segAdv += run.glyphs[j].advanceWidth * run.fontSize / 1000.0
                            j++
                        }
                        val tm = Matrix(0.0, -1.0, 1.0, 0.0, xAxis, height - pen)
                        canvas.drawGlyphs(
                            run.glyphs.subList(k, j), run.fontSize, unitsPerEm = run.unitsPerEm,
                            hasOutlines = run.hasOutlines, fontSpec = run.fontSpec,
                            textToDevice = deviceCtm.concat(tm),
                            color = run.color, alpha = 1.0, blendMode = BlendMode.Normal,
                        )
                        pen += segAdv
                        k = j
                    }
                }
            }
            // Inline images rotate with the flow: the inline extent runs down
            // the page, the height extends left of the baseline axis.
            for (im in line.images) {
                val xAxis = colX(line.yTop + line.ascent)
                val top = margin + im.x
                val svg = im.svg
                if (svg != null && svg.width > 0 && svg.height > 0) {
                    val m = Matrix(0.0, -im.width / svg.width, -im.height / svg.height, 0.0, xAxis + im.height, height - top)
                    svg.render(canvas, deviceCtm.concat(m))
                } else if (im.image != null) {
                    val m = Matrix(0.0, -im.width, im.height, 0.0, xAxis, height - top)
                    canvas.drawImage(im.image, deviceCtm.concat(m))
                }
            }
        }

        for (box in page.images) {
            val left = colX(box.bottom)
            val top = margin + box.x
            val svg = box.svg
            if (svg != null) {
                val m = Matrix(0.0, -box.drawWidth / svg.width, -box.drawHeight / svg.height, 0.0, left + box.drawHeight, height - top)
                svg.render(canvas, deviceCtm.concat(m))
                continue
            }
            val img = box.image ?: continue
            val m = Matrix(0.0, -box.drawWidth, box.drawHeight, 0.0, left, height - top)
            canvas.drawImage(img, deviceCtm.concat(m))
        }
        canvas.endPage()
    }

    /** Upright in vertical flow: the full-width (CJK) codepoints; the rest rotate. */
    private fun isUpright(g: io.github.yuroyami.kitepdf.font.TextGlyph): Boolean =
        g.text.isNotEmpty() && FontMetrics.isWide(g.text[0].code)

    /** [paintBox] under the vertical mapping: block spans columns, inline runs down. */
    private fun paintBoxVertical(
        box: LayoutBox, canvas: PdfCanvas, ctm: Matrix, margin: Double,
        startY: Double, bandBottom: Double, colX: (Double) -> Double,
    ) {
        val s = box.style
        val w = box.borderBoxWidth // inline extent (runs down the page)
        val topDoc = maxOf(box.y, startY)
        val botDoc = minOf(box.bottom, bandBottom)
        if (botDoc <= topDoc || w <= 0.0) return
        val yTopDisp = margin + box.x

        fun fill(vFrom: Double, vTo: Double, uFrom: Double, uLen: Double, color: RgbColor) {
            if (vTo <= vFrom || uLen <= 0.0) return
            rectFill(canvas, ctm, colX(vTo), height - (uFrom + uLen), vTo - vFrom, uLen, color)
        }

        s.backgroundColor?.let { fill(topDoc, botDoc, yTopDisp, w, it) }

        val eT = s.borderTop.effective; val eB = s.borderBottom.effective
        val eL = s.borderLeft.effective; val eR = s.borderRight.effective
        // Block-start (logical top) edge is the rightmost column edge; the
        // inline-start/-end edges run across the clipped column band.
        if (eT > 0) fill(maxOf(box.y, startY), minOf(box.y + eT, bandBottom), yTopDisp, w, s.borderTop.color)
        if (eB > 0) fill(maxOf(box.bottom - eB, startY), minOf(box.bottom, bandBottom), yTopDisp, w, s.borderBottom.color)
        if (eL > 0) fill(topDoc, botDoc, yTopDisp, eL, s.borderLeft.color)
        if (eR > 0) fill(topDoc, botDoc, yTopDisp + w - eR, eR, s.borderRight.color)
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

    /* ── links ───────────────────────────────────────────────────────────── */

    /**
     * The tappable link regions on this page, in display space (same rect
     * convention as [KiteStructuredText]: y-min in `bottom`, y-max in `top`,
     * y measured downward). One rect per line a link touches; consecutive
     * same-target runs on a line merge into one rect. Internal targets are
     * `zipPath#fragment` strings resolvable via `EpubDocument.pageIndexOfHref`;
     * external URLs are verbatim.
     */
    public val links: List<EpubLink> by lazy {
        val out = ArrayList<EpubLink>()
        for (line in page.lines) {
            val top = displayY(line.yTop)
            val bottom = top + line.height
            val runs = line.runs.filter { !it.isAnnotation && it.glyphs.isNotEmpty() }.sortedBy { it.x }
            var i = 0
            while (i < runs.size) {
                val href = runs[i].href
                if (href == null) { i++; continue }
                var j = i
                while (j + 1 < runs.size && runs[j + 1].href == href) j++
                out.add(
                    EpubLink(
                        rect = io.github.yuroyami.kitepdf.Rectangle(
                            left = page.margin + runs[i].x,
                            bottom = top,
                            right = runEnd(runs[j]),
                            top = bottom,
                        ),
                        href = href,
                    ),
                )
                i = j + 1
            }
        }
        out
    }

    private fun runEnd(r: PlacedRun): Double =
        page.margin + r.x + r.glyphs.sumOf { it.advanceWidth } * r.fontSize / 1000.0

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

        /**
         * Vertical writing: offset (in em) from the mapped baseline axis to the
         * em-box centre an upright glyph is centred on, assuming the nominal
         * 0.88/0.12 ascent/descent split: (0.88 - 0.12) / 2.
         */
        const val UPRIGHT_CENTER = 0.38
    }
}
