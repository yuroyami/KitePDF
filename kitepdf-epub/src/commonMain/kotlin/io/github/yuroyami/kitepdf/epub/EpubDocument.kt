package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteDataUrl
import io.github.yuroyami.kitepdf.core.KiteCancellation
import io.github.yuroyami.kitepdf.svg.SvgImage
import io.github.yuroyami.kitepdf.svg.SvgTextContent

import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode

import io.github.yuroyami.kitepdf.core.zip.ZipReader

import io.github.yuroyami.kitepdf.epub.css.ComputedStyle
import io.github.yuroyami.kitepdf.epub.css.CssParser
import io.github.yuroyami.kitepdf.epub.css.Direction
import io.github.yuroyami.kitepdf.epub.css.Origin
import io.github.yuroyami.kitepdf.epub.css.ObjectFit
import io.github.yuroyami.kitepdf.epub.css.StyleResolver
import io.github.yuroyami.kitepdf.epub.css.StyleRule
import io.github.yuroyami.kitepdf.epub.css.TextOrientation
import io.github.yuroyami.kitepdf.epub.css.UnderlinePosition
import io.github.yuroyami.kitepdf.epub.css.UnderlineSide
import io.github.yuroyami.kitepdf.core.render.KiteFunction
import io.github.yuroyami.kitepdf.core.render.KiteColorSpace
import io.github.yuroyami.kitepdf.core.render.KiteShading
import io.github.yuroyami.kitepdf.epub.css.GradientStop
import io.github.yuroyami.kitepdf.epub.css.CssBackgroundLayer
import io.github.yuroyami.kitepdf.epub.css.CssBackgroundImage
import io.github.yuroyami.kitepdf.epub.css.grownRadii
import io.github.yuroyami.kitepdf.epub.css.innerRadii
import io.github.yuroyami.kitepdf.epub.css.roundedRect
import io.github.yuroyami.kitepdf.epub.css.transformMatrix
import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.KiteRole
import io.github.yuroyami.kitepdf.core.KiteReadingItem
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
import io.github.yuroyami.kitepdf.core.render.NoopCanvas
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

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
            // One list for each state of the book, so a panel that reads it on every
            // recomposition neither builds it nor flattens it again (#391).
            outlineCache?.let { (forResolved, list) -> if (forResolved == resolved) return list }
            fun href(e: TocEntry): String? =
                e.href?.let { if (e.fragment != null) "$it#${e.fragment}" else it }
            fun map(e: TocEntry): KiteOutlineItem = KiteOutlineItem(
                title = e.label,
                pageIndex = if (resolved) href(e)?.let { pageIndexOfHref(it) } else null,
                children = e.children.map(::map),
                target = href(e)?.let { bookmarkOf(it) },
            )
            return parsed.toc.entries.map(::map).also { outlineCache = resolved to it }
        }

    /** The last [outline], and whether the book was laid out when it was built. */
    @kotlin.concurrent.Volatile
    private var outlineCache: Pair<Boolean, List<KiteOutlineItem>>? = null

    public val pageWidth: Double get() = settings.pageWidth
    public val pageHeight: Double get() = settings.pageHeight
    public val fontSize: Double get() = settings.fontSize
    public val margin: Double get() = settings.margin

    private val contentWidth: Double get() = settings.pageWidth - 2 * settings.margin
    private val pageContentHeight: Double get() = settings.pageHeight - 2 * settings.margin

    /**
     * True for a pre-paginated (fixed-layout) book: one page per chapter, no reflow. A book that
     * mixes fixed and reflowable chapters is false, and [renditionOf] tells its chapters apart.
     */
    public val isFixedLayout: Boolean get() = parsed.allFixed

    /**
     * How [chapter] asks to be shown: the rendition properties of its spine entry, and the
     * book's for the rest. A chapter whose layout is [EpubLayout.PRE_PAGINATED] is one page at
     * the size it declares, and the others reflow, in the same book (#37).
     *
     * @throws IndexOutOfBoundsException when [chapter] is not a chapter of the book.
     */
    public fun renditionOf(chapter: Int): EpubRendition = parsed.renditions[chapter]

    /**
     * Whether [chapter] is scripted: its manifest item has the `scripted` property, or its
     * document has a `script` element (#40). Without a script handler such a chapter shows what
     * its markup shows, `noscript` content included; [EpubScriptSession], or `EpubScriptRunner`
     * of `kitepdf-javascript`, runs its scripts over this library's own layout (#235). Reads the
     * chapter's markup once, and does not lay it out.
     *
     * @throws IndexOutOfBoundsException when [chapter] is not a chapter of the book.
     */
    public fun isScripted(chapter: Int): Boolean = parsed.isScripted(chapter)

    /** The scripted chapters, in reading order (#40). Reads the markup of every chapter once. */
    public val scriptedChapters: List<Int> get() = parsed.spineIndices.filter(parsed::isScripted)

    /**
     * Whether [chapter] names a resource outside the book's container by an `http` or `https`
     * URL (EPUB 3.3, 3.6): its manifest item has the `remote-resources` property (EPUB 3.3, D.6.4),
     * or its markup or styles name one for an image, a video poster, a background or a font (#38).
     * Reads the chapter's markup and styles once, and does not lay it out.
     *
     * @throws IndexOutOfBoundsException when [chapter] is not a chapter of the book.
     */
    public fun hasRemoteResources(chapter: Int): Boolean = parsed.hasRemoteResources(chapter)

    /**
     * Fetches [chapter]'s remote resources through [EpubSettings.resourceFetcher], and returns once
     * each has landed or failed (#38). Only `https` URLs are fetched (EPUB Reading Systems 3.3, 3.3).
     * Returns at once without a fetcher, or for a chapter the book does not have.
     *
     * Layout never waits for the network on its own. An image whose width and height the markup
     * gives keeps that room, and its page paints the picture once its bytes land, which
     * [remoteArrivals] and [EpubPage.remoteVersion] announce. An image without both, and a font,
     * size the layout, so a chapter laid out before they land keeps their absence for the life of
     * this document, which keeps its pages and its page count. [layoutOnly] fetches just those.
     * [awaitLayoutResources] waits for them within a time limit, for a caller that lays [chapter]
     * out next. Without [layoutOnly], it fetches every one the chapter names, an `url()` of a rule
     * that matches no element included.
     *
     * The fetches belong to the book, not to the caller: cancelling this call stops the wait,
     * and the bytes still land for the next paint and the next document over the book.
     */
    public suspend fun fetchRemoteResources(chapter: Int, layoutOnly: Boolean = false) {
        val fetcher = settings.resourceFetcher ?: return
        if (chapter !in parsed.spineIndices) return
        val refs = parsed.remoteRefs(chapter)
        val pending = (if (layoutOnly) refs.layout else refs.all).mapNotNull { parsed.remote.request(it, fetcher) }
        for (fetch in pending) fetch.await()
    }

    /**
     * Fetches the remote resources that size [chapter]'s layout, its fonts and its images without
     * both a width and a height, and waits at most [wait] for them, for a caller that lays
     * [chapter] out next (#38). Returns once each has landed or failed, or once [wait] has passed,
     * and at once without [EpubSettings.resourceFetcher] or for a chapter the book does not have.
     *
     * A URL is waited for once per book. When a wait runs out, the URLs still in flight are left
     * out of every later wait, of this document and of any other over the book, so a font server
     * that hangs delays the first chapter that names the font by [wait], and not each chapter
     * after it (#492). Their fetches go on, and what lands shows in the next document over
     * the book, since this one keeps the layout it made without it. The viewer of
     * `kitepdf-compose-viewer` waits two seconds before it lays a chapter out.
     */
    public suspend fun awaitLayoutResources(chapter: Int, wait: Duration) {
        val fetcher = settings.resourceFetcher ?: return
        if (chapter !in parsed.spineIndices) return
        parsed.remote.awaitLayout(parsed.remoteRefs(chapter).layout, fetcher, wait)
    }

    /**
     * How many of the book's remote resources have landed, for every document over the book
     * (#38). A viewer collects it, and draws a page again when that page's
     * [EpubPage.remoteVersion] has moved.
     */
    public val remoteArrivals: StateFlow<Int> get() = parsed.remote.arrivals

    /**
     * How many times the book's scripts changed a chapter, for every document over the book
     * (#41). A viewer collects it, takes each chapter's page count again, since a change can add
     * or take pages, and draws a page again when its [EpubPage.chapterVersion] has moved. The
     * document that the scripts run over has the chapter's new pages ready when this moves.
     */
    public val chapterChanges: StateFlow<Int> get() = parsed.chapterChanges

    /** How many times scripts changed [chapter], which its pages answer as [EpubPage.chapterVersion] (#41). */
    internal fun chapterVersionOf(chapter: Int): Int = parsed.treeVersion(chapter)

    /**
     * The tree the layout reads for [chapter]: its document as parsed, or the last tree its scripts
     * gave it (#41). The caller must not change it: the layout may be reading it on another thread.
     */
    internal fun chapterTree(chapter: Int): KiteXmlNode.Element = parsed.layoutSpine(chapter).tree

    /**
     * [chapter]'s document as parsed, whatever its scripts made of it since: what a chapter's
     * scripts start from, as a browser loads a page from its file (#498). The caller must not
     * change it.
     */
    internal fun sourceChapterTree(chapter: Int): KiteXmlNode.Element = parsed.spine(chapter).tree

    /** The text of [chapter]'s document, which its scripts' DOM reads for what [sourceChapterTree] drops (#544, #546). */
    internal fun chapterText(chapter: Int): String = parsed.chapterText(chapter)

    /** Whether [other] is a document over the same book, whose scripts' changes it shares (#41). */
    internal fun sharesBookWith(other: EpubDocument): Boolean = other.parsed === parsed

    /** The book's blob URL store, which every document over it shares (#533). */
    internal val blobUrls: BlobUrlStore get() = parsed.blobs

    /** The window a script of [chapter] sees, in points: a fixed page's viewport, else a page's content box (#41). */
    internal fun scriptViewportOf(chapter: Int): Pair<Double, Double> =
        if (parsed.isFixed(chapter)) viewportOf(chapter) else contentWidth to pageContentHeight

    /**
     * The cascade's style of [el], an element of a script's own tree for [chapter], under the
     * rules the chapter was last laid out with (#41).
     */
    internal fun scriptStyleOf(chapter: Int, el: KiteXmlNode.Element): ComputedStyle {
        val sp = parsed.layoutSpine(chapter)
        val (width, height) = scriptViewportOf(chapter)
        val resolver = StyleResolver(
            sp.rules, settings.fontSize, width, directionFor(chapter), height,
            readerRules = readerRules, useAuthorCss = settings.usePublisherCss,
        )
        // The parser's root holds the document, and is no element of it.
        val chain = ArrayList<KiteXmlNode.Element>()
        var at: KiteXmlNode.Element? = el
        while (at != null && at.parent != null) { chain.add(at); at = at.parent }
        var style = resolver.initial()
        val ancestors = ArrayList<KiteXmlNode.Element>()
        for (e in chain.asReversed()) {
            style = resolver.compute(e, ancestors.toList(), style)
            ancestors.add(0, e)
        }
        return style
    }

    /**
     * Where [element], an element of the tree [chapter] was laid out from, is on the first page
     * that shows it, in display space, or null when no page does (#41).
     */
    internal fun scriptBoundsOf(chapter: Int, element: KiteXmlNode.Element): KiteRectangle? {
        for (page in pagesIn(chapter)) page.boundsOf(element)?.let { return it }
        return null
    }

    /**
     * Lays [chapter] out from [tree] from now on, as its scripts changed it, and tells every
     * viewer of the book once the new pages are ready (#41). [tree] belongs to the layout from
     * here: the caller makes a new one for the next change. This document lays the chapter out
     * on the caller's thread; another document over the book does it when it next needs it.
     */
    internal fun replaceChapterTree(chapter: Int, tree: KiteXmlNode.Element) {
        require(chapter in parsed.spineIndices) { "no chapter $chapter" }
        parsed.replaceTree(chapter, tree)
        prepareChapter(chapter)
        parsed.announceTreeChange()
    }

    /**
     * The fragment of [chapter]'s URL without its `#`, percent-encoded as a URL keeps it, or null
     * before the reader reached one (#550). The chapter's scripts read it as `location.hash`, and
     * the element it names is the one `:target` matches.
     */
    public fun fragmentOf(chapter: Int): String? {
        require(chapter in parsed.spineIndices) { "no chapter $chapter" }
        return parsed.fragment(chapter)
    }

    /**
     * Makes [fragment] the fragment of [chapter]'s URL, as a viewer does when the reader goes to a
     * place in the chapter through a link, the table of contents or a bookmark (#550). The element
     * it names becomes the chapter's target. A chapter whose style sheets use `:target` is laid
     * out again, and [chapterChanges] moves; any other chapter keeps its pages. A script session
     * takes the new fragment the next time [EpubScriptHandler.chapterOpened] runs for the chapter,
     * and its scripts get `popstate` and `hashchange` then. Lays out the chapter.
     */
    public fun setFragment(chapter: Int, fragment: String) {
        require(chapter in parsed.spineIndices) { "no chapter $chapter" }
        val normalized = normalizedFragment(fragment)
        if (!parsed.setFragment(chapter, normalized)) return
        // A script session marks the target in its own tree, which it gives the layout.
        if (parsed.treeHeld(chapter) || !usesTarget(chapter)) return
        replaceChapterTree(chapter, withTarget(parsed.layoutSpine(chapter).tree, normalized))
    }

    /** Whether a style rule of [chapter] holds a `:target`, so that a change of target restyles it (#550). */
    internal fun usesTarget(chapter: Int): Boolean = parsed.layoutSpine(chapter).rules.any { rule -> rule.selectors.any { it.usesTarget } }

    /** A script session holds [chapter]'s live tree while [held], and marks its target itself (#550). */
    internal fun holdTree(chapter: Int, held: Boolean): Unit = parsed.holdTree(chapter, held)

    /**
     * [chapter]'s media overlay: the clips of its synchronised narration, in document order, or
     * null when the chapter has none (#36). Parsed on first use, without laying the chapter out.
     * [locateFragment] finds each clip's text on the page.
     *
     * @throws IndexOutOfBoundsException when [chapter] is not a chapter of the book.
     */
    public fun mediaOverlayOf(chapter: Int): EpubMediaOverlay? = parsed.mediaOverlay(chapter)

    /**
     * Where the element that [href] names is on screen: the page that shows its first line, and
     * one rectangle per line of it on that page (#36). [href] is a zip path with a fragment, as
     * [EpubOverlayClip.textHref] and [EpubPage.links] give it. An element without text of its own
     * gives its page from the anchor map, with no rectangle. Null when the book has no such
     * chapter or element. Lays out the chapter.
     */
    public fun locateFragment(href: String): EpubFragmentBox? {
        val id = href.substringAfter('#', "").takeIf { it.isNotEmpty() } ?: return null
        val chapter = chapterOfPath(href.substringBefore('#')) ?: return null
        for ((index, page) in pagesIn(chapter).withIndex()) {
            val rects = page.rectsOf(id)
            if (rects.isNotEmpty()) return EpubFragmentBox(KiteLocation(chapter, index), rects)
        }
        val summary = summaryOf(chapter) ?: return null
        val y = anchorYIn(summary, id) ?: return null
        return EpubFragmentBox(KiteLocation(chapter, localPageOf(summary, y)), emptyList())
    }

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
            // A forced text colour could sit on an author background of the same
            // lightness, so the reader drops backgrounds with it (#253).
            settings.textColor?.let { append("*{color:${cssColor(it)};background-color:transparent;background:transparent}") }
            settings.justify?.let { append("*{text-align:${if (it) "justify" else "start"};text-align-last:auto}") }
            settings.hyphenate?.let { append("*{hyphens:${if (it) "auto" else "manual"}}") }
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

    /**
     * What a laid-out chapter keeps for good: enough to count its pages, find
     * an anchor, and turn a location into a bookmark and back, without the
     * pages themselves. A few numbers per page, however long the chapter.
     */
    private class ChapterSummary(
        val pageCount: Int,
        /** Document-space top of each page, for anchor and offset lookups. */
        val startYs: DoubleArray,
        /** Characters of text on each page, the unit a flow bookmark counts in. */
        val textLengths: IntArray,
        /** Element ids in the chapter with their document-space y, in tree order. */
        val anchors: List<Pair<String, Double>>,
        /** Document-space y of the chapter root, where a link to the spine item lands. */
        val rootY: Double,
        /** The size every page of the chapter has, so a page answers it without its pages. */
        val pageWidth: Double,
        val pageHeight: Double,
        /** What the chapter's pages cost in memory, by [estimateBytes]. */
        val bytes: Long,
    )

    /** A chapter whose pages are in memory, with what was derived from them. */
    private class LiveChapter(
        val pages: List<PageRender>,
        /** What [pages] cost in memory, by [estimateBytes]. */
        val bytes: Long,
        /** Structured text per page, built on first use, dropped with the pages. */
        val structured: Array<KiteStructuredText?>,
        /** Link rects per page, built on first use, dropped with the pages. */
        val links: Array<List<EpubLink>?>,
        /** The tick of the last use; the smallest is the first to drop. 0 for never used. */
        var lastUse: Long,
    )

    /** One per chapter, null until that chapter has been laid out once. */
    private val summaries = arrayOfNulls<ChapterSummary>(parsed.spineCount)

    /**
     * The chapters whose pages are in memory. A slot goes back to null when
     * [EpubSettings.layoutCacheBytes] needs the room; the chapter is laid out
     * again on its next use (#218).
     */
    private val live = arrayOfNulls<LiveChapter>(parsed.spineCount)

    /** The estimated bytes the live chapters hold, kept under the budget. */
    private var liveBytes = 0L

    /** The chapters on screen, which the budget never drops (#377). */
    private var kept: Set<Int> = emptySet()

    /** Ticks on every use of a chapter's pages, to stamp [LiveChapter.lastUse]. */
    private var useClock = 0L

    /**
     * One [EpubPage] per location, created with the chapter's summary and kept
     * for the life of the document. A viewer keys its bitmap cache and its
     * state producers on the page object, so a fresh object per lookup was a
     * cache miss and a re-raster on every recomposition (#221).
     */
    private val pageObjects = arrayOfNulls<List<EpubPage>>(parsed.spineCount)

    /**
     * The tree version of [ParsedEpub.treeVersion] that each chapter's summary and pages were laid
     * out from. A chapter whose scripts gave it a newer tree is laid out again (#41).
     */
    private val laidVersions = IntArray(parsed.spineCount)

    // Box tree per spine: depends on font size + column width, so it is built
    // per layout, including the layout that brings a dropped chapter back. The
    // DOM and CSS it is built from are parsed once per chapter and live in
    // ParsedEpub, shared by every re-layout. The tree is most of what a
    // laid-out chapter weighs, so it is never kept past pagination.
    private fun buildDocRoot(chapter: Int): BlockBox {
        val build = docRootBuild(chapter)
        while (!build.step()) Unit
        return checkNotNull(build.box)
    }

    /** [buildDocRoot] as steps, each the next child of a block element (#389). */
    private fun docRootBuild(chapter: Int): BoxBuilder.Run {
        val sp = parsed.layoutSpine(chapter)
        val (layoutWidth, layoutHeight) =
            if (parsed.isFixed(chapter)) viewportOf(chapter) else contentWidth to pageContentHeight
        val resolver = StyleResolver(
            sp.rules, settings.fontSize, layoutWidth, directionFor(chapter), layoutHeight,
            readerRules = readerRules, useAuthorCss = settings.usePublisherCss,
        )
        return BoxBuilder(resolver, sp.path, parsed::mediaTypeOf, parsed.tracksElements(chapter)) { href -> resolvePath(sp.docDir, href) }.start(sp.tree)
    }

    /**
     * One chapter's box tree under a fresh root. Layout starts each chapter at
     * y = 0, so a chapter's geometry never depends on the chapters before it.
     */
    private fun chapterRoot(chapter: Int, docRoot: BlockBox): BlockBox =
        BlockBox(ComputedStyle.initial(settings.fontSize, direction = directionFor(chapter)), listOf(docRoot))

    /**
     * The writing mode of the first reflowable chapter, if it is a vertical one. Each chapter
     * lays out in its own mode, which [verticalModeOf] reads from its box tree (#507).
     * Fixed-layout chapters stay on the pre-paginated path regardless.
     */
    internal val verticalMode: io.github.yuroyami.kitepdf.epub.css.WritingMode? by lazy {
        val first = parsed.spineIndices.firstOrNull { !parsed.isFixed(it) } ?: return@lazy null
        verticalModeOf(buildDocRoot(first))
    }

    /**
     * The writing mode a chapter's root resolves, if it is a vertical one: `vertical-rl` is
     * Japanese tategaki, columns running right to left, and `vertical-lr` runs its columns the
     * other way. A document's principal writing mode is its own, from its root element or, in
     * HTML, its body (CSS Writing Modes 3, 8, #507).
     */
    private fun verticalModeOf(docRoot: LayoutBox): io.github.yuroyami.kitepdf.epub.css.WritingMode? {
        // The box of the document node has the initial style; the html element's computed style
        // sits one level down and body's below that, so walk the first-child chain a few levels.
        var box: LayoutBox? = docRoot
        var depth = 0
        while (box != null && depth < 4) {
            val s = when (box) {
                is BlockBox -> box.style
                is TextBlockBox -> box.style
                else -> null
            }
            val mode = s?.writingMode
            if (mode == io.github.yuroyami.kitepdf.epub.css.WritingMode.VERTICAL_RL ||
                mode == io.github.yuroyami.kitepdf.epub.css.WritingMode.VERTICAL_LR
            ) return mode
            box = (box as? BlockBox)?.children?.firstOrNull()
            depth++
        }
        return null
    }

    internal val isVertical: Boolean get() = verticalMode != null

    /** True for `vertical-lr`: vertical text whose columns advance left to right. */
    internal val isVerticalLr: Boolean
        get() = verticalMode == io.github.yuroyami.kitepdf.epub.css.WritingMode.VERTICAL_LR

    private fun fixedSpine(chapter: Int): FixedSpine? {
        if (!parsed.isFixed(chapter)) return null
        val (w, h) = viewportOf(chapter)
        return FixedSpine(buildDocRoot(chapter), w, h)
    }

    /** A fixed-layout chapter's page size: what it declares, else the reader's. */
    private fun viewportOf(chapter: Int): Pair<Double, Double> =
        parsed.layoutSpine(chapter).viewport?.resolve(settings.pageWidth, settings.pageHeight) ?: (settings.pageWidth to settings.pageHeight)

    /**
     * The document's language: the first chapter's own `lang`, else the OPF
     * `dc:language`. Null falls back to the en-US patterns in [BoxLayout].
     */
    internal val documentLanguage: String? by lazy { languageFor(0) }

    /**
     * The hyphenation language for one chapter: the `lang` / `xml:lang` on
     * that chapter's `<html>` or `<body>` (the parser folds both onto the
     * `lang` key), else the OPF `dc:language`. Each spine document carries its
     * own, so a bilingual anthology hyphenates each chapter with its own
     * patterns. The chapter is already parsed by every caller (pagination has
     * its tree in hand), so this reads nothing new.
     */
    internal fun languageFor(chapter: Int): String? {
        val tree = if (chapter !in 0 until parsed.spineCount) null else parsed.spine(chapter).tree
        val html = tree?.children?.filterIsInstance<KiteXmlNode.Element>()
            ?.firstOrNull { it.tag == "html" }
        val body = html?.children?.filterIsInstance<KiteXmlNode.Element>()
            ?.firstOrNull { it.tag == "body" }
        return html?.attrs?.get("lang")?.takeIf { it.isNotBlank() }
            ?: html?.attrs?.get("xml:lang")?.takeIf { it.isNotBlank() }
            ?: body?.attrs?.get("lang")?.takeIf { it.isNotBlank() }
            ?: body?.attrs?.get("xml:lang")?.takeIf { it.isNotBlank() }
            ?: parsed.metadata.language?.takeIf { it.isNotBlank() }
    }

    /**
     * The direction [chapter] reads in where it declares none, by `dir` or CSS: its language's,
     * else the book's. The spine's page progression orders the pages and has no say (#512).
     */
    private fun directionFor(chapter: Int): Direction =
        if (isRightToLeftLanguage(languageFor(chapter))) Direction.RTL else Direction.LTR

    /**
     * The faces [chapter] lays out with: the book's embedded fonts, plus any
     * `@font-face` the chapter declares in its own inline `<style>`. Keeping the
     * inline ones chapter-local is what makes a chapter's layout independent of
     * which chapters were laid out before it.
     */
    private fun fontsFor(chapter: Int): FontRegistry {
        val spine = parsed.layoutSpine(chapter)
        if (parsed.hasRemoteFonts(chapter)) {
            // A remote face is what the chapter's first layout found, for good, so a layout
            // after the budget dropped the chapter shapes its text the same way (#38).
            val faces = (parsed.fontFaceRules + spine.faceRules).mapNotNull { (rule, dir) ->
                parsed.loadFace(rule, dir) { url -> layoutBytes(chapter, url) }
            }
            return FontRegistry(faces)
        }
        val local = spine.localFaces
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
    private fun paginateChapter(chapter: Int): Laid {
        val layout = ChapterLayout(chapter)
        while (!layout.step()) Unit
        return layout.laid
    }

    /**
     * [paginateChapter] as steps: the parse of the chapter, each child of a block element as its
     * box tree builds, each child of a block in normal flow as it lays out, and the pages.
     * [prepareChapter] with a checkpoint calls the checkpoint between two steps, so that a thread
     * that must draw frames can stop there (#389).
     */
    private inner class ChapterLayout(private val chapter: Int) {
        private val fonts = fontsFor(chapter)
        /** A chapter that scripts run in keeps the boxes a tap can land on (#41). */
        private val hits = parsed.tracksElements(chapter)
        /** Each picture once per layout, however many elements name it (#560). */
        private val images = HashMap<String, KiteImageData?>()
        private val svgs = HashMap<String, SvgImage?>()
        private val image: (String) -> KiteImageData? = { path ->
            if (path in images) images[path] else loadImage(path) { url -> layoutBytes(chapter, url) }.also { images[path] = it }
        }
        private val svg: (String) -> SvgImage? = { path ->
            if (path in svgs) svgs[path] else loadSvg(path) { url -> layoutBytes(chapter, url) }.also { svgs[path] = it }
        }
        private var stage = 0
        private var build: BoxBuilder.Run? = null
        private var docRoot: BlockBox? = null
        private var root: BlockBox? = null
        private var run: BoxLayout.Run? = null
        /** The chapter's own writing mode, once its box tree is built (#507). */
        private var vertical = false
        private var verticalLr = false

        /** The chapter's pages, once [step] has returned true. */
        lateinit var laid: Laid

        /** Takes the next step, and returns true once [laid] is set. */
        fun step(): Boolean {
            when (stage) {
                0 -> {
                    val spine = fixedSpine(chapter)
                    if (spine != null) {
                        BoxLayout(image, svg, spine.height, fonts, languageFor(chapter), settings.lineHeightScale, isSvg = parsed::namesSvg)
                            .layout(spine.root, spine.width, spine.height)
                        laid = Laid(listOf(Paginator.paginateFixed(spine.root, spine.width, spine.height, hits)), spine.root)
                        return true
                    }
                    // Parsing the chapter's document and its style sheets, once for the book.
                    parsed.spine(chapter)
                }
                1 -> build = docRootBuild(chapter)
                2 -> if (!checkNotNull(build).step()) return false
                3 -> {
                    val built = checkNotNull(checkNotNull(build).box)
                    val mode = verticalModeOf(built)
                    vertical = mode != null
                    verticalLr = mode == io.github.yuroyami.kitepdf.epub.css.WritingMode.VERTICAL_LR
                    // Vertical writing swaps the budgets: the inline (line-length) budget is
                    // the page content HEIGHT and each page holds contentWidth of columns.
                    val inlineBudget = if (vertical) pageContentHeight else contentWidth
                    val blockBudget = if (vertical) contentWidth else pageContentHeight
                    val chapterRoot = chapterRoot(chapter, built)
                    build = null
                    docRoot = built
                    root = chapterRoot
                    run = BoxLayout(
                        image, svg, blockBudget, fonts, languageFor(chapter),
                        settings.lineHeightScale, vertical = vertical, isSvg = parsed::namesSvg,
                    ).start(chapterRoot, inlineBudget, blockBudget)
                }
                4 -> if (!checkNotNull(run).step()) return false
                else -> {
                    val pages = Paginator.paginate(
                        checkNotNull(root), settings.pageWidth, settings.pageHeight, settings.margin,
                        vertical = vertical, verticalLr = verticalLr, hits = hits,
                    )
                    // A spine document with nothing to paint contributed no page when the
                    // whole book shared one box tree. Keep that: do not invent a blank page.
                    // An embedded document is something to show, even with no fallback (#41).
                    // So is a chapter that scripts run in, which they may fill once it shows (#41).
                    val blank = !hits && pages.size == 1 && pages[0].lines.isEmpty() && pages[0].images.isEmpty() &&
                        pages[0].decoBoxes.isEmpty() && pages[0].embedBoxes.isEmpty()
                    laid = Laid(if (blank) emptyList() else pages, checkNotNull(docRoot))
                    return true
                }
            }
            stage++
            return false
        }
    }

    /** One chapter's pages with the box tree they came from, for its anchors. */
    private class Laid(val pages: List<PageRender>, val root: BlockBox)

    /* ── the chapter API ──────────────────────────────────────────────────── */

    override val chapterCount: Int get() = parsed.spineCount

    override fun isChapterReady(chapter: Int): Boolean {
        if (chapter !in parsed.spineIndices) return false
        // A chapter whose scripts gave it a new tree is laid out again (#41).
        val version = parsed.treeVersion(chapter)
        return tableLock.withLock { summaries[chapter] != null && laidVersions[chapter] == version }
    }

    /**
     * Lays out one chapter. This is where a book's time goes, so it is also the
     * only thing a reader has to wait for: opening at chapter 20 prepares
     * chapter 20, not chapters 0 to 20.
     *
     * Pages laid out this way count as never used: a loader that walks the
     * whole book behind the reader must not push the chapter being read out
     * of the budget, so these are the first to drop.
     */
    override fun prepareChapter(chapter: Int) {
        if (chapter !in parsed.spineIndices) return
        if (isChapterReady(chapter)) return
        // Per-chapter lock: two callers for one chapter share a single layout,
        // two callers for different chapters do not wait on each other.
        chapterLocks[chapter].withLock {
            if (isChapterReady(chapter)) return
            // Read before the layout reads the tree: a tree that changes meanwhile leaves the chapter stale, not wrong (#41).
            val version = parsed.treeVersion(chapter)
            publishPrepared(chapter, paginateChapter(chapter), version)
        }
    }

    /**
     * Lays [chapter] out a step at a time, each step the parse of the chapter, its box tree, a
     * child of a block in normal flow, or its pages, and calls [checkpoint] between two (#389).
     * A viewer on a thread that must draw frames suspends in [checkpoint] once a frame's share
     * of time is used, and the layout goes on after the frame. No lock is held across a
     * checkpoint, so two callers for one chapter may each lay it out; the first to finish
     * publishes its pages, as [prepareChapter] would.
     */
    override suspend fun prepareChapter(chapter: Int, checkpoint: suspend () -> Unit) {
        if (chapter !in parsed.spineIndices) return
        if (isChapterReady(chapter)) return
        val version = parsed.treeVersion(chapter)
        val layout = ChapterLayout(chapter)
        while (!layout.step()) checkpoint()
        chapterLocks[chapter].withLock {
            if (isChapterReady(chapter)) return
            publishPrepared(chapter, layout.laid, version)
        }
    }

    /**
     * Under [chapterLocks]: makes [laid], laid out from tree [version], [chapter]'s pages, counted
     * as never used so the budget drops them first.
     */
    private fun publishPrepared(chapter: Int, laid: Laid, version: Int) {
        val summary = summarize(laid)
        tableLock.withLock {
            summaries[chapter] = summary
            pageObjects[chapter] = pageObjectsFor(chapter, summary, pageObjects[chapter])
            laidVersions[chapter] = version
            publishLive(chapter, laid.pages, summary, lastUse = 0L)
        }
    }

    /**
     * [chapter]'s pages, in memory. Lays the chapter out when they are not,
     * whether never or dropped by the budget, and stamps them used so the
     * budget drops other chapters first.
     */
    private fun livePages(chapter: Int): List<PageRender> {
        fun current(version: Int): List<PageRender>? = tableLock.withLock {
            live[chapter]?.takeIf { laidVersions[chapter] == version }?.let { it.lastUse = ++useClock; it.pages }
        }
        current(parsed.treeVersion(chapter))?.let { return it }
        chapterLocks[chapter].withLock {
            val version = parsed.treeVersion(chapter)
            current(version)?.let { return it }
            val laid = paginateChapter(chapter)
            // The summary and the page objects exist from the first layout of a tree; only a
            // chapter that was never prepared, or whose scripts changed its tree, builds them here.
            val known = tableLock.withLock { summaries[chapter]?.takeIf { laidVersions[chapter] == version } }
            val fresh = if (known == null) summarize(laid) else null
            return tableLock.withLock {
                val summary = if (fresh != null) {
                    summaries[chapter] = fresh
                    pageObjects[chapter] = pageObjectsFor(chapter, fresh, pageObjects[chapter])
                    fresh
                } else {
                    checkNotNull(known)
                }
                laidVersions[chapter] = version
                publishLive(chapter, laid.pages, summary, lastUse = ++useClock)
                laid.pages
            }
        }
    }

    /**
     * One permanent page object per page of [chapter], sized from its summary. A layout of a new
     * tree keeps each object of [previous] whose page is still there at the same size, so a
     * viewer's caches keyed on it stay (#41).
     */
    private fun pageObjectsFor(chapter: Int, summary: ChapterSummary, previous: List<EpubPage>? = null): List<EpubPage> =
        List(summary.pageCount) { i ->
            previous?.getOrNull(i)?.takeIf { it.hasSize(summary.pageWidth, summary.pageHeight) }
                ?: EpubPage(this, chapter, i, summary.pageWidth, summary.pageHeight)
        }

    /** Under [tableLock]: installs [pages] as [chapter]'s live pages, then trims to the budget. */
    private fun publishLive(chapter: Int, pages: List<PageRender>, summary: ChapterSummary, lastUse: Long) {
        live[chapter]?.let { liveBytes -= it.bytes }
        live[chapter] = LiveChapter(pages, summary.bytes, arrayOfNulls(pages.size), arrayOfNulls(pages.size), lastUse)
        liveBytes += summary.bytes
        trimToBudget()
    }

    /**
     * Keeps [chapters] in memory past the budget, so that a gesture or a draw on the screen
     * does not lay a chapter out again on the UI thread (#377). The chapters kept before are
     * dropped again when the budget needs the room.
     */
    override fun keepChapters(chapters: Set<Int>) {
        tableLock.withLock {
            kept = chapters.toSet()
            trimToBudget()
        }
    }

    /**
     * Under [tableLock]: drops the least recently used chapters until the live
     * set fits [EpubSettings.layoutCacheBytes]. One chapter always stays,
     * however large: a book that is one spine document has nothing smaller
     * to drop. A chapter in [kept] stays too, even past the budget.
     */
    private fun trimToBudget() {
        val budget = settings.layoutCacheBytes
        while (liveBytes > budget) {
            var victim = -1
            var oldest = Long.MAX_VALUE
            var count = 0
            for (c in parsed.spineIndices) {
                val entry = live[c] ?: continue
                count++
                if (c in kept) continue
                if (entry.lastUse < oldest) {
                    oldest = entry.lastUse
                    victim = c
                }
            }
            if (count <= 1 || victim < 0) return
            liveBytes -= live[victim]?.bytes ?: 0L
            live[victim] = null
        }
    }

    /** The summary of [chapter], laying it out first. Null off the spine. */
    private fun summaryOf(chapter: Int): ChapterSummary? {
        if (chapter !in parsed.spineIndices) return null
        prepareChapter(chapter)
        return tableLock.withLock { summaries[chapter] }
    }

    /** What a chapter keeps for good, read off its pages and box tree once. */
    private fun summarize(laid: Laid): ChapterSummary {
        val pages = laid.pages
        val anchors = ArrayList<Pair<String, Double>>()
        collectAnchors(laid.root) { id, y -> anchors.add(id to y) }
        return ChapterSummary(
            pageCount = pages.size,
            startYs = DoubleArray(pages.size) { pages[it].startY },
            textLengths = IntArray(pages.size) { textLengthOf(pages[it]) },
            anchors = anchors,
            rootY = laid.root.y,
            pageWidth = pages.firstOrNull()?.pageWidth ?: settings.pageWidth,
            pageHeight = pages.firstOrNull()?.pageHeight ?: settings.pageHeight,
            bytes = estimateBytes(pages),
        )
    }

    /**
     * What [pages] hold in memory: [BYTES_PER_GLYPH] per glyph (measured on
     * the JVM over the corpus, an estimate everywhere), the samples of every
     * image on them and the size of every SVG file they draw, each counted
     * once, and [BYTES_PER_PAGE] for each page. A chapter of SVG images
     * counted as nothing, so no budget ever dropped it (#396).
     */
    private fun estimateBytes(pages: List<PageRender>): Long {
        var glyphs = 0L
        var imageBytes = 0L
        val seen = HashSet<KiteImageData>()
        val seenSvg = HashSet<String>()
        fun count(image: KiteImageData?) {
            if (image != null && seen.add(image)) {
                imageBytes += image.encodedBytes.size.toLong() + (image.pixelBytes?.size ?: 0)
            }
        }
        // A floor for the tree the parse of an SVG file keeps: its size in the book.
        fun countSvg(zipPath: String) {
            if (zipPath.isNotEmpty() && seenSvg.add(zipPath)) imageBytes += parsed.sizeOf(zipPath)
        }
        for (page in pages) {
            for (line in page.lines) {
                for (run in line.runs) glyphs += run.glyphs.size
                for (m in line.maths) glyphs += m.box.items.size
                for (im in line.images) {
                    count(im.image)
                    if (im.svg != null) countSvg(im.zipPath)
                }
            }
            for (box in page.images) {
                count(box.image)
                if (box.svg != null) countSvg(box.zipPath)
            }
        }
        return glyphs * BYTES_PER_GLYPH + imageBytes + pages.size * BYTES_PER_PAGE
    }

    /** Whether [chapter]'s document has been read and parsed yet. */
    internal fun isChapterParsed(chapter: Int): Boolean = parsed.isSpineParsed(chapter)

    /** How many stylesheet files this book has parsed, however many chapters link them. */
    internal val stylesheetsParsed: Int get() = parsed.sheetsParsed

    /** How many font files this book has parsed, however many chapters declare them. */
    internal val fontFilesParsed: Int get() = parsed.fontFilesParsed

    /** Whether [chapter]'s pages are in memory right now. For tests and diagnostics. */
    internal fun isChapterLive(chapter: Int): Boolean =
        chapter in parsed.spineIndices && tableLock.withLock { live[chapter] } != null

    /** How many chapters hold their pages in memory. For tests and diagnostics. */
    internal val liveChapterCount: Int
        get() = tableLock.withLock { live.count { it != null } }

    /** [chapter]'s page objects, laying it out first. Empty for a chapter off the spine. */
    private fun pagesIn(chapter: Int): List<EpubPage> {
        if (summaryOf(chapter) == null) return emptyList()
        return tableLock.withLock { pageObjects[chapter] } ?: emptyList()
    }

    override fun pageCountIn(chapter: Int): Int = summaryOf(chapter)?.pageCount ?: 0

    override fun page(location: KiteLocation): EpubPage {
        val pages = pagesIn(location.chapter)
        return pages.getOrNull(location.page)
            ?: throw IndexOutOfBoundsException(
                "no page $location: chapter ${location.chapter} has ${pages.size} page(s)",
            )
    }

    /* ── what a page reads from its chapter ───────────────────────────────── */

    /** Brings [chapter]'s pages into memory, laying the chapter out again if the budget dropped it. */
    internal fun loadChapter(chapter: Int) {
        if (chapter in parsed.spineIndices) livePages(chapter)
    }

    /** Page [index] of [chapter] to paint, in memory, laid out again if it was dropped. */
    internal fun render(chapter: Int, index: Int): PageRender {
        val pages = livePages(chapter)
        pages.getOrNull(index)?.let { return it }
        // A script can take pages from its chapter while a viewer still shows the last of them,
        // which then shows nothing until the viewer has the new page count (#41).
        if (parsed.treeVersion(chapter) > 0) {
            return PageRender(0.0, emptyList(), emptyList(), emptyList(), settings.pageWidth, settings.pageHeight, settings.margin)
        }
        throw IllegalStateException("chapter $chapter laid out to ${pages.size} page(s), page $index expected")
    }

    /**
     * A per-page value derived from its render, kept with the live chapter so
     * it is built once and dropped with the pages. A chapter dropped between
     * the build and the store hands the value back unshared, which is correct
     * and merely unlucky.
     */
    private fun <T : Any> derived(
        chapter: Int,
        index: Int,
        slot: (LiveChapter) -> Array<T?>,
        build: (PageRender) -> T,
    ): T {
        tableLock.withLock { live[chapter]?.let { slot(it).getOrNull(index) } }?.let { return it }
        val built = build(render(chapter, index))
        return tableLock.withLock {
            val entry = live[chapter] ?: return@withLock built
            val cells = slot(entry)
            // A page past the end of a chapter that a script shortened keeps nothing (#41).
            if (index !in cells.indices) return@withLock built
            cells[index] ?: built.also { cells[index] = it }
        }
    }

    internal fun structuredTextOf(chapter: Int, index: Int, build: (PageRender) -> KiteStructuredText): KiteStructuredText =
        derived(chapter, index, { it.structured }, build)

    internal fun linksOf(chapter: Int, index: Int, build: (PageRender) -> List<EpubLink>): List<EpubLink> =
        derived(chapter, index, { it.links }, build)

    override val isComplete: Boolean
        get() = tableLock.withLock { summaries.all { it != null } }

    override val knownPageCount: Int
        get() = tableLock.withLock { summaries.sumOf { it?.pageCount ?: 0 } }

    /**
     * The global index of [location]. Null while any earlier chapter is still
     * unlaid, because the pages before it have not been counted yet.
     */
    override fun pageIndexOf(location: KiteLocation): Int? = tableLock.withLock {
        if (location.chapter !in parsed.spineIndices) return@withLock null
        var offset = 0
        for (c in 0 until location.chapter) offset += (summaries[c] ?: return@withLock null).pageCount
        val own = summaries[location.chapter] ?: return@withLock null
        if (location.page !in 0 until own.pageCount) return@withLock null
        offset + location.page
    }

    /** The location of a global index, or null past what is laid out so far. */
    override fun locationOf(pageIndex: Int): KiteLocation? = tableLock.withLock {
        if (pageIndex < 0) return@withLock null
        var remaining = pageIndex
        for (c in parsed.spineIndices) {
            val own = summaries[c] ?: return@withLock null
            if (remaining < own.pageCount) return@withLock KiteLocation(c, remaining)
            remaining -= own.pageCount
        }
        null
    }

    /**
     * The location of the book's page [pageIndex], laying out chapters in order until one holds
     * it. Without the layout the index names no page, and it read as the start of the book (#349).
     * An index past the end gives the last page.
     */
    private fun pageLocation(pageIndex: Int): KiteLocation {
        var remaining = pageIndex.coerceAtLeast(0)
        var last = KiteLocation(0, 0)
        for (c in parsed.spineIndices) {
            val pages = summaryOf(c)?.pageCount ?: continue
            if (remaining < pages) return KiteLocation(c, remaining)
            remaining -= pages
            if (pages > 0) last = KiteLocation(c, pages - 1)
        }
        return last
    }

    /* ── whole-document views (these lay out everything) ──────────────────── */

    private fun prepareAll() {
        for (c in parsed.spineIndices) prepareChapter(c)
    }

    /** `chapterPageOffset[c]` is the global index of chapter `c`'s first page. */
    private fun chapterPageOffsets(): IntArray = tableLock.withLock {
        val offsets = IntArray(parsed.spineCount + 1)
        for (c in parsed.spineIndices) offsets[c + 1] = offsets[c] + (summaries[c]?.pageCount ?: 0)
        offsets
    }

    /**
     * Every page, in reading order. Lays out the whole book; for a big EPUB
     * that is the slow path the chapter API exists to avoid. The objects are
     * the ones [page] answers, and only what the budget keeps stays in memory.
     */
    override val pages: List<EpubPage>
        get() {
            prepareAll()
            return buildList { for (c in parsed.spineIndices) addAll(pagesIn(c)) }
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
     * Each hit carries its chapter/page location in this layout and its global
     * page index. Iterating the sequence prepares the whole book, as [pages] does.
     */
    public fun search(needle: String, ignoreCase: Boolean = true): Sequence<KiteSearchHit> = sequence {
        if (needle.isEmpty()) return@sequence
        for ((i, page) in pages.withIndex()) {
            yieldAll(page.textContent().search(needle, ignoreCase, pageIndex = i, location = page.location))
        }
    }

    /* ── href -> page navigation ─────────────────────────────────────────── */

    /**
     * `spinePath` and `spinePath#id` -> zero-based page index. Spine starts map
     * to the page holding the spine root's top; anchors to the page holding
     * their box's top (inline ids anchor to their enclosing block).
     */
    private var anchorCache: Pair<Int, Map<String, Int>>? = null

    /** [buildAnchorPages], built again once scripts gave a chapter a new tree (#41). */
    private val anchorPages: Map<String, Int>
        get() {
            val revision = parsed.treeRevision
            tableLock.withLock { anchorCache?.takeIf { it.first == revision } }?.let { return it.second }
            val built = buildAnchorPages()
            tableLock.withLock { anchorCache = revision to built }
            return built
        }

    private fun buildAnchorPages(): Map<String, Int> {
        prepareAll()
        val offsets = chapterPageOffsets()
        val map = HashMap<String, Int>()
        parsed.spinePaths.forEachIndexed { i, path ->
            val summary = tableLock.withLock { summaries[i] } ?: return@forEachIndexed
            val base = offsets[i]
            map.getOrPut(path) { base + localPageOf(summary, summary.rootY) }
            for ((id, y) in summary.anchors) map.getOrPut("$path#$id") { base + localPageOf(summary, y) }
        }
        return map
    }

    /** Chapter-local document y to a page index inside that chapter. */
    private fun localPageOf(summary: ChapterSummary, y: Double): Int {
        // A fixed chapter has one page, so it gives 0 here.
        val starts = summary.startYs
        var p = 0
        for (k in starts.indices) if (starts[k] <= y + 1e-9) p = k else break
        return p
    }

    /** The chapter a zip path belongs to, or null when it is not on the spine. */
    private fun chapterOfPath(path: String): Int? =
        parsed.spinePaths.indexOfFirst { it == path }.takeIf { it >= 0 }

    /**
     * The element an internal link points at, to show a note, a glossary entry or a
     * citation in place instead of turning the page (#227). Reads the markup of the
     * target's chapter only, and does not lay it out.
     *
     * [href] is a link as [EpubPage.links] gives it, `zipPath#fragment`. Returns null for
     * an external URL, a link without a fragment, or a fragment the chapter lacks.
     *
     * ```kotlin
     * val link = page.links.first { it.kind == EpubLinkKind.NOTE_REFERENCE }
     * book.linkTarget(link.href)?.let { note -> showNote(note.text) }
     * ```
     */
    public fun linkTarget(href: String): EpubLinkTarget? {
        val fragment = href.substringAfter('#', "").takeIf { it.isNotEmpty() } ?: return null
        val chapter = chapterOfPath(href.substringBefore('#')) ?: return null
        return linkTargetIn(parsed.layoutSpine(chapter).tree, chapter, href, fragment)
    }

    /** What the link [href] on a page of [chapter] is for. */
    internal fun linkKind(chapter: Int, href: String): EpubLinkKind =
        parsed.layoutSpine(chapter).linkKinds[href] ?: EpubLinkKind.LINK

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
        val summary = summaryOf(location.chapter) ?: return KiteBookmark.Flow(location.chapter, 0)
        var offset = 0
        for (i in 0 until location.page.coerceAtMost(summary.pageCount)) offset += summary.textLengths[i]
        return KiteBookmark.Flow(location.chapter, offset)
    }

    /**
     * Where [bookmark] sits now. A [KiteBookmark.Flow] prepares its chapter and
     * no other; a fragment wins over an offset, and both clamp into range rather
     * than failing. A [KiteBookmark.Page] counts the pages of the whole book, so
     * it lays out the chapters before its page, in order, and clamps to the last
     * page. Save a book's position as a Flow bookmark, as [bookmarkOf] gives.
     */
    override fun locate(bookmark: KiteBookmark): KiteLocation {
        val chapter = bookmark.chapter.coerceIn(0, (chapterCount - 1).coerceAtLeast(0))
        if (bookmark is KiteBookmark.Page) return pageLocation(bookmark.pageIndex)
        val flow = bookmark as KiteBookmark.Flow
        val summary = summaryOf(chapter) ?: return KiteLocation(chapter, 0)
        val last = summary.pageCount - 1
        if (last < 0) return KiteLocation(chapter, 0)

        flow.fragment?.let { id ->
            val y = anchorYIn(summary, id)
            if (y != null) return KiteLocation(chapter, localPageOf(summary, y))
        }
        if (flow.charOffset <= 0) return KiteLocation(chapter, 0)
        var seen = 0
        for (i in 0..last) {
            val length = summary.textLengths[i]
            if (flow.charOffset < seen + length || i == last) return KiteLocation(chapter, i)
            seen += length
        }
        return KiteLocation(chapter, last)
    }

    /** Chapter-local y of an element id, or null when the chapter has no such id. */
    private fun anchorYIn(summary: ChapterSummary, id: String): Double? =
        summary.anchors.firstOrNull { it.first == id }?.second

    /**
     * Characters of the book's text on one page, the unit [KiteBookmark.Flow.charOffset] counts
     * in. It counts characters, not glyphs, so a ligature or an added hyphen moves no position (#434).
     */
    private fun textLengthOf(page: PageRender): Int =
        page.lines.sumOf { it.sourceLength }

    private fun collectAnchors(box: LayoutBox, sink: (String, Double) -> Unit) {
        when (box) {
            is BlockBox -> {
                for (id in box.anchors) sink(id, box.y)
                for (c in box.children) collectAnchors(c, sink)
            }
            is TableBox -> for (r in box.rows) for (cell in r.cells) collectAnchors(cell, sink)
            is ImageBox -> for (id in box.anchors) sink(id, box.y)
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

    /**
     * The bytes of the book's file at [path], a zip path as [EpubMedia.sources], [EpubMedia.poster]
     * and [EpubLink.href] give it, or null when the book has no such file. The feed for a player of
     * the book's media (#29). A fragment after `#` is ignored. A `data:` URL, as a page gives one
     * for a resource the document carries in itself, gives the bytes it holds (#514).
     */
    public fun resource(path: String): ByteArray? = parsed.read(path)

    /** The media type that the manifest gives the file at [path], or null when it gives none. */
    public fun resourceType(path: String): String? = parsed.mediaTypeOf(path.substringBefore('#'))

    /**
     * The zip path of [chapter]'s document, as [resource] takes it, for a host that reads or shows
     * the chapter's own markup (#41). Throws [IndexOutOfBoundsException] for a chapter the book
     * does not have.
     */
    public fun chapterPath(chapter: Int): String = parsed.spinePaths[chapter]

    /**
     * The image at [zipPath], or the first item of its manifest fallback chain that decodes (#27).
     * A remote URL in the chain reads the bytes [remote] gives for it (#38).
     */
    private fun loadImage(zipPath: String, remote: (String) -> ByteArray?): KiteImageData? {
        for (path in listOf(zipPath) + parsed.fallbackPaths(zipPath)) {
            bytesAt(path, remote)?.let { KiteImageData.fromEncodedImage(it) }?.let {
                return it.withIdentity(parsed.imageIdentities.child(path))
            }
        }
        return null
    }

    private fun loadSvg(zipPath: String, remote: (String) -> ByteArray?): SvgImage? =
        bytesAt(zipPath, remote)?.let { SvgImage.parse(it) }

    /** The bytes of the file at [path] in the archive, or, for a remote URL, those [remote] gives (#38). */
    private fun bytesAt(path: String, remote: (String) -> ByteArray?): ByteArray? =
        if (isRemoteUrl(path)) remote(path) else parsed.read(path)

    private val pinLock = KiteLock()

    /** Per chapter, the remote bytes its first layout found, by URL; null for a URL that had not landed. */
    private val pinned = arrayOfNulls<HashMap<String, ByteArray?>>(parsed.spineCount)

    /**
     * The bytes of the remote resource [url] as [chapter]'s layout sees them: what had landed when
     * the chapter's first layout asked, for the life of this document (#38). A chapter laid out
     * again after the budget dropped it then makes the same pages, so its page count holds. A miss
     * starts the fetch, so the next document over the book finds the bytes.
     */
    private fun layoutBytes(chapter: Int, url: String): ByteArray? {
        pinLock.withLock { pinned[chapter]?.let { if (it.containsKey(url)) return it[url] } }
        val bytes = parsed.remote.bytes(url)
        if (bytes == null) requestRemote(url)
        return pinLock.withLock {
            val chapterPins = pinned[chapter] ?: HashMap<String, ByteArray?>().also { pinned[chapter] = it }
            if (chapterPins.containsKey(url)) chapterPins[url] else bytes.also { chapterPins[url] = it }
        }
    }

    /**
     * The bytes of the remote resource [url] for a paint of [chapter]: what has landed so far. A
     * miss starts the fetch, and [chapter]'s [EpubPage.remoteVersion] moves when it lands (#38).
     */
    private fun paintBytes(chapter: Int, url: String): ByteArray? {
        parsed.remote.notePainter(url, chapter)
        return parsed.remote.bytes(url) ?: run {
            requestRemote(url)
            null
        }
    }

    /** Starts the fetch of [url] through this document's fetcher, if it has one. */
    private fun requestRemote(url: String) {
        settings.resourceFetcher?.let { parsed.remote.request(url, it) }
    }

    /** How many remote resources [chapter] painted before they landed have landed since (#38). */
    internal fun remoteVersionOf(chapter: Int): Int = parsed.remote.versionOf(chapter)

    private val backgroundLock = KiteLock()

    /** Decoded pictures that pages paint by path, oldest use first, within [BACKGROUND_BYTES] (#28). */
    private val backgrounds = LinkedHashMap<String, Any?>()
    private var backgroundBytes = 0L

    /**
     * The picture that [chapter] paints from [zipPath], a decoded raster or a parsed SVG, or null:
     * a `background-image`, or a remote image whose box its markup sized (#38). A page paints
     * these on every render, so the document keeps them within a budget. A remote picture that may
     * still land is not kept, so the paint after it lands finds it.
     */
    internal fun paintPicture(zipPath: String, chapter: Int): Any? {
        backgroundLock.withLock {
            if (backgrounds.containsKey(zipPath)) return backgrounds.remove(zipPath).also { backgrounds[zipPath] = it }
        }
        val remote = isRemoteUrl(zipPath)
        val bytes: (String) -> ByteArray? = { url -> paintBytes(chapter, url) }
        val picture: Any? = if (parsed.namesSvg(zipPath)) loadSvg(zipPath, bytes) else loadImage(zipPath, bytes)
        if (remote && settings.resourceFetcher != null && parsed.remote.mayLand(zipPath)) return picture
        val size = (picture as? KiteImageData)?.let { (it.pixelBytes?.size ?: it.encodedBytes.size).toLong() } ?: 1024L
        backgroundLock.withLock {
            if (size > BACKGROUND_BYTES) return picture
            backgrounds.remove(zipPath)?.let { backgroundBytes -= sizeOfBackground(it) }
            backgrounds[zipPath] = picture
            backgroundBytes += size
            val oldest = backgrounds.entries.iterator()
            while (backgroundBytes > BACKGROUND_BYTES && oldest.hasNext()) {
                val entry = oldest.next()
                if (entry.key == zipPath) continue
                backgroundBytes -= sizeOfBackground(entry.value)
                oldest.remove()
            }
        }
        return picture
    }

    private fun sizeOfBackground(picture: Any?): Long =
        (picture as? KiteImageData)?.let { (it.pixelBytes?.size ?: it.encodedBytes.size).toLong() } ?: 1024L

    /**
     * Reads a file an `<image>` inside an SVG points at, resolved against the
     * directory that SVG lives in. Fixed-layout comics wrap each page's JPEG
     * in an SVG, so this is how those pages get their picture.
     */
    internal fun svgResource(baseDir: String, href: String, chapter: Int): ByteArray? =
        resolvePath(baseDir, href.substringBefore('#')).let { path ->
            if (isRemoteUrl(path)) paintBytes(chapter, path) else parsed.read(path)
        }

    /** The directory of [chapter]'s own document, for inline SVG references. */
    internal fun chapterDir(chapter: Int): String = parsed.spine(chapter).docDir

    public companion object {
        /**
         * What one laid-out glyph costs, with its share of lines, runs and DOM:
         * measured at 165 to 200 bytes per character on the JVM over the
         * corpus, rounded down because Android objects are smaller.
         */
        private const val BYTES_PER_GLYPH = 160L

        /** A floor for what a page holds besides its glyphs and images: the page and its lists. An estimate. */
        private const val BYTES_PER_PAGE = 2048L

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

        /**
         * Resolve a relative href against [baseDir], normalizing `.`/`..` + percent-decode. An
         * absolute http or https URL names a resource outside the container, and stays a URL
         * without its fragment (EPUB 3.3, 3.6, #38).
         */
        internal fun resolvePath(baseDir: String, href: String): String {
            val trimmed = href.trim()
            if (isRemoteUrl(trimmed)) return trimmed.substringBefore('#')
            // A data URL carries its resource with it, so it is its own path (#514), and a blob
            // URL names a blob of the book's scripts, which the book's blob URL store holds (#533).
            if (KiteDataUrl.isDataUrl(trimmed) || isBlobUrl(trimmed)) return trimmed
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

/** Draws nothing and keeps each SVG that a page paints, with the matrix it paints it under (#523). */
private class SvgPlacements : KiteCanvas by NoopCanvas {
    val placed = ArrayList<Pair<SvgImage, KiteMatrix>>()
}

/**
 * A tappable link region on an [EpubPage]. [rect] is in display space (y-down;
 * y-min stored in [KiteRectangle.bottom]). [href] is either `zipPath#fragment`
 * (internal, resolve with the document's href navigation) or an external URL.
 * [kind] says what the link is for, such as a note reference, which a reader can
 * open in place with [EpubDocument.linkTarget].
 *
 * The page gives the links of its own markup. Make one for a link that comes from elsewhere,
 * such as a web view over an embedded document that opens a link (#41).
 */
public class EpubLink(
    public val rect: io.github.yuroyami.kitepdf.core.KiteRectangle,
    public val href: String,
    public val kind: EpubLinkKind = EpubLinkKind.LINK,
)

/** Generic font family a reader app can force via [EpubSettings.fontFamily]. */
public enum class ReaderFontFamily { SERIF, SANS_SERIF, MONOSPACE }

/**
 * Reader layout settings. All values in points. Change them at runtime with
 * [EpubDocument.withSettings] (or the `withFontSize`/`withPageSize`/`withMargin`
 * shorthands) to re-flow without re-parsing the book.
 *
 * The typography overrides (font family, colors, justification, hyphenation) are applied
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
    /** true forces justify, false forces start alignment; null = as authored. */
    val justify: Boolean? = null,
    /** False drops the publisher's CSS (author rules + inline styles): UA + reader layers only. */
    val usePublisherCss: Boolean = true,
    /**
     * How much laid-out text the book keeps in memory, as an estimate in bytes.
     *
     * Layout keeps one glyph object per character, so a whole novel costs more
     * than an Android app's heap allows (about 165 bytes per character on the
     * JVM). The chapters the reader has not used for the longest drop their
     * pages first and lay out again on their next use, about a tenth of a
     * second per chapter. Page counts, anchors and bookmarks survive the drop,
     * so navigation never waits. One chapter always stays, so a book that is a
     * single spine document keeps that document whole. 0 keeps only the chapter
     * in use.
     */
    val layoutCacheBytes: Long = 48L * 1024 * 1024,
    /**
     * Hyphenation for the whole book, whatever its CSS says: true breaks long
     * words at line ends, false never does, null follows the book's `hyphens`.
     * Each chapter uses the patterns of its own language. The book is not changed.
     */
    val hyphenate: Boolean? = null,
    /**
     * Fetches the resources that the book names by an absolute `https` URL instead of a file in
     * its container (EPUB 3.3, 3.6, #38). `kitepdf-net` ships one on a Ktor client. Null fetches
     * nothing: a remote image takes its manifest fallback, else keeps the room its markup gives,
     * and a remote font takes the next source of its rule. See [EpubDocument.fetchRemoteResources]
     * for when the bytes count.
     */
    val resourceFetcher: EpubResourceFetcher? = null,
) {
    init {
        require(layoutCacheBytes >= 0L) { "layoutCacheBytes must be >= 0" }
        require(pageWidth.isFinite() && pageWidth > 0.0) { "pageWidth must be finite and > 0" }
        require(pageHeight.isFinite() && pageHeight > 0.0) { "pageHeight must be finite and > 0" }
        require(fontSize.isFinite() && fontSize > 0.0) { "fontSize must be finite and > 0" }
        require(margin.isFinite() && margin >= 0.0) { "margin must be finite and >= 0" }
        require(margin < pageWidth / 2.0 && margin < pageHeight / 2.0) {
            "margin must leave a positive page content area"
        }
        require(lineHeightScale.isFinite() && lineHeightScale > 0.0) {
            "lineHeightScale must be finite and > 0"
        }
    }
}

/**
 * One reflowed EPUB page: paints backgrounds/borders, then text lines and images.
 *
 * A page object is permanent (#221) and holds no layout of its own: what it
 * paints comes from [EpubDocument.render], which lays the chapter out again
 * when the memory budget had dropped it (#218). What a viewer asks without
 * painting (size, location) is answered from the constructor, so composing a
 * page never lays anything out.
 */
public class EpubPage internal constructor(
    private val doc: EpubDocument,
    /** The spine item this page belongs to. */
    public val chapter: Int,
    /** This page's index inside its chapter. */
    private val index: Int,
    private val pageWidth: Double,
    private val pageHeight: Double,
) : KitePage {

    /**
     * The book this page belongs to, for the bytes of its resources: a player that plays an
     * element of [media] reads its sources with [EpubDocument.resource] (#31).
     */
    public val document: EpubDocument get() = doc

    /** The laid-out page, fetched per operation: holding it would defeat the budget. */
    private fun laidOut(): PageRender = doc.render(chapter, index)

    /** Whether this page object has the size [width] by [height], so a new layout of its chapter can keep it (#41). */
    internal fun hasSize(width: Double, height: Double): Boolean = pageWidth == width && pageHeight == height

    /**
     * How many times the book's scripts changed this page's chapter (#41). A viewer that drew the
     * page at another value draws it again; [EpubDocument.chapterChanges] says when to look.
     */
    public val chapterVersion: Int get() = doc.chapterVersionOf(chapter)

    /**
     * Where [element] is on this page, in display space: its boxes, and the text and images
     * inside it, together. Null when the page shows none of it (#41).
     */
    internal fun boundsOf(element: KiteXmlNode.Element): io.github.yuroyami.kitepdf.core.KiteRectangle? {
        val page = laidOut()
        var left = Double.POSITIVE_INFINITY
        var top = Double.POSITIVE_INFINITY
        var right = Double.NEGATIVE_INFINITY
        var bottom = Double.NEGATIVE_INFINITY
        fun add(r: io.github.yuroyami.kitepdf.core.KiteRectangle) {
            left = minOf(left, r.left); top = minOf(top, r.bottom); right = maxOf(right, r.right); bottom = maxOf(bottom, r.top)
        }
        fun inside(e: KiteXmlNode.Element?): Boolean {
            var at = e
            while (at != null) { if (at === element) return true; at = at.parent }
            return false
        }
        if (!page.vertical) {
            val pageBottom = page.startY + page.pageHeight - 2 * page.margin
            for (box in page.hitBoxes) {
                if (box.source !== element) continue
                val boxTop = maxOf(box.y, page.startY)
                val boxBottom = minOf(box.bottom, pageBottom)
                if (boxBottom < boxTop) continue
                val x = page.margin + box.x
                add(movedRect(KiteRectangle(x, displayY(page, boxTop), x + box.borderBoxWidth, displayY(page, boxBottom)), displayTransformAt(page, box.decoRank)))
            }
        }
        for (line in page.lines) {
            fun rectOf(start: Double, end: Double): KiteRectangle = if (page.vertical) {
                val a = columnX(page, line.yTop)
                val b = columnX(page, line.yTop + line.height)
                KiteRectangle(minOf(a, b), page.margin + start, maxOf(a, b), page.margin + end)
            } else {
                movedRect(
                    KiteRectangle(page.margin + start, displayY(page, line.yTop), page.margin + end, displayY(page, line.yTop + line.height)),
                    displayTransformAt(page, line.paintRank),
                )
            }
            for (run in line.runs) if (!run.isAnnotation && inside(run.element)) add(rectOf(run.x, run.x + run.paintWidth))
            for (image in line.images) if (inside(image.element)) add(rectOf(image.x, image.x + image.width))
        }
        return if (left > right) null else KiteRectangle(left, top, right, bottom)
    }

    /**
     * The innermost element painted at ([x], [y]) on this page, in display space, or null where
     * no element is (#41). Only a chapter that scripts run in keeps its elements, so in any other
     * chapter this is null. The paint on top wins: text over the box it sits in, and a positioned
     * box over what it covers. The boxes of a vertical page are not searched, only its text.
     */
    internal fun elementAt(x: Double, y: Double): KiteXmlNode.Element? {
        val page = laidOut()
        var best: KiteXmlNode.Element? = null
        var bestRank = Int.MIN_VALUE
        fun offer(element: KiteXmlNode.Element?, rank: Int, rect: io.github.yuroyami.kitepdf.core.KiteRectangle) {
            if (element == null || rank < bestRank) return
            if (x < rect.left || x > rect.right || y < rect.bottom || y > rect.top) return
            best = element
            bestRank = rank
        }
        if (!page.vertical) {
            val pageBottom = page.startY + page.pageHeight - 2 * page.margin
            // Tree order, so of two boxes painted at one rank the inner one, which comes later, wins.
            for (box in page.hitBoxes) {
                val top = maxOf(box.y, page.startY)
                val bottom = minOf(box.bottom, pageBottom)
                if (bottom <= top || box.borderBoxWidth <= 0.0) continue
                val left = page.margin + box.x
                val rect = movedRect(
                    io.github.yuroyami.kitepdf.core.KiteRectangle(left, displayY(page, top), left + box.borderBoxWidth, displayY(page, bottom)),
                    displayTransformAt(page, box.decoRank),
                )
                offer(box.source, if (box is ImageBox) box.contentRank else box.decoRank, rect)
            }
        }
        for (line in page.lines) {
            fun rectOf(start: Double, end: Double): io.github.yuroyami.kitepdf.core.KiteRectangle = if (page.vertical) {
                val a = columnX(page, line.yTop)
                val b = columnX(page, line.yTop + line.height)
                io.github.yuroyami.kitepdf.core.KiteRectangle(minOf(a, b), page.margin + start, maxOf(a, b), page.margin + end)
            } else {
                movedRect(
                    io.github.yuroyami.kitepdf.core.KiteRectangle(
                        page.margin + start, displayY(page, line.yTop), page.margin + end, displayY(page, line.yTop + line.height),
                    ),
                    displayTransformAt(page, line.paintRank),
                )
            }
            for (run in line.runs) if (!run.isAnnotation) offer(run.element, line.paintRank, rectOf(run.x, run.x + run.paintWidth))
            for (image in line.images) offer(image.element, line.paintRank, rectOf(image.x, image.x + image.width))
        }
        return best
    }

    /** False while the layout budget has dropped this page's chapter (#377). */
    override val isContentLoaded: Boolean get() = doc.isChapterLive(chapter)

    /** Lays this page's chapter out again when the layout budget dropped it (#377). */
    override fun loadContent() {
        doc.loadChapter(chapter)
    }

    /**
     * True when a run of this page's text has no embedded outlines, which is the case for every
     * run of a book without fonts of its own. Null when an SVG on the page may draw text of its
     * own, and false otherwise (#131).
     */
    override val drawsHostFontText: Boolean?
        get() {
            val page = laidOut()
            // A formula draws its glyphs in a host font too (#32).
            if (page.lines.any { line -> line.maths.isNotEmpty() || line.runs.any { !it.hasOutlines && it.glyphs.isNotEmpty() } }) return true
            val svg = page.images.any { it.svg != null || it.zipPath.endsWith(".svg", true) } ||
                page.lines.any { line -> line.images.any { it.svg != null || it.zipPath.endsWith(".svg", true) } }
            return if (svg) null else false
        }

    /** One rectangle per line of the text inside the element [id] on this page, in display space (#36). */
    internal fun rectsOf(id: String): List<io.github.yuroyami.kitepdf.core.KiteRectangle> {
        val page = laidOut()
        val out = ArrayList<io.github.yuroyami.kitepdf.core.KiteRectangle>()
        for (line in page.lines) {
            var start = Double.POSITIVE_INFINITY
            var end = Double.NEGATIVE_INFINITY
            for (run in line.runs) {
                if (run.isAnnotation || id !in run.ids) continue
                start = minOf(start, run.x)
                end = maxOf(end, run.x + run.paintWidth)
            }
            if (start > end) continue
            out += if (page.vertical) {
                // A column: the text runs down the page, and the column spans the line's height across it.
                val a = columnX(page, line.yTop)
                val b = columnX(page, line.yTop + line.height)
                io.github.yuroyami.kitepdf.core.KiteRectangle(minOf(a, b), page.margin + start, maxOf(a, b), page.margin + end)
            } else {
                // A transformed box moves the element with its paint (#28).
                movedRect(
                    io.github.yuroyami.kitepdf.core.KiteRectangle(
                        page.margin + start, displayY(page, line.yTop), page.margin + end, displayY(page, line.yTop + line.height),
                    ),
                    displayTransformAt(page, line.paintRank),
                )
            }
        }
        // An element inside an SVG on the page, by the lines of its text or the box of what it draws (#523).
        // An id is unique, so one the page's own lines hold is in no SVG, and the page is not painted again.
        if (out.isEmpty()) for (svg in svgTexts(page)) out += svg.rectsOf(id)
        return out
    }

    /**
     * The folder an image's SVG resolves its own links against: the folder of the image file
     * at [zipPath], or the chapter's folder for an `<svg>` written in the chapter (RFC 3986,
     * 5.2, #276). Block and inline images use the same rule (#425).
     */
    private fun resourceDir(zipPath: String): String =
        if (zipPath.isEmpty()) doc.chapterDir(chapter) else zipPath.substringBeforeLast('/', "")

    /** Reads files an SVG references, relative to [baseDir] inside the archive, or at an https URL (#38). */
    private fun svgLoader(baseDir: String): (String) -> ByteArray? =
        { href -> doc.svgResource(baseDir, href, chapter) }

    /**
     * How many remote resources that this page's chapter painted before their bytes landed have
     * landed since (#38). A viewer that keeps the pixels of a page draws it again when this moves,
     * so a remote image with a declared size appears in its box. It counts for the whole chapter,
     * and reads no layout.
     */
    public val remoteVersion: Int get() = doc.remoteVersionOf(chapter)

    /**
     * Paints the remote picture at [url] in the box from ([left], [bottom]), [width] by [height],
     * once its bytes have landed, and returns false while they have not (#38).
     */
    private fun paintRemoteImage(
        canvas: KiteCanvas, deviceCtm: KiteMatrix, url: String,
        width: Double, height: Double, left: Double, bottom: Double, objectFit: ObjectFit,
    ): Boolean {
        if (width <= 0.0 || height <= 0.0) return false
        val picture = doc.paintPicture(url, chapter) ?: return false
        val image = picture as? KiteImageData
        val svg = picture as? SvgImage
        val iw = svg?.width ?: image?.width?.toDouble() ?: return false
        val ih = svg?.height ?: image?.height?.toDouble() ?: return false
        if (iw <= 0.0 || ih <= 0.0) return false
        paintImage(canvas, deviceCtm, image, svg, width, height, left, bottom, objectFit, resourceDir(url))
        return true
    }

    /** True for a picture that its layout sized without bytes, which a paint reads from the remote store (#38). */
    private fun paintsRemote(image: KiteImageData?, svg: SvgImage?, path: String): Boolean =
        image == null && svg == null && isRemoteUrl(path)

    /** Where this page sits: its chapter, and its index inside that chapter. */
    public val location: KiteLocation get() = KiteLocation(chapter, index)
    override val displayWidth: Double get() = pageWidth
    override val displayHeight: Double get() = pageHeight

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
    private fun displayY(page: PageRender, docY: Double): Double = page.margin + (docY - page.startY)

    override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix): Unit = render(canvas, deviceCtm, null)

    /** [renderTo] that stops between two paint steps, such as two lines, once [cancellation] reads true (#370). */
    override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix, cancellation: KiteCancellation): Unit =
        render(canvas, deviceCtm, cancellation)

    private fun render(canvas: KiteCanvas, deviceCtm: KiteMatrix, cancellation: KiteCancellation?) {
        val page = laidOut()
        if (page.vertical) renderVerticalTo(page, canvas, deviceCtm, cancellation) else renderHorizontalTo(page, canvas, deviceCtm, cancellation)
    }

    private fun renderHorizontalTo(page: PageRender, canvas: KiteCanvas, deviceCtm: KiteMatrix, cancellation: KiteCancellation?) {
        canvas.beginPage(displayWidth, displayHeight, deviceCtm)
        val margin = page.margin
        val startY = page.startY
        val bandBottom = startY + (displayHeight - 2 * margin)
        fun yUp(docY: Double) = displayHeight - displayY(page, docY)

        // Reader background (night mode): under everything, full page.
        doc.settings.backgroundColor?.let { bg ->
            val rect = KitePath.Builder().apply {
                moveTo(0.0, 0.0); lineTo(displayWidth, 0.0); lineTo(displayWidth, displayHeight); lineTo(0.0, displayHeight); close()
            }.build()
            canvas.fillPath(rect, deviceCtm, bg, evenOdd = false)
        }

        // The matrix of the steps being painted: the page's, or a transformed box's inside its stretch (#28).
        var ctm = deviceCtm
        val saved = ArrayList<KiteMatrix>()

        fun paintLine(line: PositionedLine) {
            val base = yUp(line.yTop + line.ascent)
            for (run in line.runs) {
                val tm = KiteMatrix.translation(margin + run.x, base + run.baselineShift)
                paintRunBackground(run, canvas, ctm.concat(tm))
            }
            for (run in line.runs) {
                val tm = KiteMatrix.translation(margin + run.x, base + run.baselineShift)
                if (run.glyphs.isNotEmpty()) canvas.drawGlyphs(
                    run.glyphs, run.fontSize, unitsPerEm = run.unitsPerEm, hasOutlines = run.hasOutlines,
                    fontSpec = run.fontSpec, textToDevice = ctm.concat(tm),
                    color = run.color, alpha = 1.0, blendMode = KiteBlendMode.Normal,
                )
                paintRunLines(run, canvas, vertical = false) { shift -> ctm.concat(KiteMatrix.translation(margin + run.x, base + shift)) }
            }
            // Inline images: bottom on the baseline, next to the text runs.
            for (im in line.images) {
                if (paintsRemote(im.image, im.svg, im.zipPath)) {
                    paintRemoteImage(canvas, ctm, im.zipPath, im.width, im.height, margin + im.x, base, im.objectFit)
                    continue
                }
                paintImage(canvas, ctm, im.image, im.svg, im.width, im.height,
                    margin + im.x, base, im.objectFit, resourceDir(im.zipPath))
            }
            // Formulas: their baselines on the line's (#32).
            for (m in line.maths) paintMath(canvas, ctm, m, margin + m.x, base)
        }

        paintInOrder(
            page, cancellation,
            deco = { box -> paintBox(box, canvas, ctm, margin, startY, bandBottom, ::yUp) },
            line = ::paintLine,
            begin = { scope ->
                val s = scope.box.style
                if (scope.kind == EffectKind.TRANSFORM) {
                    saved += ctm
                    ctm = ctm.concat(yUpMatrix(displayTransform(page, scope.box) ?: KiteMatrix.IDENTITY))
                    return@paintInOrder
                }
                // The padding box, in page space, y up.
                val left = margin + scope.box.x + s.borderLeft.effective
                val right = margin + scope.box.x + scope.box.borderBoxWidth - s.borderRight.effective
                val top = yUp(scope.box.y + s.borderTop.effective)
                val bottom = yUp(scope.box.bottom - s.borderBottom.effective)
                val radii = innerRadii(
                    s.radii?.resolve(scope.box.borderBoxWidth, scope.box.borderBoxHeight),
                    s.borderLeft.effective, s.borderTop.effective, s.borderRight.effective, s.borderBottom.effective,
                )
                openEffect(canvas, ctm, scope, KiteRectangle(left, bottom, right, top), radii)
            },
            end = { scope -> if (scope.kind == EffectKind.TRANSFORM) ctm = saved.removeAt(saved.lastIndex) else closeEffect(canvas, scope) },
            image = { box ->
                // The picture fills the content box, inside the border and padding (#101).
                val inset = imageInset(box.style)
                val left = margin + box.x + inset.inlineStart
                val bottom = yUp(box.bottom - inset.blockEnd)
                // Rounded corners clip the picture to its content box, with the corners made smaller by the insets (#28).
                val radii = innerRadii(
                    box.style.radii?.resolve(box.borderBoxWidth, box.borderBoxHeight),
                    inset.inlineStart, inset.blockStart, inset.inlineEnd, inset.blockEnd,
                )
                if (radii != null) {
                    val shape = KitePath.Builder().apply { roundedRect(left, bottom, left + box.drawWidth, bottom + box.drawHeight, radii) }.build()
                    canvas.pushClip(shape, ctm, evenOdd = false)
                }
                when {
                    // A remote picture whose bytes have landed fills its box (#38).
                    paintsRemote(box.image, box.svg, box.zipPath) &&
                        paintRemoteImage(canvas, ctm, box.zipPath, box.drawWidth, box.drawHeight, left, bottom, box.style.objectFit) -> Unit
                    box.media != null && box.image == null -> paintMediaPlaceholder(canvas, ctm, left, bottom, box.drawWidth, box.drawHeight)
                    else -> paintImage(canvas, ctm, box.image, box.svg, box.drawWidth, box.drawHeight,
                        left, bottom, box.style.objectFit, resourceDir(box.zipPath))
                }
                if (radii != null) canvas.popClip()
            },
        )
        canvas.endPage()
    }

    /**
     * Vertical-rl painting: the logical layout maps onto physical
     * columns advancing right-to-left, the inline axis running down the page.
     * Full-width glyphs stand upright, centred on the column's em axis;
     * everything else rotates 90 degrees clockwise around the shared baseline.
     */
    private fun renderVerticalTo(page: PageRender, canvas: KiteCanvas, deviceCtm: KiteMatrix, cancellation: KiteCancellation?) {
        canvas.beginPage(displayWidth, displayHeight, deviceCtm)
        val margin = page.margin
        val startY = page.startY
        val bandBottom = startY + (displayWidth - 2 * margin)
        fun colX(v: Double) = columnX(page, v)

        doc.settings.backgroundColor?.let { bg ->
            val rect = KitePath.Builder().apply {
                moveTo(0.0, 0.0); lineTo(displayWidth, 0.0); lineTo(displayWidth, displayHeight); lineTo(0.0, displayHeight); close()
            }.build()
            canvas.fillPath(rect, deviceCtm, bg, evenOdd = false)
        }

        fun paintLine(line: PositionedLine) {
            // CSS Writing Modes 4, 6.3 and 6.4: line-over is the right side in both
            // vertical modes, which in vertical-lr is the line's block-end side. So a
            // vertical-lr baseline sits the line's ascent before its end (#261), and a
            // line taller than the page keeps that over side, which holds the ink, on
            // the page. A positive shift moves toward line-over.
            fun axisX(shift: Double): Double = colX(
                if (page.verticalLr) minOf(line.yTop + line.height, bandBottom) - line.ascent + shift
                else line.yTop + line.ascent - shift,
            )
            fun runTransform(run: PlacedRun, shift: Double = run.baselineShift): KiteMatrix = deviceCtm.concat(KiteMatrix(
                0.0, -1.0, 1.0, 0.0, axisX(shift), displayHeight - margin - run.x,
            ))
            for (run in line.runs) paintRunBackground(run, canvas, runTransform(run))
            for (run in line.runs) {
                // The horizontal baseline maps to a vertical em axis at this x
                // (a positive baselineShift moves toward the line-over side, so
                // ruby lands to the RIGHT of its base column).
                val xAxis = axisX(run.baselineShift)
                var pen = margin + run.x // display-y pen, running down the page
                val combined = run.combined
                if (combined != null) {
                    // A tate-chu-yoko composition stands upright, side by side across the column's em
                    // and squeezed into it when wider, centred in its advance down the column as an
                    // upright character is (#508).
                    val width = combined.sumOf { it.advanceWidth } * run.fontSize / 1000.0
                    val scale = if (width > run.fontSize) run.fontSize / width else 1.0
                    val x0 = xAxis + UPRIGHT_CENTER * run.fontSize - width * scale / 2.0
                    val baseline = pen + run.paintWidth / 2.0 + UPRIGHT_CENTER * run.fontSize
                    canvas.drawGlyphs(
                        combined, run.fontSize, unitsPerEm = run.unitsPerEm, hasOutlines = run.hasOutlines, fontSpec = run.fontSpec,
                        textToDevice = deviceCtm.concat(KiteMatrix(scale, 0.0, 0.0, 1.0, x0, displayHeight - baseline)),
                        color = run.color, alpha = 1.0, blendMode = KiteBlendMode.Normal,
                    )
                    paintRunLines(run, canvas, vertical = true) { shift -> runTransform(run, shift) }
                    continue
                }
                var k = 0
                while (k < run.glyphs.size) {
                    val g = run.glyphs[k]
                    if (isUpright(g, run)) {
                        val advPt = g.advanceWidth * run.fontSize / 1000.0
                        // Counter-rotated in place: centred on the em axis, the em
                        // box straddling the axis by the nominal ascent/descent.
                        // A system-font glyph draws with no offset, so its centring moves its origin (#508).
                        val shifted = !run.hasOutlines && g.xOffset != 0.0
                        val x0 = xAxis + UPRIGHT_CENTER * run.fontSize - advPt / 2.0 + (if (shifted) g.xOffset * run.fontSize / 1000.0 else 0.0)
                        val baseline = pen + advPt / 2.0 + UPRIGHT_CENTER * run.fontSize
                        canvas.drawGlyphs(
                            if (shifted) listOf(g.copy(xOffset = 0.0)) else run.glyphs.subList(k, k + 1), run.fontSize, unitsPerEm = run.unitsPerEm,
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
                        while (j < run.glyphs.size && !isUpright(run.glyphs[j], run)) {
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
                paintRunLines(run, canvas, vertical = true) { shift -> runTransform(run, shift) }
            }
            // Replaced content stays upright. Its physical width occupies the
            // line-over side of the baseline, while its height advances the inline pen.
            for (im in line.images) {
                val top = margin + im.x
                if (paintsRemote(im.image, im.svg, im.zipPath)) {
                    paintRemoteImage(canvas, deviceCtm, im.zipPath, im.width, im.height, axisX(0.0), displayHeight - top - im.height, im.objectFit)
                    continue
                }
                paintImage(canvas, deviceCtm, im.image, im.svg, im.width, im.height,
                    axisX(0.0), displayHeight - top - im.height, im.objectFit, resourceDir(im.zipPath))
            }
        }

        paintInOrder(
            page, cancellation,
            deco = { box -> paintBoxVertical(box, canvas, deviceCtm, margin, startY, bandBottom, ::colX) },
            line = ::paintLine,
            begin = { scope ->
                val s = scope.box.style
                // The block axis runs across the columns and the inline axis down the page.
                val a = colX(scope.box.y + s.borderTop.effective)
                val b = colX(scope.box.bottom - s.borderBottom.effective)
                val top = margin + scope.box.x + s.borderLeft.effective
                val bottom = margin + scope.box.x + scope.box.borderBoxWidth - s.borderRight.effective
                openEffect(canvas, deviceCtm, scope, KiteRectangle(minOf(a, b), displayHeight - bottom, maxOf(a, b), displayHeight - top))
            },
            end = { scope -> closeEffect(canvas, scope) },
            image = { box ->
                val inset = imageInset(box.style)
                val left = minOf(colX(box.y + inset.blockStart), colX(box.bottom - inset.blockEnd))
                val top = margin + box.x + inset.inlineStart
                val bottom = displayHeight - top - box.drawHeight
                when {
                    // A remote picture whose bytes have landed fills its box (#38).
                    paintsRemote(box.image, box.svg, box.zipPath) &&
                        paintRemoteImage(canvas, deviceCtm, box.zipPath, box.drawWidth, box.drawHeight, left, bottom, box.style.objectFit) -> Unit
                    box.media != null && box.image == null -> paintMediaPlaceholder(canvas, deviceCtm, left, bottom, box.drawWidth, box.drawHeight)
                    else -> paintImage(canvas, deviceCtm, box.image, box.svg, box.drawWidth, box.drawHeight,
                        left, bottom, box.style.objectFit, resourceDir(box.zipPath))
                }
            },
        )
        canvas.endPage()
    }

    /**
     * Paints the backgrounds and borders, the lines and the block images of [page] in the order
     * that [Paginator] numbered them, which follows CSS 2.1, Appendix E (#172). Stops before the
     * next step once [cancellation] reads true.
     */
    private fun paintInOrder(
        page: PageRender,
        cancellation: KiteCancellation?,
        deco: (LayoutBox) -> Unit,
        line: (PositionedLine) -> Unit,
        image: (ImageBox) -> Unit,
        begin: (EffectScope) -> Unit = {},
        end: (EffectScope) -> Unit = {},
    ) {
        // Each step is its rank, then its kind, then its index in the page's list.
        val steps = LongArray(page.decoBoxes.size + page.lines.size + page.images.size)
        var n = 0
        fun step(rank: Int, kind: Int, index: Int) { steps[n++] = (rank.toLong() shl 32) or (kind.toLong() shl 30) or index.toLong() }
        page.decoBoxes.forEachIndexed { i, box -> step(box.decoRank, 0, i) }
        page.lines.forEachIndexed { i, l -> step(l.paintRank, 1, i) }
        page.images.forEachIndexed { i, box -> step(box.contentRank, 2, i) }
        steps.sort()
        // A box that paints as one group owns a stretch of ranks, so its group or clip opens
        // before the first step in the stretch and closes after the last. Stretches nest (#28).
        val scopes = effectScopes(page)
        val open = ArrayList<EffectScope>()
        var next = 0
        try {
            for (s in steps) {
                if (cancellation?.isCancelled() == true) return
                val rank = (s ushr 32).toInt()
                while (open.isNotEmpty() && open.last().last < rank) end(open.removeAt(open.lastIndex))
                while (next < scopes.size && scopes[next].first <= rank) {
                    val scope = scopes[next++]
                    if (scope.last < rank) continue
                    begin(scope)
                    open += scope
                }
                val index = (s and 0x3FFFFFFF).toInt()
                // A box with visibility hidden keeps its room and paints nothing of its own (CSS 2.1, 11.2).
                when ((s ushr 30 and 3).toInt()) {
                    0 -> page.decoBoxes[index].let { if (it.style.visible) deco(it) }
                    1 -> page.lines[index].let { if (it.owner?.style?.visible != false) line(it) }
                    else -> page.images[index].let { if (it.style.visible) image(it) }
                }
            }
        } finally {
            // A cancelled render still leaves the canvas balanced.
            while (open.isNotEmpty()) end(open.removeAt(open.lastIndex))
        }
    }

    /** What an effect stretch does to its steps, outermost first where stretches start together (#28). */
    private enum class EffectKind { TRANSFORM, GROUP, CLIP }

    /** The ranks that one box paints through a transform, a transparency group or a clip (#28). */
    private class EffectScope(val box: LayoutBox, val kind: EffectKind, val first: Int, val last: Int) {
        val clip: Boolean get() = kind == EffectKind.CLIP
    }

    /** The effect stretches on [page], outer ones first where they start at the same rank. */
    private fun effectScopes(page: PageRender): List<EffectScope> {
        if (page.effectBoxes.isEmpty()) return emptyList()
        val scopes = ArrayList<EffectScope>()
        for (box in page.effectBoxes) {
            if (box.lastRank < box.decoRank) continue
            // A transform moves the box and all it holds, so it comes first; vertical pages do not transform.
            if (box.style.transform != null && !page.vertical) scopes += EffectScope(box, EffectKind.TRANSFORM, box.decoRank, box.lastRank)
            if (box.style.opacity < 1.0) scopes += EffectScope(box, EffectKind.GROUP, box.decoRank, box.lastRank)
            // Overflow clips the content, not the box's own background and border, which paint first.
            if (box.style.clipsOverflow && box.lastRank > box.decoRank) scopes += EffectScope(box, EffectKind.CLIP, box.decoRank + 1, box.lastRank)
        }
        return scopes.sortedWith(compareBy<EffectScope> { it.first }.thenByDescending { it.last }.thenBy { it.kind.ordinal })
    }

    /**
     * Opens [scope]'s effect: a clip to [paddingBox] with its corner [radii], or a group over the
     * page with the box's opacity.
     */
    private fun openEffect(canvas: KiteCanvas, ctm: KiteMatrix, scope: EffectScope, paddingBox: KiteRectangle, radii: DoubleArray? = null) {
        if (scope.clip) {
            val path = KitePath.Builder().apply { roundedRect(paddingBox.left, paddingBox.bottom, paddingBox.right, paddingBox.top, radii) }.build()
            canvas.pushClip(path, ctm, evenOdd = false)
        } else {
            // The group spans the page, since content can reach outside its box.
            canvas.beginTransparencyGroup(
                KiteRectangle(0.0, 0.0, displayWidth, displayHeight), ctm,
                isolated = true, knockout = false, alpha = scope.box.style.opacity, blendMode = KiteBlendMode.Normal,
            )
        }
    }

    private fun closeEffect(canvas: KiteCanvas, scope: EffectScope) {
        if (scope.clip) canvas.popClip() else canvas.endTransparencyGroup()
    }

    /**
     * [box]'s `transform` in the display space of [page], y down, about its `transform-origin`
     * in its border box. Null without a transform, and in vertical writing (#28).
     */
    private fun displayTransform(page: PageRender, box: LayoutBox): KiteMatrix? {
        val functions = box.style.transform ?: return null
        if (page.vertical) return null
        val w = box.borderBoxWidth
        val h = box.borderBoxHeight
        val (ox, oy) = box.style.transformOrigin
        return transformMatrix(functions, w, h, page.margin + box.x + ox.resolve(w), displayY(page, box.y) + oy.resolve(h))
    }

    /** [m], a matrix of the display space with y down, as the same motion of the page space with y up. */
    private fun yUpMatrix(m: KiteMatrix): KiteMatrix =
        KiteMatrix(m.a, -m.b, -m.c, m.d, m.c * displayHeight + m.e, displayHeight - m.d * displayHeight - m.f)

    /**
     * The transforms of the boxes whose paint stretch holds [rank], outermost applied last, in
     * display space. Null when none holds it (#28).
     */
    private fun displayTransformAt(page: PageRender, rank: Int): KiteMatrix? {
        // Stretches nest, so the boxes that hold the rank, by first rank, run from the outermost in.
        val holders = page.effectBoxes
            .filter { it.style.transform != null && rank >= it.decoRank && rank <= it.lastRank }
            .sortedBy { it.decoRank }
        if (holders.isEmpty()) return null
        var m = KiteMatrix.IDENTITY
        // An inner box moves first, inside the space its outer box then moves.
        for (box in holders) displayTransform(page, box)?.let { m = m.concat(it) }
        return m
    }

    /** The bounding box of [r], a display-space rectangle with the smaller y in bottom, moved by [m]. */
    private fun movedRect(r: KiteRectangle, m: KiteMatrix?): KiteRectangle {
        if (m == null) return r
        val xs = DoubleArray(4)
        val ys = DoubleArray(4)
        for ((i, p) in listOf(r.left to r.bottom, r.right to r.bottom, r.left to r.top, r.right to r.top).withIndex()) {
            xs[i] = m.transformX(p.first, p.second)
            ys[i] = m.transformY(p.first, p.second)
        }
        return KiteRectangle(xs.min(), ys.min(), xs.max(), ys.max())
    }

    /**
     * CSS Writing Modes 4, 3 and 7.2: image orientation/dimensions are physical.
     * CSS Images 3, 4.3.2 and 4.5: cover and contain keep the intrinsic aspect and centre the
     * content, as the initial `object-position` does. Cover fills the replaced element's box
     * and is clipped to it in every writing mode; contain fits inside it and leaves the rest
     * of the box empty (#100, #170, #490).
     */
    private fun paintImage(
        canvas: KiteCanvas, deviceCtm: KiteMatrix, image: KiteImageData?, svg: SvgImage?,
        width: Double, height: Double, left: Double, bottom: Double, objectFit: ObjectFit, baseDir: String,
    ) {
        val intrinsicW = svg?.width ?: image?.width?.toDouble() ?: return
        val intrinsicH = svg?.height ?: image?.height?.toDouble() ?: return
        if (width <= 0 || height <= 0 || intrinsicW <= 0 || intrinsicH <= 0) return
        val cover = objectFit == ObjectFit.COVER
        val scale = when (objectFit) {
            ObjectFit.COVER -> maxOf(width / intrinsicW, height / intrinsicH)
            ObjectFit.CONTAIN -> minOf(width / intrinsicW, height / intrinsicH)
            ObjectFit.FILL -> null
        }
        val dw = scale?.let { intrinsicW * it } ?: width
        val dh = scale?.let { intrinsicH * it } ?: height
        val x = left + (width - dw) / 2.0
        val y = bottom + (height - dh) / 2.0
        if (cover) {
            val clip = KitePath.Builder().apply { rectangle(left, bottom, width, height) }.build()
            canvas.pushClip(clip, deviceCtm, evenOdd = false)
        }
        try {
            if (svg != null) {
                val m = KiteMatrix(dw / intrinsicW, 0.0, 0.0, -dh / intrinsicH, x, y + dh)
                if (canvas is SvgPlacements) canvas.placed += svg to deviceCtm.concat(m)
                else svg.render(canvas, deviceCtm.concat(m), svgLoader(baseDir))
            } else if (image != null) {
                val m = KiteMatrix(dw, 0.0, 0.0, dh, x, y)
                canvas.drawImage(image, deviceCtm.concat(m))
            }
        } finally {
            if (cover) canvas.popClip()
        }
    }

    /** CSS 2.1, section 14.2: every inline fragment paints its own background, with its alpha (#253). */
    /** Paints formula [m] with its baseline at ([x], [baseline]) in the page's y-up space (#32). */
    private fun paintMath(canvas: KiteCanvas, ctm: KiteMatrix, m: PlacedMath, x: Double, baseline: Double) {
        for (item in m.box.items) when (item) {
            is MathItem.Glyphs -> canvas.drawGlyphs(
                item.glyphs, item.fontSize, unitsPerEm = 1000, hasOutlines = false, fontSpec = item.spec,
                textToDevice = ctm.concat(KiteMatrix.translation(x + item.x, baseline - item.y)).concat(KiteMatrix.scaling(1.0, item.scaleY)),
                color = m.color, alpha = 1.0, blendMode = KiteBlendMode.Normal,
            )
            is MathItem.Rule -> {
                val left = x + item.x
                val top = baseline - item.y
                val rect = KitePath.Builder().apply {
                    moveTo(left, top); lineTo(left + item.width, top); lineTo(left + item.width, top - item.height); lineTo(left, top - item.height); close()
                }.build()
                canvas.fillPath(rect, ctm, m.color, evenOdd = false)
            }
            is MathItem.Stroke -> {
                val line = KitePath.Builder().apply {
                    item.points.forEachIndexed { i, (px, py) -> if (i == 0) moveTo(x + px, baseline - py) else lineTo(x + px, baseline - py) }
                }.build()
                canvas.strokePath(line, ctm, m.color, item.width, lineCap = 1, lineJoin = 1)
            }
        }
    }

    private fun paintRunBackground(run: PlacedRun, canvas: KiteCanvas, ctm: KiteMatrix) {
        run.backgroundColor?.let {
            rectFill(canvas, ctm, 0.0, -0.2 * run.fontSize, run.paintWidth, run.fontSize, it.color, it.alpha)
        }
    }

    /**
     * CSS Text Decoration 3: a line keeps the colour and size of the element that draws
     * it (2.3, 2.5). An underline or an overline stays on the line's baseline unless that
     * element is raised itself; a line-through crosses the text it decorates (#271, #578). [at] places
     * the run's start at a baseline shifted toward line-over.
     */
    private fun paintRunLines(run: PlacedRun, canvas: KiteCanvas, vertical: Boolean, at: (shift: Double) -> KiteMatrix) {
        run.underline?.let {
            val ctm = at(if (it.raised) run.baselineShift else 0.0)
            rectFill(canvas, ctm, 0.0, underlineEm(it.position, vertical) * it.sizePt, run.paintWidth, it.sizePt * 0.05, it.color)
        }
        run.lineThrough?.let {
            rectFill(canvas, at(run.baselineShift), 0.0, 0.3 * run.fontSize, run.paintWidth, it.sizePt * 0.05, it.color)
        }
        run.overline?.let {
            val ctm = at(if (it.raised) run.baselineShift else 0.0)
            rectFill(canvas, ctm, 0.0, overlineEm(it.position, vertical) * it.sizePt, run.paintWidth, it.sizePt * 0.05, it.color)
        }
    }

    /**
     * Where an underline's lower edge sits, in ems of its decorating element up from the baseline,
     * toward line-over (CSS Text Decoration 3, 3.4, #508). `auto` keeps it just under the baseline,
     * where descenders cross it, and `under` clears the descenders of a 0.25 em deep face. Vertical
     * text puts `under` and `left` on the left, the line-under side, and `right` past the right edge
     * of the column, where an upright glyph's em box ends at [UPRIGHT_CENTER] + 0.5.
     */
    private fun underlineEm(position: UnderlinePosition, vertical: Boolean): Double = when {
        vertical && position.side == UnderlineSide.RIGHT -> UPRIGHT_CENTER + 0.55
        position.under || vertical && position.side == UnderlineSide.LEFT -> -0.3
        else -> -0.15
    }

    /**
     * Where an overline's lower edge sits, in ems of its decorating element up from the baseline,
     * toward line-over (CSS Text Decoration 3, 2.4, #578): just over the layout's em box, which ends
     * 0.8 em up in horizontal text and at the right edge of the column in vertical text. A vertical
     * underline that [position] moves to the right sends the overline to the left (3.4).
     */
    private fun overlineEm(position: UnderlinePosition, vertical: Boolean): Double = when {
        !vertical -> 0.8
        position.side == UnderlineSide.RIGHT -> -0.3
        else -> UPRIGHT_CENTER + 0.55
    }

    /**
     * Logical block position to the column's physical x. `vertical-rl` (the
     * usual tategaki) starts at the right edge and works left; `vertical-lr`
     * starts at the left and works right.
     */
    private fun columnX(page: PageRender, v: Double): Double {
        val span = v - page.startY
        return if (page.verticalLr) page.margin + span else displayWidth - page.margin - span
    }

    /**
     * Upright in vertical flow, as the run's text-orientation says: with `mixed` the full-width
     * (CJK) codepoints stand and the rest rotate (CSS Writing Modes 3, 5.1, #508).
     */
    private fun isUpright(g: io.github.yuroyami.kitepdf.core.font.TextGlyph, run: PlacedRun): Boolean = when (run.orientation) {
        TextOrientation.UPRIGHT -> true
        TextOrientation.SIDEWAYS -> false
        TextOrientation.MIXED -> g.text.isNotEmpty() && FontMetrics.isWide(codePointAt(g.text, 0))
    }

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

        fun fill(vFrom: Double, vTo: Double, uFrom: Double, uLen: Double, color: RgbColor, alpha: Double = 1.0) {
            if (vTo <= vFrom || uLen <= 0.0) return
            rectFill(canvas, ctm, colX(vTo), displayHeight - (uFrom + uLen), vTo - vFrom, uLen, color, alpha)
        }

        s.backgroundColor?.let { fill(topDoc, botDoc, yTopDisp, w, it.color, it.alpha) }

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
        val radii = s.radii?.resolve(w, box.borderBoxHeight)
        if (radii != null || s.shadows.isNotEmpty()) {
            paintShapedBox(box, canvas, ctm, xDev, radii, startY, bandBottom, yUp)
            return
        }

        s.backgroundColor?.let { rectFill(canvas, ctm, xDev, yUp(botDoc), w, yUp(topDoc) - yUp(botDoc), it.color, it.alpha) }
        if (s.backgroundLayers.isNotEmpty()) {
            // The layers are cut to the part of the box on this page, as the colour is.
            val band = KitePath.Builder().apply { rectangle(xDev, yUp(botDoc), w, yUp(topDoc) - yUp(botDoc)) }.build()
            canvas.pushClip(band, ctm, evenOdd = false)
            paintBackgroundLayers(box, canvas, ctm, xDev, yUp(box.bottom), xDev + w, yUp(box.y), null, yUp(botDoc), yUp(topDoc))
            canvas.popClip()
        }

        val eT = s.borderTop.effective; val eB = s.borderBottom.effective
        val eL = s.borderLeft.effective; val eR = s.borderRight.effective
        if (eT > 0) horizontalEdge(canvas, ctm, xDev, w, box.y, box.y + eT, startY, bandBottom, yUp, s.borderTop.color)
        if (eB > 0) horizontalEdge(canvas, ctm, xDev, w, box.bottom - eB, box.bottom, startY, bandBottom, yUp, s.borderBottom.color)
        if (eL > 0) rectFill(canvas, ctm, xDev, yUp(botDoc), eL, yUp(topDoc) - yUp(botDoc), s.borderLeft.color)
        if (eR > 0) rectFill(canvas, ctm, xDev + w - eR, yUp(botDoc), eR, yUp(topDoc) - yUp(botDoc), s.borderRight.color)
    }

    /**
     * A box with rounded corners or shadows: its outer shadows, then its background and its
     * border as shapes, cut to the page's band when the box goes on past it (#28). The border
     * ring takes one colour, that of the first edge with a width.
     */
    private fun paintShapedBox(
        box: LayoutBox, canvas: KiteCanvas, ctm: KiteMatrix, left: Double, radii: DoubleArray?,
        startY: Double, bandBottom: Double, yUp: (Double) -> Double,
    ) {
        val s = box.style
        val right = left + box.borderBoxWidth
        val top = yUp(box.y)
        val bottom = yUp(box.bottom)
        val sliced = box.y < startY || box.bottom > bandBottom
        if (sliced) {
            val band = KitePath.Builder().apply { rectangle(0.0, yUp(bandBottom), displayWidth, yUp(startY) - yUp(bandBottom)) }.build()
            canvas.pushClip(band, ctm, evenOdd = false)
        }
        try {
            paintShadows(canvas, ctm, s, left, bottom, right, top, radii)
            s.backgroundColor?.let { bg ->
                val shape = KitePath.Builder().apply { roundedRect(left, bottom, right, top, radii) }.build()
                canvas.fillPath(shape, ctm, bg.color, evenOdd = false, alpha = bg.alpha, blendMode = KiteBlendMode.Normal)
            }
            paintBackgroundLayers(box, canvas, ctm, left, bottom, right, top, radii, maxOf(bottom, yUp(bandBottom)), minOf(top, yUp(startY)))
            val eT = s.borderTop.effective; val eR = s.borderRight.effective
            val eB = s.borderBottom.effective; val eL = s.borderLeft.effective
            val edge = listOf(s.borderTop, s.borderRight, s.borderBottom, s.borderLeft).firstOrNull { it.effective > 0 }
            if (edge != null) {
                val ring = KitePath.Builder().apply {
                    roundedRect(left, bottom, right, top, radii)
                    // A border wider than the box fills it.
                    if (left + eL < right - eR && bottom + eB < top - eT) {
                        roundedRect(left + eL, bottom + eB, right - eR, top - eT, innerRadii(radii, eL, eT, eR, eB))
                    }
                }.build()
                canvas.fillPath(ring, ctm, edge.color, evenOdd = true, alpha = 1.0, blendMode = KiteBlendMode.Normal)
            }
        } finally {
            if (sliced) canvas.popClip()
        }
    }

    /**
     * [box]'s background layers, over its colour and under its border, the first on top (#503).
     * Each is sized and placed in the padding box, repeated as the style asks, and cut to the
     * border box, with that box's rounded corners [radii] (CSS Backgrounds 3, 3; #28). The box
     * is from ([left], [bottom]) to ([right], [top]) in the page's y-up space, and only its part
     * from [visibleBottom] to [visibleTop] shows on this page, so tiles go there alone.
     */
    private fun paintBackgroundLayers(
        box: LayoutBox, canvas: KiteCanvas, ctm: KiteMatrix,
        left: Double, bottom: Double, right: Double, top: Double, radii: DoubleArray?,
        visibleBottom: Double, visibleTop: Double,
    ) {
        val layers = box.style.backgroundLayers
        if (layers.isEmpty()) return
        val s = box.style
        val pl = left + s.borderLeft.effective
        val pr = right - s.borderRight.effective
        val pt = top - s.borderTop.effective
        val pb = bottom + s.borderBottom.effective
        if (pr <= pl || pt <= pb) return
        val area = BackgroundArea(left, right, maxOf(bottom, visibleBottom), minOf(top, visibleTop), pl, pb, pr, pt)
        if (area.visibleTop <= area.visibleBottom) return
        val border = KitePath.Builder().apply { roundedRect(left, bottom, right, top, radii) }.build()
        canvas.pushClip(border, ctm, evenOdd = false)
        try {
            // The first layer is on top, so the last paints first.
            for (layer in layers.asReversed()) when (val image = layer.image) {
                is CssBackgroundImage.LinearGradient -> paintGradientLayer(canvas, ctm, layer, image, s.color, area)
                is CssBackgroundImage.Url -> paintBackgroundImage(canvas, ctm, layer, image.url, area)
            }
        } finally {
            canvas.popClip()
        }
    }

    /**
     * Where a box's background layers go, in the page's y-up space: the border box across, from
     * [left] to [right], the part of it on this page, from [visibleBottom] to [visibleTop], and
     * the padding box, from ([pl], [pb]) to ([pr], [pt]), which places and sizes the layers.
     */
    private class BackgroundArea(
        val left: Double, val right: Double, val visibleBottom: Double, val visibleTop: Double,
        val pl: Double, val pb: Double, val pr: Double, val pt: Double,
    ) {
        val width: Double get() = pr - pl
        val height: Double get() = pt - pb

        /**
         * The tiles of [layer], [tw] by [th], that meet the visible part of the box, as the left
         * edges of their columns and the tops of their rows. A layer that does not repeat on an
         * axis has one there. Null past [MAX_BACKGROUND_TILES].
         */
        fun tiles(layer: CssBackgroundLayer, tw: Double, th: Double): Pair<List<Double>, List<Double>>? {
            // A percentage lines that point of the tile up with that point of the area. CSS y runs down.
            val x0 = pl + (if (layer.x.percent) (width - tw) * layer.x.value else layer.x.value)
            val top0 = pt - (if (layer.y.percent) (height - th) * layer.y.value else layer.y.value)
            fun starts(origin: Double, step: Double, from: Double, to: Double, repeat: Boolean): List<Double> {
                if (!repeat) return listOf(origin)
                var first = origin - kotlin.math.ceil((origin - from) / step) * step
                val out = ArrayList<Double>()
                while (first < to && out.size < MAX_BACKGROUND_TILES) { out += first; first += step }
                return out
            }
            val xs = starts(x0, tw, left, right, layer.repeatX)
            // Tops of the rows, from the top of the visible part down.
            val tops = starts(-top0, th, -visibleTop, -visibleBottom, layer.repeatY).map { -it }
            return if (xs.size * tops.size > MAX_BACKGROUND_TILES) null else xs to tops
        }
    }

    /**
     * A background picture, sized from its own size in CSS pixels where `background-size` says
     * auto, and tiled over the visible part of the box when it repeats.
     */
    private fun paintBackgroundImage(canvas: KiteCanvas, ctm: KiteMatrix, layer: CssBackgroundLayer, url: String, area: BackgroundArea) {
        // A stylesheet's url is absolute already, with a leading slash; a style attribute's is the document's.
        val path = EpubDocument.resolvePath(doc.chapterDir(chapter), url)
        val picture = doc.paintPicture(path, chapter)
        val image = picture as? KiteImageData
        val svg = picture as? SvgImage
        val iw = (svg?.width ?: image?.width?.toDouble() ?: return) * CSS_PX
        val ih = (svg?.height ?: image?.height?.toDouble() ?: return) * CSS_PX
        if (iw <= 0.0 || ih <= 0.0) return
        val areaW = area.width
        val areaH = area.height
        val size = layer.size
        val (tw, th) = when {
            size.cover -> maxOf(areaW / iw, areaH / ih).let { iw * it to ih * it }
            size.contain -> minOf(areaW / iw, areaH / ih).let { iw * it to ih * it }
            else -> {
                val w = size.width?.resolve(areaW)
                val h = size.height?.resolve(areaH)
                when {
                    w != null && h != null -> w to h
                    w != null -> w to w * ih / iw
                    h != null -> h * iw / ih to h
                    else -> iw to ih
                }
            }
        }
        if (tw <= 0.0 || th <= 0.0 || !tw.isFinite() || !th.isFinite()) return
        val (xs, tops) = area.tiles(layer, tw, th) ?: return
        for (x in xs) for (t in tops) {
            paintImage(canvas, ctm, image, svg, tw, th, x, t - th, ObjectFit.FILL, path.substringBeforeLast('/', ""))
        }
    }

    /**
     * A `linear-gradient` layer, tiled by its `background-size` (#503). A gradient has no size of
     * its own, so an auto side, `cover` and `contain` take the area's (CSS Backgrounds 3, 3.9).
     * A `currentColor` stop takes [current], the colour of the box.
     */
    private fun paintGradientLayer(
        canvas: KiteCanvas, ctm: KiteMatrix, layer: CssBackgroundLayer,
        gradient: CssBackgroundImage.LinearGradient, current: RgbColor, area: BackgroundArea,
    ) {
        val size = layer.size
        val whole = size.cover || size.contain
        val tw = if (whole) area.width else size.width?.resolve(area.width) ?: area.width
        val th = if (whole) area.height else size.height?.resolve(area.height) ?: area.height
        if (!(tw > 0.0) || !(th > 0.0) || !tw.isFinite() || !th.isFinite()) return
        val (xs, tops) = area.tiles(layer, tw, th) ?: return
        for (x in xs) for (t in tops) paintGradientTile(canvas, ctm, gradient, current, x, t - th, x + tw, t)
    }

    /**
     * One tile of a `linear-gradient`, from ([x0], [y0]) to ([x1], [y1]) in y-up space. Stops of
     * one alpha paint as one axial shading at that alpha. The shading has no alpha of its own, so
     * stops that differ in alpha paint in bands across the gradient line: a band between stops of
     * one alpha is a shading or a solid fill, and one whose alpha changes is a run of thin solid
     * strips, each of the colour the stops blend to there in premultiplied space, as CSS Images 3,
     * 3.5.3 says, so a fade to `transparent` keeps its colour (#503).
     */
    private fun paintGradientTile(
        canvas: KiteCanvas, ctm: KiteMatrix, gradient: CssBackgroundImage.LinearGradient, current: RgbColor,
        x0: Double, y0: Double, x1: Double, y1: Double,
    ) {
        val w = x1 - x0
        val h = y1 - y0
        val radians = gradient.angleFor(w, h) * kotlin.math.PI / 180.0
        val sin = kotlin.math.sin(radians)
        val cos = kotlin.math.cos(radians)
        // CSS Images 3, 3.1.1: the line runs through the centre, long enough that its ends touch the corners.
        val length = kotlin.math.abs(w * sin) + kotlin.math.abs(h * cos)
        if (length <= 0.0) return
        // 0 degrees points up, and y runs up here. The line starts at (sx, sy).
        val sx = (x0 + x1) / 2 - sin * length / 2
        val sy = (y0 + y1) / 2 - cos * length / 2
        fun along(t: Double) = doubleArrayOf(sx + sin * length * t, sy + cos * length * t)
        val stops = gradient.stops
        val colors = stops.map { it.color ?: current }
        val at = stopPositions(stops, length)
        val alpha = stops.first().alpha
        val tile = KitePath.Builder().apply { rectangle(x0, y0, w, h) }.build()
        if (stops.all { kotlin.math.abs(it.alpha - alpha) <= 1e-6 }) {
            if (alpha <= 0.0) return
            val shading = KiteShading.Axial(
                KiteColorSpace.DeviceRGB, background = null, bbox = null, coords = along(0.0) + along(1.0), domain = doubleArrayOf(0.0, 1.0),
                function = gradientFunction(colors, at) ?: return, extendStart = true, extendEnd = true,
            )
            canvas.fillShading(shading, ctm, tile, alpha = alpha, blendMode = KiteBlendMode.Normal)
            return
        }
        // The part of the tile between t0 and t1 along the line: a strip across the line, cut to the tile.
        fun band(t0: Double, t1: Double): KitePath? {
            val reach = w + h
            val a = along(t0)
            val b = along(t1)
            val corners = listOf(
                a[0] + cos * reach to a[1] - sin * reach, b[0] + cos * reach to b[1] - sin * reach,
                b[0] - cos * reach to b[1] + sin * reach, a[0] - cos * reach to a[1] + sin * reach,
            )
            val cut = clipToRect(corners, x0, y0, x1, y1)
            if (cut.size < 3) return null
            return KitePath.Builder().apply {
                moveTo(cut[0].first, cut[0].second)
                for (k in 1 until cut.size) lineTo(cut[k].first, cut[k].second)
                close()
            }.build()
        }
        fun solid(t0: Double, t1: Double, color: RgbColor, a: Double) {
            if (a <= 0.0 || t1 - t0 <= 1e-9) return
            band(t0, t1)?.let { canvas.fillPath(it, ctm, color, evenOdd = false, alpha = a, blendMode = KiteBlendMode.Normal) }
        }
        // Before the first stop and after the last, the colour stays flat.
        solid(0.0, at.first(), colors.first(), stops.first().alpha)
        for (k in 0 until stops.lastIndex) {
            val t0 = at[k]
            val t1 = at[k + 1]
            if (t1 - t0 <= 1e-9) continue
            val c0 = colors[k]
            val c1 = colors[k + 1]
            val a0 = stops[k].alpha
            val a1 = stops[k + 1].alpha
            if (kotlin.math.abs(a0 - a1) <= 1e-6) {
                if (a0 <= 0.0) continue
                if (c0 == c1) { solid(t0, t1, c0, a0); continue }
                val path = band(t0, t1) ?: continue
                val shading = KiteShading.Axial(
                    KiteColorSpace.DeviceRGB, background = null, bbox = null, coords = along(t0) + along(t1), domain = doubleArrayOf(0.0, 1.0),
                    function = KiteFunction.Type2(doubleArrayOf(0.0, 1.0), null, doubleArrayOf(c0.r, c0.g, c0.b), doubleArrayOf(c1.r, c1.g, c1.b), 1.0),
                    extendStart = true, extendEnd = true,
                )
                canvas.fillShading(shading, ctm, path, alpha = a0, blendMode = KiteBlendMode.Normal)
                continue
            }
            val strips = kotlin.math.ceil((t1 - t0) * length / GRADIENT_STRIP_PT).toInt().coerceIn(2, MAX_GRADIENT_STRIPS)
            for (i in 0 until strips) {
                val f = (i + 0.5) / strips
                val a = a0 + (a1 - a0) * f
                if (a <= 0.0) continue
                fun mix(v0: Double, v1: Double) = ((v0 * a0 * (1 - f) + v1 * a1 * f) / a).coerceIn(0.0, 1.0)
                solid(t0 + (t1 - t0) * i / strips, t0 + (t1 - t0) * (i + 1) / strips, RgbColor(mix(c0.r, c1.r), mix(c0.g, c1.g), mix(c0.b, c1.b)), a)
            }
        }
        solid(at.last(), 1.0, colors.last(), stops.last().alpha)
    }

    /**
     * Where each stop sits along the gradient line, from 0 to 1 (and past them for a stop outside
     * the line): a stop without a position halfway between its neighbours that have one, and no
     * stop before the one before it (CSS Images 3, 3.5.3).
     */
    private fun stopPositions(stops: List<GradientStop>, length: Double): DoubleArray {
        val positions = arrayOfNulls<Double>(stops.size)
        for ((i, stop) in stops.withIndex()) positions[i] = stop.position?.resolve(length)?.div(length)
        if (positions[0] == null) positions[0] = 0.0
        if (positions[stops.lastIndex] == null) positions[stops.lastIndex] = 1.0
        var i = 1
        while (i < stops.size) {
            if (positions[i] == null) {
                val start = i - 1
                var end = i
                while (positions[end] == null) end++
                val from = positions[start]!!
                val to = positions[end]!!
                for (k in start + 1 until end) positions[k] = from + (to - from) * (k - start) / (end - start)
                i = end
            }
            i++
        }
        var max = Double.NEGATIVE_INFINITY
        return DoubleArray(stops.size) { k -> maxOf(positions[k]!!, max).also { max = it } }
    }

    /** The colour of the gradient along its line, from 0 to 1: a straight blend between each pair of [colors], at [at]. */
    private fun gradientFunction(colors: List<RgbColor>, at: DoubleArray): KiteFunction? {
        // The function runs over 0 to 1: the colour before the first stop and after the last stays flat.
        val points = ArrayList<Pair<Double, RgbColor>>()
        if (at.first() > 0.0) points += 0.0 to colors.first()
        for (k in colors.indices) points += at[k].coerceIn(0.0, 1.0) to colors[k]
        if (at.last() < 1.0) points += 1.0 to colors.last()
        if (points.size < 2) return null
        fun rgb(c: RgbColor) = doubleArrayOf(c.r, c.g, c.b)
        val segments = (0 until points.size - 1).map { k ->
            KiteFunction.Type2(doubleArrayOf(0.0, 1.0), null, rgb(points[k].second), rgb(points[k + 1].second), 1.0)
        }
        if (segments.size == 1) return segments[0]
        val bounds = DoubleArray(points.size - 2) { k -> points[k + 1].first }
        val encode = DoubleArray(segments.size * 2) { k -> if (k % 2 == 0) 0.0 else 1.0 }
        return KiteFunction.Type3(doubleArrayOf(0.0, 1.0), null, segments, bounds, encode)
    }

    /**
     * The outer `box-shadow`s of a box, the first listed on top, outside its border box only
     * (CSS Backgrounds 3, 7.1.1). A blur is a few rings that fade out, not a real gaussian (#28).
     */
    private fun paintShadows(
        canvas: KiteCanvas, ctm: KiteMatrix, s: ComputedStyle,
        left: Double, bottom: Double, right: Double, top: Double, radii: DoubleArray?,
    ) {
        val outer = s.shadows.filter { !it.inset && it.alpha > 0.0 }
        if (outer.isEmpty()) return
        val outside = KitePath.Builder().apply {
            rectangle(-displayWidth, -displayHeight, displayWidth * 3, displayHeight * 3)
            roundedRect(left, bottom, right, top, radii)
        }.build()
        canvas.pushClip(outside, ctm, evenOdd = true)
        try {
            for (shadow in outer.asReversed()) {
                val color = shadow.color ?: s.color
                val rings = if (shadow.blur > 0.0) SHADOW_RINGS else 1
                for (k in 0 until rings) {
                    // Inside k + 1 rings the alpha is k + 1 shares of the shadow's own, so the blur
                    // fades evenly from its outer ring to the core. Rings of one colour stack alike in any order.
                    val before = shadow.alpha * k / rings
                    val after = shadow.alpha * (k + 1) / rings
                    val layer = 1.0 - (1.0 - after) / (1.0 - before)
                    // From half the blur outside the shadow's edge to half the blur inside it. CSS y runs down.
                    val grow = shadow.spread + if (rings == 1) 0.0 else shadow.blur / 2 - shadow.blur * k / (rings - 1)
                    val l = left - grow + shadow.x
                    val r = right + grow + shadow.x
                    val b = bottom - grow - shadow.y
                    val t = top + grow - shadow.y
                    if (r <= l || t <= b) continue
                    val shape = KitePath.Builder().apply { roundedRect(l, b, r, t, grownRadii(radii, grow)) }.build()
                    canvas.fillPath(shape, ctm, color, evenOdd = false, alpha = layer, blendMode = KiteBlendMode.Normal)
                }
            }
        } finally {
            canvas.popClip()
        }
    }

    private fun horizontalEdge(
        canvas: KiteCanvas, ctm: KiteMatrix, xDev: Double, w: Double, y0Doc: Double, y1Doc: Double,
        startY: Double, bandBottom: Double, yUp: (Double) -> Double, color: RgbColor,
    ) {
        val t = maxOf(y0Doc, startY); val b = minOf(y1Doc, bandBottom)
        if (b <= t) return
        rectFill(canvas, ctm, xDev, yUp(b), w, yUp(t) - yUp(b), color)
    }

    /**
     * A grey box, for a media element without a poster (#29). It draws no play button, because a
     * page plays nothing by itself: a player such as `kitepdf-media` draws its own control over
     * the box (#31). [left] and [bottom] are in the page's y-up space, as [paintImage] takes them.
     */
    private fun paintMediaPlaceholder(canvas: KiteCanvas, deviceCtm: KiteMatrix, left: Double, bottom: Double, width: Double, height: Double) {
        if (width <= 0.0 || height <= 0.0) return
        rectFill(canvas, deviceCtm, left, bottom, width, height, MEDIA_PLACEHOLDER)
    }

    /**
     * The `<video>` and `<audio>` elements on this page, in painting order, each with its box, its
     * sources and its flags (#29). A player that an app places over [EpubMedia.rect] plays what the
     * page shows the poster or the placeholder of.
     */
    public val media: List<EpubMedia> get() = buildMedia(laidOut())

    private fun buildMedia(page: PageRender): List<EpubMedia> = page.images.mapNotNull { box ->
        val info = box.media ?: return@mapNotNull null
        EpubMedia(
            imageRect(page, box),
            info.kind,
            info.sources.map { EpubMediaSource(it.href, it.type ?: doc.resourceType(it.href)) },
            info.poster, info.controls, info.autoplay, info.loop, info.muted, info.id,
        )
    }

    /**
     * The inline frames and the HTML objects on this page, in document order, each with its box
     * and the document it embeds (#40). The page keeps a frame's box empty and paints an object's
     * fallback children there, so an app can place a web view over [EpubEmbed.rect].
     */
    public val embeds: List<EpubEmbed> get() = buildEmbeds(laidOut())

    private fun buildEmbeds(page: PageRender): List<EpubEmbed> {
        // Where this page's share of the block axis ends: its height, or its width in vertical writing.
        val end = page.startY + (if (page.vertical) page.pageWidth else page.pageHeight) - 2 * page.margin
        return page.embedBoxes.mapNotNull { box ->
            val info = box.embed ?: return@mapNotNull null
            var whole = true
            val rect = if (box is ImageBox) imageRect(page, box) else {
                // An object's content box, cut to this page, as the engine lays it out on both axes.
                val s = box.style
                val inlineStart = page.margin + box.x + s.borderLeft.effective + s.paddingLeftPt
                val inlineSize = box.borderBoxWidth - s.borderLeft.effective - s.paddingLeftPt - s.paddingRightPt - s.borderRight.effective
                val contentTop = box.y + s.borderTop.effective + s.paddingTopPt
                val contentBottom = box.bottom - s.paddingBottomPt - s.borderBottom.effective
                val top = maxOf(contentTop, page.startY)
                val bottom = minOf(contentBottom, end)
                if (bottom <= top || inlineSize <= 0.0) return@mapNotNull null
                whole = top == contentTop && bottom == contentBottom
                if (page.vertical) {
                    val a = columnX(page, top)
                    val b = columnX(page, bottom)
                    io.github.yuroyami.kitepdf.core.KiteRectangle(minOf(a, b), inlineStart, maxOf(a, b), inlineStart + inlineSize)
                } else {
                    io.github.yuroyami.kitepdf.core.KiteRectangle(inlineStart, displayY(page, top), inlineStart + inlineSize, displayY(page, bottom))
                }
            }
            EpubEmbed(rect, info.kind, info.href, info.type, info.id, whole)
        }
    }

    /** The content box of the block image [box] on [page], in display space. */
    private fun imageRect(page: PageRender, box: ImageBox): io.github.yuroyami.kitepdf.core.KiteRectangle {
        val inset = imageInset(box.style)
        val (left, top) = if (page.vertical) {
            minOf(columnX(page, box.y + inset.blockStart), columnX(page, box.bottom - inset.blockEnd)) to page.margin + box.x + inset.inlineStart
        } else {
            (page.margin + box.x + inset.inlineStart) to displayY(page, box.y + inset.blockStart)
        }
        return io.github.yuroyami.kitepdf.core.KiteRectangle(left, top, left + box.drawWidth, top + box.drawHeight)
    }

    private fun rectFill(
        canvas: KiteCanvas, ctm: KiteMatrix, x: Double, yBottom: Double, w: Double, h: Double,
        color: RgbColor, alpha: Double = 1.0,
    ) {
        if (w <= 0.0 || h <= 0.0) return
        val path = KitePath.Builder().apply { rectangle(x, yBottom, w, h) }.build()
        canvas.fillPath(path, ctm, color, evenOdd = false, alpha = alpha, blendMode = KiteBlendMode.Normal)
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
    public val links: List<EpubLink> get() = doc.linksOf(chapter, index) { page -> buildLinks(page) }

    private fun buildLinks(page: PageRender): List<EpubLink> {
        val out = ArrayList<EpubLink>()
        for (line in page.lines) {
            // A vertical line is a column: the run extent goes down the page
            // and the line's own thickness goes across it.
            val acrossLow: Double
            val acrossHigh: Double
            if (page.vertical) {
                val a = columnX(page, line.yTop)
                val b = columnX(page, line.yTop + line.height)
                acrossLow = minOf(a, b); acrossHigh = maxOf(a, b)
            } else {
                acrossLow = displayY(page, line.yTop); acrossHigh = acrossLow + line.height
            }
            val runs = line.runs.filter { !it.isAnnotation && it.glyphs.isNotEmpty() }.sortedBy { it.x }
            var i = 0
            while (i < runs.size) {
                val href = runs[i].href
                if (href == null) { i++; continue }
                var j = i
                while (j + 1 < runs.size && runs[j + 1].href == href) j++
                val start = page.margin + runs[i].x
                val end = runEnd(page, runs[j])
                out.add(
                    EpubLink(
                        // A transformed box moves its links with its paint (#28).
                        rect = if (page.vertical) {
                            io.github.yuroyami.kitepdf.core.KiteRectangle(acrossLow, start, acrossHigh, end)
                        } else {
                            movedRect(io.github.yuroyami.kitepdf.core.KiteRectangle(start, acrossLow, end, acrossHigh), displayTransformAt(page, line.paintRank))
                        },
                        href = href,
                        kind = doc.linkKind(chapter, href),
                    ),
                )
                i = j + 1
            }
        }
        // A block lifted out of an inline <a> is part of that link, so its
        // whole box is clickable, cut to this page (#214).
        if (!page.vertical) {
            val pageBottom = page.startY + page.pageHeight - 2 * page.margin
            for (box in page.linkBoxes) {
                val href = box.linkHref ?: continue
                val top = maxOf(box.y, page.startY)
                val bottom = minOf(box.bottom, pageBottom)
                if (bottom <= top || box.borderBoxWidth <= 0.0) continue
                val left = page.margin + box.x
                out.add(
                    EpubLink(
                        rect = movedRect(
                            io.github.yuroyami.kitepdf.core.KiteRectangle(left, displayY(page, top), left + box.borderBoxWidth, displayY(page, bottom)),
                            displayTransformAt(page, box.decoRank),
                        ),
                        href = href,
                        kind = doc.linkKind(chapter, href),
                    ),
                )
            }
        }
        return out
    }

    private fun runEnd(page: PageRender, r: PlacedRun): Double =
        page.margin + r.x + r.glyphs.sumOf { it.advanceWidth } * r.fontSize / 1000.0

    /* ── accessibility ───────────────────────────────────────────────────── */

    /**
     * What this page says, in the order a reader that speaks would say it:
     * one item per block of text or per image, each carrying the role its
     * source element declared (`<h2>` is a heading, `<li>` a list item).
     *
     * Left out: anything marked `aria-hidden="true"` or `role="presentation"`,
     * and an image with `alt=""`, which is how authors mark decoration.
     *
     * Order is the page's own top-to-bottom, which is document order for
     * everything except boxes deliberately overlapped by absolute positioning.
     *
     * ```kotlin
     * for (item in page.readingOrder()) speak(item.role, item.text)
     * ```
     */
    override fun readingOrder(): List<KiteReadingItem> {
        val page = laidOut()
        val out = ArrayList<Pair<Double, KiteReadingItem>>()
        var owner: TextBlockBox? = null
        var speech: SpeechHint? = null
        var top = 0.0
        var words = ArrayList<String>()
        // Where the words are on the page, for a screen reader's focus (#427).
        var bounds: KiteRectangle? = null

        fun flushText() {
            val o = owner
            if (o != null && words.isNotEmpty()) {
                readingItem(o.semantics, words.joinToString(" "), speech, bounds)?.let { out.add(top to it) }
            }
            words = ArrayList()
            bounds = null
        }

        for (line in page.lines) {
            if (line.owner !== owner) { flushText(); owner = line.owner; speech = null; top = line.yTop }
            // A pronunciation splits the text, so its phoneme covers exactly its own span. A block
            // that a label replaces is one item, whatever its runs say (#39).
            val parts = if (owner?.semantics?.label != null) listOf(null to line.runs) else speechParts(line)
            for ((hint, runs) in parts) {
                if (hint !== speech) { flushText(); speech = hint; top = line.yTop }
                extractLine(page, line, runs)?.let { text ->
                    words.add(text.text)
                    val box = movedLine(text, displayTransformAt(page, line.paintRank)).bounds
                    bounds = bounds?.union(box) ?: box
                }
            }
            for (img in line.images) {
                if (img.alt?.isEmpty() == true) continue   // decorative
                val box = movedRect(inlineImageRect(page, line, img), displayTransformAt(page, line.paintRank))
                out.add(line.yTop to KiteReadingItem(KiteRole.IMAGE, img.alt.orEmpty(), bounds = box))
            }
        }
        flushText()

        for (img in page.images) {
            val sem = img.semantics ?: continue
            if (sem.hidden) continue
            val box = movedRect(imageRect(page, img), displayTransformAt(page, img.contentRank))
            out.add(img.y to KiteReadingItem(KiteRole.IMAGE, sem.label.orEmpty(), sourceType = sem.epubType, bounds = box))
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    /**
     * Where the painter draws the inline picture [img] of [line], in display space: on the
     * baseline of a horizontal line, or from the line-over side of a vertical one.
     */
    private fun inlineImageRect(page: PageRender, line: PositionedLine, img: PlacedImage): KiteRectangle {
        if (page.vertical) {
            val bandBottom = page.startY + (displayWidth - 2 * page.margin)
            val x = columnX(
                page,
                if (page.verticalLr) minOf(line.yTop + line.height, bandBottom) - line.ascent else line.yTop + line.ascent,
            )
            val top = page.margin + img.x
            return KiteRectangle(x, top, x + img.width, top + img.height)
        }
        val baseline = displayY(page, line.yTop + line.ascent)
        val left = page.margin + img.x
        return KiteRectangle(left, baseline - img.height, left + img.width, baseline)
    }

    private fun readingItem(sem: BoxSemantics?, text: String, speech: SpeechHint?, bounds: KiteRectangle?): KiteReadingItem? {
        if (sem?.hidden == true) return null
        val spoken = (sem?.label ?: text).trim()
        if (spoken.isEmpty()) return null
        return KiteReadingItem(
            sem?.role ?: KiteRole.TEXT, spoken, sem?.headingLevel ?: 0, sem?.epubType, speech?.phoneme, speech?.alphabet,
            bounds = bounds,
        )
    }

    /** The runs of [line] in reading order, cut where the pronunciation they belong to changes. */
    private fun speechParts(line: PositionedLine): List<Pair<SpeechHint?, List<PlacedRun>>> {
        val parts = ArrayList<Pair<SpeechHint?, List<PlacedRun>>>()
        var current = ArrayList<PlacedRun>()
        var hint: SpeechHint? = null
        for (run in line.runs.filter { !it.isAnnotation && it.glyphs.isNotEmpty() }.sortedBy { it.x }) {
            if (current.isNotEmpty() && run.speech !== hint) {
                parts += hint to current
                current = ArrayList()
            }
            hint = run.speech
            current += run
        }
        if (current.isNotEmpty()) parts += hint to current
        return parts
    }

    /* ── structured text (extraction / search) ───────────────────────────── */

    /** Built once per live chapter and dropped with its pages, so search over a book stays bounded. */
    override fun textContent(): KiteStructuredText =
        doc.structuredTextOf(chapter, index) { page -> buildStructuredText(page) }

    /**
     * Blocks = consecutive page lines sharing one owning [TextBlockBox];
     * lines rebuild their text from the placed runs (in x order, ruby
     * overlays excluded), restoring the collapsed inter-word spaces from the
     * pen gaps, since spaces are never drawn as glyphs.
     */
    private fun buildStructuredText(page: PageRender): KiteStructuredText {
        val blocks = ArrayList<KiteTextBlock>()
        var curOwner: TextBlockBox? = null
        var curLines = ArrayList<KiteTextLine>()
        fun flush() {
            if (curLines.isNotEmpty()) { blocks.add(KiteTextBlock(curLines)); curLines = ArrayList() }
        }
        for (line in page.lines) {
            if (line.owner !== curOwner) { flush(); curOwner = line.owner }
            extractLine(page, line)?.let { curLines += movedLine(it, displayTransformAt(page, line.paintRank)) }
        }
        flush()
        // The text an SVG draws reads where the SVG stands, before the first block below its own (#523).
        for (svg in svgTexts(page)) for (block in svg.text.blocks) {
            val top = block.lines.first().bounds.bottom
            val at = if (page.vertical) blocks.size else blocks.indexOfFirst { it.lines.first().bounds.bottom > top }.takeIf { it >= 0 } ?: blocks.size
            blocks.add(at, block)
        }
        return KiteStructuredText(blocks)
    }

    /**
     * The text of each `<svg>` element that [page] paints, in display space (#523): one written in
     * the chapter, or the root of an SVG spine item. An SVG file that an `<img>` shows is a picture,
     * and its text is not the page's, as in a browser. The page paints itself into a canvas that
     * keeps each SVG with its matrix, so the text lands where the picture does, moved by any
     * transform around it. A page without such an element costs nothing.
     */
    private fun svgTexts(page: PageRender): List<SvgTextContent> {
        val elements = HashSet<SvgImage>()
        for (box in page.images) if (box.zipPath.isEmpty()) box.svg?.let(elements::add)
        for (line in page.lines) for (im in line.images) if (im.zipPath.isEmpty()) im.svg?.let(elements::add)
        if (elements.isEmpty()) return emptyList()
        val placements = SvgPlacements()
        // Painting maps the page's y-up space to the device. This flip makes the device the display space.
        render(placements, displayToDeviceBase(), null)
        return placements.placed.filter { it.first in elements }.map { (svg, m) -> svg.textContent(m) }
    }

    /**
     * [line] moved by [m] when [m] only moves and scales, so its char edges stay in order along
     * it. The text of a turned, skewed or mirrored box stays where the layout put it (#28).
     */
    private fun movedLine(line: KiteTextLine, m: KiteMatrix?): KiteTextLine {
        if (m == null || line.vertical || m.b != 0.0 || m.c != 0.0 || m.a <= 0.0 || m.d <= 0.0) return line
        val edges = DoubleArray(line.charEdges.size) { m.a * line.charEdges[it] + m.e }
        return KiteTextLine(line.text, movedRect(line.bounds, m), edges, line.vertical, line.end)
    }

    private fun extractLine(page: PageRender, line: PositionedLine, only: List<PlacedRun> = line.runs): KiteTextLine? {
        val runs = only
            .filter { !it.isAnnotation && it.glyphs.isNotEmpty() }
            .sortedBy { it.x }
        // A formula reads as its linear text, spread over its width, where it stands in the line (#32).
        val maths = if (only === line.runs) line.maths.sortedBy { it.x } else emptyList()
        if (runs.isEmpty() && maths.isEmpty()) return null
        val sb = StringBuilder()
        val edges = ArrayList<Double>()
        var penEnd = Double.NaN
        var penSize = 0.0
        var nextMath = 0
        fun formulasBefore(limit: Double) {
            while (nextMath < maths.size && maths[nextMath].x < limit) {
                val m = maths[nextMath++]
                val x = page.margin + m.x
                if (!penEnd.isNaN() && x - penEnd > penSize * SPACE_GAP_EM && sb.isNotEmpty() && sb.last() != ' ') {
                    edges.add(penEnd); sb.append(' ')
                }
                for (k in m.text.indices) { edges.add(x + m.box.width * k / m.text.length); sb.append(m.text[k]) }
                penEnd = x + m.box.width
            }
        }
        for (run in runs) {
            formulasBefore(run.x)
            var x = page.margin + run.x
            // Spaces the layout kept, two or more in preserved white space or the ones a line
            // starts with, are each a space char spanning its share of their room (#576).
            val n = run.spacesBefore
            val leading = penEnd.isNaN() && sb.isEmpty() && only === line.runs
            if (n >= 2 && !penEnd.isNaN() || n >= 1 && leading) {
                val from = if (penEnd.isNaN()) x - run.spacesWidth else penEnd
                for (k in 0 until n) { edges.add(from + (x - from) * k / n); sb.append(' ') }
            } else if (!penEnd.isNaN() && x - penEnd > minOf(run.fontSize, penSize) * SPACE_GAP_EM && sb.isNotEmpty() && sb.last() != ' ') {
                // Words are separate runs with a pen gap where the collapsed space
                // was; restore it as one space char spanning the gap. The smaller of
                // the two sizes judges the gap, since either side may hold the space (#259).
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
            penSize = run.fontSize
        }
        formulasBefore(Double.MAX_VALUE)
        if (sb.isEmpty()) return null
        edges.add(penEnd)
        if (page.vertical) {
            // A column: the char edges run DOWN the page, and the line's own
            // extent is the column's width across it.
            val a = columnX(page, line.yTop)
            val b = columnX(page, line.yTop + line.height)
            return KiteTextLine(
                text = sb.toString(),
                bounds = io.github.yuroyami.kitepdf.core.KiteRectangle(
                    minOf(a, b), edges.first(), maxOf(a, b), edges.last(),
                ),
                charEdges = edges.toDoubleArray(),
                vertical = true,
                end = line.end,
            )
        }
        val top = displayY(page, line.yTop)
        return KiteTextLine(
            text = sb.toString(),
            // Display-space rect: y-min lives in [KiteRectangle.bottom] (see KiteStructuredText).
            bounds = io.github.yuroyami.kitepdf.core.KiteRectangle(edges.first(), top, edges.last(), top + line.height),
            charEdges = edges.toDoubleArray(),
            end = line.end,
        )
    }

    private companion object {
        /** Pen-gap threshold (in em) that reads as a collapsed word space. */
        const val SPACE_GAP_EM = 0.15
    }
}

/** The rings that stand in for the gaussian of a `box-shadow` blur (#28). */
private const val SHADOW_RINGS = 6

/** The background pictures a document keeps decoded (#28). */
private const val BACKGROUND_BYTES = 32L * 1024 * 1024

/** The most copies of a repeated background picture one box paints; a pattern of tiny tiles draws none. */
private const val MAX_BACKGROUND_TILES = 4096

/** How wide, in points, each strip of a gradient's alpha fade is at most, up to [MAX_GRADIENT_STRIPS] of them (#503). */
private const val GRADIENT_STRIP_PT = 2.0

/** The most strips one alpha fade of a gradient paints in. */
private const val MAX_GRADIENT_STRIPS = 64

/** The convex polygon [points] cut to the rectangle from ([x0], [y0]) to ([x1], [y1]), one edge at a time (Sutherland and Hodgman). */
private fun clipToRect(points: List<Pair<Double, Double>>, x0: Double, y0: Double, x1: Double, y1: Double): List<Pair<Double, Double>> {
    var poly = points
    // Each edge keeps the points where inside(p) holds, and cuts the polygon where it crosses the edge.
    fun cut(inside: (Pair<Double, Double>) -> Boolean, cross: (Pair<Double, Double>, Pair<Double, Double>) -> Pair<Double, Double>) {
        if (poly.isEmpty()) return
        val out = ArrayList<Pair<Double, Double>>()
        for (k in poly.indices) {
            val p = poly[k]
            val q = poly[(k + 1) % poly.size]
            val pin = inside(p)
            val qin = inside(q)
            if (pin) out += p
            if (pin != qin) out += cross(p, q)
        }
        poly = out
    }
    fun atX(p: Pair<Double, Double>, q: Pair<Double, Double>, x: Double) = x to p.second + (q.second - p.second) * (x - p.first) / (q.first - p.first)
    fun atY(p: Pair<Double, Double>, q: Pair<Double, Double>, y: Double) = p.first + (q.first - p.first) * (y - p.second) / (q.second - p.second) to y
    cut({ it.first >= x0 }) { p, q -> atX(p, q, x0) }
    cut({ it.first <= x1 }) { p, q -> atX(p, q, x1) }
    cut({ it.second >= y0 }) { p, q -> atY(p, q, y0) }
    cut({ it.second <= y1 }) { p, q -> atY(p, q, y1) }
    return poly
}

/** Points in a CSS pixel. */
private const val CSS_PX = 0.75

/** The grey of a media element without a poster. */
private val MEDIA_PLACEHOLDER = RgbColor(0.85, 0.85, 0.85)
