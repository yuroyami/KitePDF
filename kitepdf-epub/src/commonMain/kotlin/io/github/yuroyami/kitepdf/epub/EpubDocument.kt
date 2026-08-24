package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.zip.ZipReader

import io.github.yuroyami.kitepdf.epub.css.ComputedStyle
import io.github.yuroyami.kitepdf.epub.css.CssParser
import io.github.yuroyami.kitepdf.epub.css.Origin
import io.github.yuroyami.kitepdf.epub.css.StyleResolver
import io.github.yuroyami.kitepdf.epub.css.StyleRule
import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.KiteLock
import io.github.yuroyami.kitepdf.core.withLock
import io.github.yuroyami.kitepdf.core.KiteMetadata
import io.github.yuroyami.kitepdf.core.KiteOutlineItem
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.KiteSearchHit
import io.github.yuroyami.kitepdf.core.KiteStructuredText
import io.github.yuroyami.kitepdf.core.KiteTextBlock
import io.github.yuroyami.kitepdf.core.KiteTextLine
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RgbColor

/**
 * A parsed EPUB, reflowed onto fixed-size pages and rendered through the shared
 * [KiteCanvas] the PDF engine uses. The second document handler on :kitepdf-core.
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
     * Format-neutral outline for [KiteDocument] viewers: [tableOfContents] with
     * each href turned into a [KiteBookmark] target.
     *
     * Building this lays nothing out, so a table of contents opens instantly on
     * a book that is still paginating. `pageIndex` is filled in only once the
     * book is fully laid out; navigate by `target` and the viewer prepares that
     * one chapter on tap.
     */
    override val outline: List<KiteOutlineItem>
        get() {
            val resolved = isComplete
            fun href(e: TocEntry): String? =
                e.href?.let { if (e.fragment != null) "$it#${e.fragment}" else it }
            fun map(e: TocEntry): KiteOutlineItem = KiteOutlineItem(
                title = e.label,
                pageIndex = if (resolved) href(e)?.let { pageIndexOfHref(it) } else null,
                children = e.children.map(::map),
                target = href(e)?.let { bookmarkOf(it) },
            )
            return parsed.toc.entries.map(::map)
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

    /* ── chapter state ────────────────────────────────────────────────────── */

    /** Guards the tables below. Never held while a chapter is being laid out. */
    private val tableLock = KiteLock()

    /** One per chapter, held for that chapter's layout so it happens once. */
    private val chapterLocks: Array<KiteLock> = Array(parsed.spineCount) { KiteLock() }

    /** Box trees, built on demand. Cheap next to layout, but not free. */
    private val builtRoots = arrayOfNulls<BlockBox>(parsed.spineCount)

    /** Laid-out pages per chapter. Null until that chapter is prepared. */
    private val chapterPages = arrayOfNulls<List<PageRender>>(parsed.spineCount)

    // Box tree per spine: depends on font size + column width, so it is rebuilt
    // whenever settings change. The DOM and CSS it is built from are parsed once
    // per chapter and live in ParsedEpub, shared by every re-layout.
    private fun buildDocRoot(chapter: Int): BlockBox {
        val sp = parsed.spine(chapter)
        val (layoutWidth, layoutHeight) =
            if (parsed.fixedLayout) viewportOf(chapter) else contentWidth to pageContentHeight
        val resolver = StyleResolver(
            sp.rules, settings.fontSize, layoutWidth, parsed.baseDir, layoutHeight,
            readerRules = readerRules, useAuthorCss = settings.usePublisherCss,
        )
        return BoxBuilder(resolver, sp.path) { href -> resolvePath(sp.docDir, href) }.build(sp.tree)
    }

    /**
     * One chapter's box tree, memoized. Building runs outside the lock; if two
     * threads race, the first to publish wins and both get that instance, which
     * matters because layout mutates the tree in place.
     */
    private fun docRoot(chapter: Int): BlockBox {
        tableLock.withLock { builtRoots[chapter] }?.let { return it }
        val built = buildDocRoot(chapter)
        return tableLock.withLock { builtRoots[chapter] ?: built.also { builtRoots[chapter] = it } }
    }

    /**
     * One chapter's box tree under a fresh root. Layout starts each chapter at
     * y = 0, so a chapter's geometry never depends on the chapters before it.
     */
    private fun chapterRoot(chapter: Int): BlockBox =
        BlockBox(ComputedStyle.initial(settings.fontSize, direction = parsed.baseDir), listOf(docRoot(chapter)))

    /**
     * Vertical writing: true when the first spine root resolves
     * `writing-mode: vertical-rl` (Japanese tategaki). One mode per document;
     * mixed horizontal/vertical spines follow the first (a noted limit).
     * Fixed-layout books stay on the pre-paginated path regardless.
     */
    internal val isVertical: Boolean by lazy {
        if (parsed.fixedLayout) return@lazy false
        // The spine root box wraps the document node (initial style); the html
        // element's computed style sits one level down and body's below that,
        // so walk the first-child chain a few levels.
        var box: LayoutBox? = if (parsed.spineCount == 0) null else docRoot(0)
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

    private fun fixedSpine(chapter: Int): FixedSpine? {
        if (!parsed.fixedLayout) return null
        val (w, h) = viewportOf(chapter)
        return FixedSpine(docRoot(chapter), w, h)
    }

    /** A fixed-layout chapter's page size: what it declares, else the reader's. */
    private fun viewportOf(chapter: Int): Pair<Double, Double> =
        parsed.spine(chapter).viewport ?: (settings.pageWidth to settings.pageHeight)

    /**
     * The document's language, for hyphenation pattern selection: the `lang` /
     * `xml:lang` on the first chapter's `<html>` or `<body>` (the parser folds
     * both onto the `lang` key), else the OPF `dc:language`. Null falls back to
     * the en-US patterns in [BoxLayout].
     *
     * One language per document. Reading it from the first chapter only keeps it
     * from parsing the whole book, and `dc:language` is required of every EPUB,
     * so the fallback is normally there. Per-spine switching is a noted follow-up.
     */
    internal val documentLanguage: String? by lazy {
        val tree = if (parsed.spineCount == 0) null else parsed.spine(0).tree
        val html = tree?.children?.filterIsInstance<HtmlNode.Element>()
            ?.firstOrNull { it.tag == "html" }
        val body = html?.children?.filterIsInstance<HtmlNode.Element>()
            ?.firstOrNull { it.tag == "body" }
        html?.attrs?.get("lang")?.takeIf { it.isNotBlank() }
            ?: body?.attrs?.get("lang")?.takeIf { it.isNotBlank() }
            ?: parsed.metadata.language?.takeIf { it.isNotBlank() }
    }

    /**
     * The faces [chapter] lays out with: the book's embedded fonts, plus any
     * `@font-face` the chapter declares in its own inline `<style>`. Keeping the
     * inline ones chapter-local is what makes a chapter's layout independent of
     * which chapters were laid out before it.
     */
    private fun fontsFor(chapter: Int): FontRegistry {
        val local = parsed.spine(chapter).localFaces
        return if (local.isEmpty()) parsed.fonts else parsed.fonts.with(local)
    }

    /**
     * Lays out and paginates one chapter, on its own. Every spine item starts
     * on a fresh page, which is what readers expect and what lets a chapter be
     * laid out without the ones before it.
     *
     * Pure: it reads the parse and the settings, and returns new objects. Two
     * calls for the same chapter produce equal pages, so it is safe to run
     * chapters in any order or on any thread.
     */
    private fun paginateChapter(chapter: Int): List<PageRender> {
        val fonts = fontsFor(chapter)
        val spine = fixedSpine(chapter)
        if (spine != null) {
            BoxLayout(::loadImage, ::loadSvg, spine.height, fonts, documentLanguage, settings.lineHeightScale)
                .layout(spine.root, spine.width)
            return listOf(Paginator.paginateFixed(spine.root, spine.width, spine.height))
        }
        // Vertical writing swaps the budgets: the inline (line-length) budget is
        // the page content HEIGHT and each page holds contentWidth of columns.
        val inlineBudget = if (isVertical) pageContentHeight else contentWidth
        val blockBudget = if (isVertical) contentWidth else pageContentHeight
        val root = chapterRoot(chapter)
        BoxLayout(
            ::loadImage, ::loadSvg, blockBudget, fonts, documentLanguage,
            settings.lineHeightScale, vertical = isVertical,
        ).layout(root, inlineBudget)
        val pages = Paginator.paginate(
            root, settings.pageWidth, settings.pageHeight, settings.margin, vertical = isVertical,
        )
        // A spine document with nothing to paint contributed no page when the
        // whole book shared one box tree. Keep that: do not invent a blank page.
        val blank = pages.size == 1 &&
            pages[0].lines.isEmpty() && pages[0].images.isEmpty() && pages[0].decoBoxes.isEmpty()
        return if (blank) emptyList() else pages
    }

    /* ── the chapter API ──────────────────────────────────────────────────── */

    override val chapterCount: Int get() = parsed.spineCount

    override fun isChapterReady(chapter: Int): Boolean =
        chapter in parsed.spineIndices && tableLock.withLock { chapterPages[chapter] } != null

    /**
     * Lays out one chapter. This is where a book's time goes, so it is also the
     * only thing a reader has to wait for: opening at chapter 20 prepares
     * chapter 20, not chapters 0 to 20.
     */
    override fun prepareChapter(chapter: Int) {
        if (chapter !in parsed.spineIndices) return
        if (isChapterReady(chapter)) return
        // Per-chapter lock: two callers for one chapter share a single layout,
        // two callers for different chapters do not wait on each other.
        chapterLocks[chapter].withLock {
            if (isChapterReady(chapter)) return
            val laid = paginateChapter(chapter)
            tableLock.withLock { chapterPages[chapter] = laid }
        }
    }

    /** Whether [chapter]'s document has been read and parsed yet. */
    internal fun isChapterParsed(chapter: Int): Boolean = parsed.isSpineParsed(chapter)

    /** How many stylesheet files this book has parsed, however many chapters link them. */
    internal val stylesheetsParsed: Int get() = parsed.sheetsParsed

    /** [chapter]'s pages, laying it out first. */
    private fun renders(chapter: Int): List<PageRender> {
        prepareChapter(chapter)
        return tableLock.withLock { chapterPages[chapter] } ?: emptyList()
    }

    override fun pageCountIn(chapter: Int): Int =
        if (chapter in parsed.spineIndices) renders(chapter).size else 0

    override fun page(location: KiteLocation): EpubPage {
        val pages = renders(location.chapter)
        val page = pages.getOrNull(location.page)
            ?: throw IndexOutOfBoundsException(
                "no page $location: chapter ${location.chapter} has ${pages.size} page(s)",
            )
        return EpubPage(page, this, location.chapter)
    }

    override val isComplete: Boolean
        get() = tableLock.withLock { chapterPages.all { it != null } }

    override val knownPageCount: Int
        get() = tableLock.withLock { chapterPages.sumOf { it?.size ?: 0 } }

    /**
     * The global index of [location]. Null while any earlier chapter is still
     * unlaid, because the pages before it have not been counted yet.
     */
    override fun pageIndexOf(location: KiteLocation): Int? = tableLock.withLock {
        if (location.chapter !in parsed.spineIndices) return@withLock null
        var offset = 0
        for (c in 0 until location.chapter) offset += (chapterPages[c] ?: return@withLock null).size
        val own = chapterPages[location.chapter] ?: return@withLock null
        if (location.page !in own.indices) return@withLock null
        offset + location.page
    }

    /** The location of a global index, or null past what is laid out so far. */
    override fun locationOf(pageIndex: Int): KiteLocation? = tableLock.withLock {
        if (pageIndex < 0) return@withLock null
        var remaining = pageIndex
        for (c in parsed.spineIndices) {
            val own = chapterPages[c] ?: return@withLock null
            if (remaining < own.size) return@withLock KiteLocation(c, remaining)
            remaining -= own.size
        }
        null
    }

    /* ── whole-document views (these lay out everything) ──────────────────── */

    private fun prepareAll() {
        for (c in parsed.spineIndices) prepareChapter(c)
    }

    /** `chapterPageOffset[c]` is the global index of chapter `c`'s first page. */
    private fun chapterPageOffsets(): IntArray = tableLock.withLock {
        val offsets = IntArray(parsed.spineCount + 1)
        for (c in parsed.spineIndices) offsets[c + 1] = offsets[c] + (chapterPages[c]?.size ?: 0)
        offsets
    }

    /**
     * Every page, in reading order. Lays out the whole book; for a big EPUB
     * that is the slow path the chapter API exists to avoid.
     */
    override val pages: List<EpubPage>
        get() {
            prepareAll()
            return buildList {
                for (c in parsed.spineIndices) {
                    for (r in renders(c)) add(EpubPage(r, this@EpubDocument, c))
                }
            }
        }

    /** Pages in the whole book. Lays it out; see [pages]. */
    override val pageCount: Int
        get() {
            prepareAll()
            return knownPageCount
        }

    /**
     * A copy of this book re-laid-out with new [settings], reusing the parse (no
     * re-unzip / re-parse of HTML, CSS or fonts). Use for reader controls that
     * change font size, margins or page size at runtime, much cheaper than [open].
     */
    public fun withSettings(settings: EpubSettings): EpubDocument = EpubDocument(parsed, settings)

    /** Shorthand for [withSettings] changing only the body font size (points). */
    public fun withFontSize(fontSize: Double): EpubDocument = withSettings(settings.copy(fontSize = fontSize))

    /** Shorthand for [withSettings] changing the page size, e.g. on resize / rotation. */
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
        prepareAll()
        val offsets = chapterPageOffsets()
        val map = HashMap<String, Int>()
        parsed.spinePaths.forEachIndexed { i, path ->
            val base = offsets[i]
            map.getOrPut(path) { base + localPageOf(i, docRoot(i).y) }
            collectAnchors(docRoot(i)) { id, y -> map.getOrPut("$path#$id") { base + localPageOf(i, y) } }
        }
        map
    }

    /** Chapter-local document y to a page index inside that chapter. */
    private fun localPageOf(chapter: Int, y: Double): Int {
        if (parsed.fixedLayout) return 0
        val starts = renders(chapter).map { it.startY }
        var p = 0
        for (k in starts.indices) if (starts[k] <= y + 1e-9) p = k else break
        return p
    }

    /** The chapter a zip path belongs to, or null when it is not on the spine. */
    private fun chapterOfPath(path: String): Int? =
        parsed.spinePaths.indexOfFirst { it == path }.takeIf { it >= 0 }

    /**
     * A reading position for an internal href (`chapter3.xhtml#part-two`), built
     * without laying anything out. Resolve it with [locate], which prepares that
     * one chapter. This is the cheap half of following a link.
     */
    public fun bookmarkOf(href: String): KiteBookmark.Flow? {
        val clean = href.trim()
        val chapter = chapterOfPath(clean.substringBefore('#')) ?: return null
        val fragment = clean.substringAfter('#', "").takeIf { it.isNotEmpty() }
        return KiteBookmark.Flow(chapter, charOffset = 0, fragment = fragment)
    }

    /**
     * A position that survives a re-flow: the chapter, plus how far into its
     * text the page starts. Change the font size and the same words keep the
     * same offset, even though they move to another page.
     */
    override fun bookmarkOf(location: KiteLocation): KiteBookmark.Flow {
        val pages = renders(location.chapter)
        var offset = 0
        for (i in 0 until location.page.coerceAtMost(pages.size)) offset += textLengthOf(pages[i])
        return KiteBookmark.Flow(location.chapter, offset)
    }

    /**
     * Where [bookmark] sits now. Prepares its chapter and no other. A fragment
     * wins over an offset; both clamp into range rather than failing.
     */
    override fun locate(bookmark: KiteBookmark): KiteLocation {
        val chapter = bookmark.chapter.coerceIn(0, (chapterCount - 1).coerceAtLeast(0))
        if (bookmark is KiteBookmark.Page) {
            return locationOf(bookmark.pageIndex) ?: KiteLocation(chapter, 0)
        }
        val flow = bookmark as KiteBookmark.Flow
        val pages = renders(chapter)
        if (pages.isEmpty()) return KiteLocation(chapter, 0)

        flow.fragment?.let { id ->
            val y = anchorYIn(chapter, id)
            if (y != null) return KiteLocation(chapter, localPageOf(chapter, y))
        }
        if (flow.charOffset <= 0) return KiteLocation(chapter, 0)
        var seen = 0
        for (i in pages.indices) {
            val length = textLengthOf(pages[i])
            if (flow.charOffset < seen + length || i == pages.lastIndex) return KiteLocation(chapter, i)
            seen += length
        }
        return KiteLocation(chapter, pages.lastIndex)
    }

    /** Chapter-local y of an element id, or null when the chapter has no such id. */
    private fun anchorYIn(chapter: Int, id: String): Double? {
        var found: Double? = null
        collectAnchors(docRoot(chapter)) { anchorId, y -> if (anchorId == id && found == null) found = y }
        return found
    }

    /** This page's index inside its own chapter, for [EpubPage.location]. */
    internal fun indexInChapter(chapter: Int, render: PageRender): Int =
        renders(chapter).indexOfFirst { it === render }.coerceAtLeast(0)

    /** Characters of text on one page, the unit [KiteBookmark.Flow.charOffset] counts in. */
    private fun textLengthOf(page: PageRender): Int =
        page.lines.sumOf { line -> line.runs.sumOf { it.glyphs.size } }

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

    private fun loadImage(zipPath: String): KiteImageData? =
        parsed.zip.read(zipPath)?.let { KiteImageData.fromEncodedImage(it) }

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
         * Open [bytes] at [settings]. Reads the container, the OPF and the table
         * of contents; chapters parse and lay out when something asks for them.
         *
         * @throws EpubFormatException when the bytes are not a readable EPUB,
         *   with a message naming the first structural failure (missing
         *   container.xml, missing OPF, empty spine, no readable documents).
         */
        public fun open(bytes: ByteArray, settings: EpubSettings): EpubDocument =
            EpubDocument(ParsedEpub.parse(bytes), settings)

        /** [open], but null instead of [EpubFormatException] on a malformed book. */
        public fun openOrNull(bytes: ByteArray, settings: EpubSettings = EpubSettings()): EpubDocument? =
            try { open(bytes, settings) } catch (_: EpubFormatException) { null }

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
 * y-min stored in [KiteRectangle.bottom]). [href] is either `zipPath#fragment`
 * (internal, resolve with the document's href navigation) or an external URL.
 */
public class EpubLink internal constructor(
    public val rect: io.github.yuroyami.kitepdf.core.KiteRectangle,
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

/** One reflowed EPUB page: paints backgrounds/borders, then text lines and images. */
public class EpubPage internal constructor(
    private val page: PageRender,
    private val doc: EpubDocument,
    /** The spine item this page belongs to. */
    public val chapter: Int = 0,
) : KitePage {

    /** Where this page sits: its chapter, and its index inside that chapter. */
    public val location: KiteLocation get() = KiteLocation(chapter, doc.indexInChapter(chapter, page))
    override val displayWidth: Double get() = page.pageWidth
    override val displayHeight: Double get() = page.pageHeight

    @Deprecated("Renamed to displayWidth, which every KitePage answers", ReplaceWith("displayWidth"))
    public val width: Double get() = displayWidth

    @Deprecated("Renamed to displayHeight, which every KitePage answers", ReplaceWith("displayHeight"))
    public val height: Double get() = displayHeight

    /** EPUB is y-down from top-left, so the base is a straight vertical flip. */
    override fun displayToDeviceBase(): KiteMatrix = KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, displayHeight)

    /**
     * Display-space (top-left, y-down) y of a document-space y. The single
     * source of the page's vertical mapping: painting ([renderTo]) flips it
     * to y-up, extraction ([textContent]) uses it directly.
     */
    private fun displayY(docY: Double): Double = page.margin + (docY - page.startY)

    override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) {
        if (page.vertical) {
            renderVerticalTo(canvas, deviceCtm)
            return
        }
        canvas.beginPage(displayWidth, displayHeight, deviceCtm)
        val margin = page.margin
        val startY = page.startY
        val bandBottom = startY + (displayHeight - 2 * margin)
        fun yUp(docY: Double) = displayHeight - displayY(docY)

        // Reader background (night mode): under everything, full page.
        doc.settings.backgroundColor?.let { bg ->
            val rect = KitePath.Builder().apply {
                moveTo(0.0, 0.0); lineTo(displayWidth, 0.0); lineTo(displayWidth, displayHeight); lineTo(0.0, displayHeight); close()
            }.build()
            canvas.fillPath(rect, deviceCtm, bg, evenOdd = false)
        }

        for (box in page.decoBoxes) paintBox(box, canvas, deviceCtm, margin, startY, bandBottom, ::yUp)

        for (line in page.lines) {
            val base = yUp(line.yTop + line.ascent)
            for (run in line.runs) {
                val tm = KiteMatrix.translation(margin + run.x, base + run.baselineShift)
                canvas.drawGlyphs(
                    run.glyphs, run.fontSize, unitsPerEm = run.unitsPerEm, hasOutlines = run.hasOutlines,
                    fontSpec = run.fontSpec, textToDevice = deviceCtm.concat(tm),
                    color = run.color, alpha = 1.0, blendMode = KiteBlendMode.Normal,
                )
            }
            // Inline images: bottom on the baseline, next to the text runs.
            for (im in line.images) {
                val svg = im.svg
                if (svg != null && svg.width > 0 && svg.height > 0) {
                    val m = KiteMatrix(
                        im.width / svg.width, 0.0, 0.0, -im.height / svg.height,
                        margin + im.x, base + im.height,
                    )
                    svg.render(canvas, deviceCtm.concat(m))
                } else if (im.image != null) {
                    val m = KiteMatrix(im.width, 0.0, 0.0, im.height, margin + im.x, base)
                    canvas.drawImage(im.image, deviceCtm.concat(m))
                }
            }
        }

        for (box in page.images) {
            val svg = box.svg
            if (svg != null) {
                // Map the SVG viewport (origin top-left, y-down) onto the box's device
                // rect (y-up): negative y-scale, translate to the box's top edge.
                val m = KiteMatrix(
                    box.drawWidth / svg.width, 0.0, 0.0, -box.drawHeight / svg.height,
                    margin + box.x, yUp(box.bottom) + box.drawHeight,
                )
                svg.render(canvas, deviceCtm.concat(m))
                continue
            }
            val img = box.image ?: continue
            // object-fit: cover. Scale to FILL the box preserving aspect,
            // center, and clip the overflow to the box rect.
            if (box.style.objectFit == io.github.yuroyami.kitepdf.epub.css.ObjectFit.COVER &&
                img.width > 0 && img.height > 0
            ) {
                val scale = maxOf(box.drawWidth / img.width, box.drawHeight / img.height)
                val dw = img.width * scale
                val dh = img.height * scale
                val dx = (box.drawWidth - dw) / 2.0
                val dy = (box.drawHeight - dh) / 2.0
                val clip = KitePath.Builder()
                    .apply { rectangle(margin + box.x, yUp(box.bottom), box.drawWidth, box.drawHeight) }
                    .build()
                canvas.pushClip(clip, deviceCtm, evenOdd = false)
                val m = KiteMatrix(dw, 0.0, 0.0, dh, margin + box.x + dx, yUp(box.bottom) + dy)
                canvas.drawImage(img, deviceCtm.concat(m))
                canvas.popClip()
                continue
            }
            val m = KiteMatrix(box.drawWidth, 0.0, 0.0, box.drawHeight, margin + box.x, yUp(box.bottom))
            canvas.drawImage(img, deviceCtm.concat(m))
        }
        canvas.endPage()
    }

    /**
     * Vertical-rl painting: the logical layout maps onto physical
     * columns advancing right-to-left, the inline axis running down the page.
     * Full-width glyphs stand upright, centred on the column's em axis;
     * everything else rotates 90 degrees clockwise around the shared baseline.
     */
    private fun renderVerticalTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) {
        canvas.beginPage(displayWidth, displayHeight, deviceCtm)
        val margin = page.margin
        val startY = page.startY
        val bandBottom = startY + (displayWidth - 2 * margin)
        // Logical block position -> the column's physical x (canvas space).
        fun colX(v: Double) = displayWidth - margin - (v - startY)

        doc.settings.backgroundColor?.let { bg ->
            val rect = KitePath.Builder().apply {
                moveTo(0.0, 0.0); lineTo(displayWidth, 0.0); lineTo(displayWidth, displayHeight); lineTo(0.0, displayHeight); close()
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
                            textToDevice = deviceCtm.concat(KiteMatrix.translation(x0, displayHeight - baseline)),
                            color = run.color, alpha = 1.0, blendMode = KiteBlendMode.Normal,
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
                        val tm = KiteMatrix(0.0, -1.0, 1.0, 0.0, xAxis, displayHeight - pen)
                        canvas.drawGlyphs(
                            run.glyphs.subList(k, j), run.fontSize, unitsPerEm = run.unitsPerEm,
                            hasOutlines = run.hasOutlines, fontSpec = run.fontSpec,
                            textToDevice = deviceCtm.concat(tm),
                            color = run.color, alpha = 1.0, blendMode = KiteBlendMode.Normal,
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
                    val m = KiteMatrix(0.0, -im.width / svg.width, -im.height / svg.height, 0.0, xAxis + im.height, displayHeight - top)
                    svg.render(canvas, deviceCtm.concat(m))
                } else if (im.image != null) {
                    val m = KiteMatrix(0.0, -im.width, im.height, 0.0, xAxis, displayHeight - top)
                    canvas.drawImage(im.image, deviceCtm.concat(m))
                }
            }
        }

        for (box in page.images) {
            val left = colX(box.bottom)
            val top = margin + box.x
            val svg = box.svg
            if (svg != null) {
                val m = KiteMatrix(0.0, -box.drawWidth / svg.width, -box.drawHeight / svg.height, 0.0, left + box.drawHeight, displayHeight - top)
                svg.render(canvas, deviceCtm.concat(m))
                continue
            }
            val img = box.image ?: continue
            val m = KiteMatrix(0.0, -box.drawWidth, box.drawHeight, 0.0, left, displayHeight - top)
            canvas.drawImage(img, deviceCtm.concat(m))
        }
        canvas.endPage()
    }

    /** Upright in vertical flow: the full-width (CJK) codepoints; the rest rotate. */
    private fun isUpright(g: io.github.yuroyami.kitepdf.core.font.TextGlyph): Boolean =
        g.text.isNotEmpty() && FontMetrics.isWide(g.text[0].code)

    /** [paintBox] under the vertical mapping: block spans columns, inline runs down. */
    private fun paintBoxVertical(
        box: LayoutBox, canvas: KiteCanvas, ctm: KiteMatrix, margin: Double,
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
            rectFill(canvas, ctm, colX(vTo), displayHeight - (uFrom + uLen), vTo - vFrom, uLen, color)
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
        box: LayoutBox, canvas: KiteCanvas, ctm: KiteMatrix, margin: Double,
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
        canvas: KiteCanvas, ctm: KiteMatrix, xDev: Double, w: Double, y0Doc: Double, y1Doc: Double,
        startY: Double, bandBottom: Double, yUp: (Double) -> Double, color: RgbColor,
    ) {
        val t = maxOf(y0Doc, startY); val b = minOf(y1Doc, bandBottom)
        if (b <= t) return
        rectFill(canvas, ctm, xDev, yUp(b), w, yUp(t) - yUp(b), color)
    }

    private fun rectFill(canvas: KiteCanvas, ctm: KiteMatrix, x: Double, yBottom: Double, w: Double, h: Double, color: RgbColor) {
        if (w <= 0.0 || h <= 0.0) return
        val path = KitePath.Builder().apply { rectangle(x, yBottom, w, h) }.build()
        canvas.fillPath(path, ctm, color, evenOdd = false, alpha = 1.0, blendMode = KiteBlendMode.Normal)
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
                        rect = io.github.yuroyami.kitepdf.core.KiteRectangle(
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
            // Display-space rect: y-min lives in [KiteRectangle.bottom] (see KiteStructuredText).
            bounds = io.github.yuroyami.kitepdf.core.KiteRectangle(edges.first(), top, edges.last(), top + line.height),
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
