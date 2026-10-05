package io.github.yuroyami.kitepdf.compose

import io.github.yuroyami.kitepdf.core.KiteLineEnd
import io.github.yuroyami.kitepdf.core.KiteTextLine
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubEmbedKind
import io.github.yuroyami.kitepdf.epub.EpubFormatException
import io.github.yuroyami.kitepdf.epub.EpubLayout
import io.github.yuroyami.kitepdf.epub.EpubMediaKind
import io.github.yuroyami.kitepdf.epub.EpubOrientation
import io.github.yuroyami.kitepdf.epub.EpubPageSpread
import io.github.yuroyami.kitepdf.epub.EpubSettings
import io.github.yuroyami.kitepdf.epub.EpubSpread
import io.github.yuroyami.kitepdf.epub.EpubTargetKind
import kotlin.math.abs

/**
 * The results of the W3C EPUB test suite that KitePDF's API can decide (#497), by test.
 *
 * Each check opens its test book and answers whether KitePDF does what the test asks: true when
 * it passes, false when it fails. [EpubConformanceTest] holds each answer to the report, and the
 * docs page says in words what each one asserts.
 */
internal object EpubConformanceChecks {

    private val checks = LinkedHashMap<String, W3cTestBook.() -> Boolean>()

    val ids: Set<String> get() = checks.keys

    fun check(id: String, book: W3cTestBook): Boolean = checks.getValue(id)(book)

    private fun check(id: String, block: W3cTestBook.() -> Boolean) {
        require(checks.put(id, block) == null) { "two checks for $id" }
    }

    // ---- what the checks read ----

    private fun near(a: Double, b: Double, tolerance: Double = 0.75) = abs(a - b) <= tolerance

    /** Whether page [page] of [chapter] is [w] by [h] points. */
    private fun W3cTestBook.sizeIs(chapter: Int, w: Double, h: Double, page: Int = 0): Boolean =
        page(chapter, page).let { near(it.width, w) && near(it.height, h) }

    /** Whether every chapter is one pre-paginated page of [w] by [h] points. */
    private fun W3cTestBook.allFixedPages(w: Double, h: Double): Boolean =
        (0 until chapters).all { doc.renditionOf(it).layout == EpubLayout.PRE_PAGINATED && pages(it) == 1 && sizeIs(it, w, h) }

    /** The text of the glyph runs a page draws, whether or not its text layer has it. */
    private fun W3cTestBook.drawnText(chapter: Int, page: Int = 0): String =
        glyphRuns(chapter, page).joinToString(" ") { it.text }.replace(Regex("\\s+"), " ")

    /** The text of the glyph runs whose outlines come from a font embedded in the book. */
    private fun W3cTestBook.embeddedFontText(chapter: Int, page: Int = 0): String =
        glyphRuns(chapter, page).filter { it.hasOutlines }.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ")

    private fun RecordingCanvas.Call.Fill.isRgb(r: Double, g: Double, b: Double) =
        near(color.r, r, 0.02) && near(color.g, g, 0.02) && near(color.b, b, 0.02)

    /**
     * Whether [chapter] paints the grid of the `lay-*-xhtml-icb` tests over their red box: a 900
     * by 600 pixel box of two layered gradients in 50 pixel cells, so 18 by 12 lines of one pixel
     * across and as many down, in the text's black, on a white background that covers the red
     * box under it (#503).
     */
    private fun W3cTestBook.gridPaints(chapter: Int): Boolean {
        val fills = fills(chapter)
        val lines = fills.filter { it.isRgb(0.0, 0.0, 0.0) }.mapNotNull { it.path.bounds(it.ctm) }
        val across = lines.count { near(it.height, 0.75, 0.01) && near(it.width, 37.5, 0.01) }
        val down = lines.count { near(it.width, 0.75, 0.01) && near(it.height, 37.5, 0.01) }
        val red = fills.indexOfLast { it.isRgb(1.0, 0.0, 0.0) }
        val white = fills.indexOfLast { fill -> fill.isRgb(1.0, 1.0, 1.0) && fill.path.bounds(fill.ctm)?.let { near(it.width, 675.0) && near(it.height, 450.0) } == true }
        return across == 18 * 12 && down == 18 * 12 && red in 0 until white
    }

    /** The spreads the viewer pairs, as the chapter of each page, for books of one page per chapter. */
    private fun W3cTestBook.spreadChapters(landscape: Boolean = true): List<List<Int>> = spreads(landscape).map { s -> s.map { it.first } }

    /** Whether opening the book with [bytes] throws the error a broken container gives. */
    private fun refuses(bytes: ByteArray): Boolean = try {
        EpubDocument.open(bytes)
        false
    } catch (_: EpubFormatException) {
        true
    }

    init {
        openContainerFormat()
        packageDocuments()
        publicationResources()
        coreMediaTypes()
        manifestFallbacks()
        contentDocuments()
        internationalization()
        fixedLayout()
        prePaginatedLayout()
        rollLayout()
        navigationDocuments()
        structuralSemantics()
        scripting()
        mediaOverlays()
    }

    private fun openContainerFormat() {
        check("ocf-font_obfuscation") { "unusual TrueType font" in embeddedFontText(0) }
        check("ocf-font_obfuscation_bis") { embeddedFontText(0).isEmpty() && "font" in text(0) }
        check("ocf-metainf-inc") { "Test passes if this page of the book can be accessed without any error." in text(0) }
        check("ocf-metainf-manifest") { chapters == 1 && doc.chapterPath(0) == "EPUB/content_001.xhtml" && "Test fails" !in chapterText(0) }
        check("ocf-package_arbitrary") { doc.chapterPath(0) == "FOO/BAR/content_001.xhtml" && "Test passes if you see this." in text(0) && "Test fails" !in text(0) }
        check("ocf-package_multiple") { doc.chapterPath(0) == "FOO/BAR/content_001.xhtml" && "Test passes if you see this." in text(0) && "Test fails" !in text(0) }
        for (id in listOf("ocf-url_link-leaking-relative", "ocf-url_link-path-absolute", "ocf-url_link-relative")) {
            check(id) { images(0).any { it.image.width == 1000 && it.image.height == 562 } }
        }
        check("ocf-url_manifest") { doc.chapterPath(0) == "EPUB/foo/content_001.xhtml" && "Test passes if this page opens." in text(0) }
        check("ocf-url_relative") { doc.chapterPath(0) == "foo/BAR/qux/content_001.xhtml" && "Test passes if this page opens." in text(0) }
        check("ocf-url_parse-leaking-relative") {
            runScripts()
            val text = text(0)
            val root = Regex("(epub://[0-9a-f]+/)").find(text)?.value
            root != null && "${root}media/imgs/monastery.jpg" in text && runScripts().failures.isEmpty()
        }
        check("ocf-url_parse-path-absolute") {
            runScripts()
            val text = text(0)
            val root = Regex("(epub://[0-9a-f]+/)").find(text)?.value
            root != null && "${root}media/imgs/monastery.jpg" in text && runScripts().failures.isEmpty()
        }
        check("ocf-url_origin") {
            // Two readers' copies of one book: their origins must differ.
            val first = W3cTestBook(folder).also { it.runScripts() }
            val second = W3cTestBook(folder).also { it.runScripts() }
            val origin = Regex("Origin URL: (\\S+)")
            val a = origin.find(first.text(0))?.groupValues?.get(1)
            val b = origin.find(second.text(0))?.groupValues?.get(1)
            first.close()
            second.close()
            a != null && b != null && a != b
        }
        // The folder can only hold Deflate; the suite means a container in another method, or split.
        check("ocf-zip-comp") { refuses(ZipSurgery.withMethod(bytes, 12)) }
        check("ocf-zip-mult") { refuses(ZipSurgery.asSecondDisk(bytes)) }
    }

    private fun packageDocuments() {
        check("pkg-collections-unknown") { chapters == 1 && "Test passes if the EPUB opens." in text(0) && doc.epubMetadata.title == "pkg-collections-unknown" }
        check("pkg-creator-order") { doc.epubMetadata.creators == listOf("Dave Cramer", "Wendy Reid", "Dan Lazin", "Ivan Herman", "Brady Duga") }
        check("pkg-manifest-unknown") { chapters == 1 && "Test passes if the EPUB opens." in text(0) }
        check("pkg-manifest-unlisted-resource") { images(0).isEmpty() }
        check("pkg-meta-unknown") { "Test passes if the EPUB opens." in text(0) && doc.epubMetadata.title == "pkg-meta-unknown" }
        check("pkg-meta-whitespace") { doc.epubMetadata.creators == listOf("Dave Cramer") && doc.epubMetadata.title == "pkg-meta-whitespace" }
        check("pkg-spine-duplicate-item-hyperlink") {
            page(0).links.map { it.href } == listOf("EPUB/content_002.xhtml") && doc.pageOf("EPUB/content_002.xhtml") == 1
        }
        check("pkg-spine-duplicate-item-rendering") {
            chapters == 4 && (1..3).all { pages(it) == 1 && "This document occurs three times in the spine." in text(it) }
        }
        check("pkg-spine-duplicate-item-ui") {
            (1..3).all { c -> doc.bookmarkOf(io.github.yuroyami.kitepdf.core.KiteLocation(c, 0)).chapter == c }
        }
        check("pkg-spine-nonlinear-activation") {
            val href = page(0).links.single().href
            href == "EPUB/content_002.xhtml" && doc.bookmarkOf(href)?.chapter == 1 &&
                "Test passes if following the link opens this page." in text(1)
        }
        check("pkg-spine-order") {
            listOf("EPUB/d-content_001.xhtml", "EPUB/c-content_002.xhtml", "EPUB/b-content_003.xhtml", "EPUB/a-content_004.xhtml") ==
                (0 until chapters).map { doc.chapterPath(it) }
        }
        check("pkg-spine-order-svg") { chapters == 4 && (0 until 4).all { "Page${it + 1}" in drawnText(it).replace(" ", "") } }
        check("pkg-spine-unknown") { "Test passes if the EPUB opens." in text(0) }
        check("pkg-title-order") { doc.epubMetadata.title == "pkg-title-order" && doc.metadata.title == "pkg-title-order" }
        check("pkg-unique-id") { doc.epubMetadata.identifier == "pkg-unique-id" && doc.epubMetadata.title == "pkg-unique-id" }
        check("pkg-unique-id_duplicate") { doc.epubMetadata.identifier == "pkg-unique-id" && doc.epubMetadata.title == "pkg-unique-id_duplicate" }
        check("pkg-version-backward") { "Test passes if the EPUB opens." in text(0) }
    }

    private fun publicationResources() {
        check("pub-data-urls_browsing-context") { images(0).isNotEmpty() }
        // The data URL is an SVG, drawn as its paths: the plum outline of its badge.
        check("pub-data-urls_top-level-content") { chapters == 2 && fills(0).any { it.isRgb(0x65 / 255.0, 0x2d / 255.0, 0x59 / 255.0) } }
        check("pub-file-urls") {
            val frames = (0 until pages(0)).flatMap { page(0, it).embeds }.filter { it.kind == EpubEmbedKind.FRAME }
            frames.size == 3 && frames.all { it.href.startsWith("file:") && doc.resource(it.href) == null }
        }
        check("pub-xml-external-id") {
            // The DOCTYPE's internal subset ends at its ]>, and the external entity it declares stands for nothing (#571).
            val text = chapterText(0)
            "fails" !in text && "]>" !in text && "&xxe;" !in text
        }
        check("pub-xml-non-validating_comment") { (0 until chapters).map { doc.chapterPath(it) } == listOf("EPUB/content_001.xhtml", "EPUB/content_002.xhtml") }
        check("sec-untrusted-consent_network") {
            // A book opened with the default settings fetches nothing.
            val pages = 0 until pages(0)
            val stylesheet = pages.flatMap { glyphRuns(0, it) }.filter { it.text.trim() == "Stylesheet" }
            pages.all { images(0, it).isEmpty() } && doc.remoteArrivals.value == 0 && stylesheet.isNotEmpty() &&
                stylesheet.none { it.color.r > 0.5 && it.color.g < 0.3 }
        }
        check("sec-untrusted-consent_scripting") { "No script has been executed." in text(0) }
    }

    private fun coreMediaTypes() {
        for (id in listOf("pub-cmt-gif", "pub-cmt-jpeg", "pub-cmt-png", "pub-cmt-webp", "pub-cmt-avif", "pub-cmt-jxl")) {
            check(id) { images(0).size == 1 }
        }
        check("pub-cmt-svg") { fills(0).count { it.isRgb(1.0, 0.0, 0.0) } >= 2 }
        for ((id, file) in listOf("pub-cmt-mp3" to "EPUB/aud/001.mp3", "pub-cmt-mp4" to "EPUB/aud/001.m4a", "pub-cmt-opus" to "EPUB/aud/001.opus")) {
            check(id) { page(0).media.any { m -> m.kind == EpubMediaKind.AUDIO && m.controls && m.sources.any { it.href == file } } }
        }
    }

    private fun manifestFallbacks() {
        // Nothing to show: the item is dropped or stays empty, and its bytes never lay out as text.
        // The test allows an error when the book is taken in, so refusing it passes, as does a spine item that shows nothing.
        check("pub-foreign_bad-fallback") { opened.exceptionOrNull() is EpubFormatException || chapterText(0).isBlank() }
        check("pub-foreign_image") { images(0).any { it.image.width == 512 && it.image.height == 512 } }
        for (id in listOf("pub-foreign_json-spine", "pub-foreign_xml-spine", "pub-foreign_xml-suffix-spine")) {
            check(id) { doc.chapterPath(0) == "EPUB/content_001.xhtml" && "Test passes if you see this text." in text(0) }
        }
    }

    private fun contentDocuments() {
        for ((id, format) in listOf("cnt-css-fonts_ot" to "OpenType", "cnt-css-fonts_tt" to "TrueType", "cnt-css-fonts_woff" to "WOFF", "cnt-css-fonts_woff2" to "WOFF2")) {
            check(id) { "unusual font in $format format" in embeddedFontText(0) && "Test passes" !in embeddedFontText(0) }
        }
        check("cnt-mathml-support") {
            val runs = glyphRuns(0)
            val two = runs.lastOrNull { it.text.trim() == "2" }
            val b = runs.lastOrNull { "b" in it.text && "Test" !in it.text }
            // Text space runs up the page, so the raised exponent has the larger baseline.
            two != null && b != null && two.fontSize < b.fontSize && two.textToDevice.f > b.textToDevice.f + 0.2 * b.fontSize
        }
        check("cnt-svg-css") { fills(0).count { it.alpha < 0.9 } > 20 }
        check("cnt-svg-css-inclusion") { fills(0).count { it.alpha < 0.9 } > 20 }
        check("cnt-svg-css-reference") { fills(0).count { it.isRgb(0.0, 128 / 255.0, 0.0) } >= 2 && fills(0).none { it.alpha < 0.9 } }
        check("cnt-svg-embedded") { fills(0).count { it.isRgb(1.0, 0.0, 0.0) } >= 2 }
        check("cnt-svg-support") { fills(0).count { it.isRgb(1.0, 0.0, 0.0) } >= 2 && "Testpasses" in drawnText(0).replace(" ", "") }
        check("cnt-xhtml-support") { "Test passes if you see this." in text(0) }
        // The last line of each of the seven samples, which is the line before the next numbered
        // sentence, sits where its -epub-text-align-last puts it: auto, start, end in right-to-left
        // text and left at the left edge, then right, center and justify (#508). The samples have a
        // margin, so their edges are those of the lines indented from the page's.
        check("css-epub-text-align-last") {
            val lines = textLines(0)
            val starts = (2..7).map { n -> lines.indexOfFirst { it.text.trimStart().startsWith("$n. This test passes") } }
            val last = starts.map { lines[it - 1].bounds } + lines.last().bounds
            val pageLeft = lines.minOf { it.bounds.left }
            val samples = lines.filter { it.bounds.left > pageLeft + 1 }
            val left = samples.minOf { it.bounds.left }
            val right = samples.maxOf { it.bounds.right }
            starts.all { it > 0 } && (0..3).all { near(last[it].left, left) } && near(last[4].right, right) &&
                near((last[5].left + last[5].right) / 2, (left + right) / 2) && near(last[6].left, left) && near(last[6].right, right) &&
                (0..5).all { last[it].right - last[it].left < right - left - 1 }
        }
        // Three samples with a margin, so their lines are those indented from the page's: `normal`
        // and `keep-all` keep every word of English text whole, and `break-all` breaks inside words
        // and fills each line to its edge (#508).
        check("css-epub-word-break") {
            val lines = textLines(0)
            val pageLeft = lines.minOf { it.bounds.left }
            val marks = (1..3).map { n -> lines.indexOfFirst { it.text.trimStart().startsWith("$n. This test passes") } }
            val samples = (0..2).map { n ->
                if (marks.any { it < 0 }) emptyList()
                else lines.subList(marks[n], if (n < 2) marks[n + 1] else lines.size).filter { it.bounds.left > pageLeft + 1 }
            }
            val inWord = samples.map { s -> s.count { it.end == KiteLineEnd.NONE || it.end == KiteLineEnd.HYPHEN } }
            val all = samples[2]
            val left = all.minOfOrNull { it.bounds.left } ?: 0.0
            val right = all.maxOfOrNull { it.bounds.right } ?: 0.0
            samples.all { it.size >= 3 } && inWord[1] == 0 && samples[0].none { it.end == KiteLineEnd.NONE } &&
                all.count { it.end == KiteLineEnd.NONE } > 0 && all.dropLast(1).all { it.bounds.right > right - (right - left) * 0.05 }
        }
        // The four samples break the same two Japanese sentences, which put 々 and ぁ at the same place.
        // Over many widths of the page, as the book asks, no line runs past the page's edge, `auto`
        // and `strict` start no line with either, `normal` starts some with ぁ and none with 々, and
        // `loose` starts some with each (CSS Text 3, 5.3, #508).
        check("css-epub-line-break") {
            val labels = listOf("auto:", "loose:", "normal:", "strict:")
            val starts = List(labels.size) { HashSet<Char>() }
            var inside = true
            // From a page that holds a few characters a line: a narrower one breaks every pair.
            for (width in 160..400 step 3) {
                val doc = EpubDocument.open(bytes, EpubSettings(pageWidth = width.toDouble(), pageHeight = 2000.0))
                var sample = -1
                for (p in 0 until doc.pageCountIn(0)) {
                    for (line in doc.page(io.github.yuroyami.kitepdf.core.KiteLocation(0, p)).textContent().blocks.flatMap { it.lines }) {
                        val t = line.text.trim()
                        if (t in labels) { sample = labels.indexOf(t); continue }
                        val first = t.firstOrNull() ?: continue
                        if (sample < 0 || first.code < 0x3000) continue
                        starts[sample] += first
                        inside = inside && line.bounds.right <= width
                    }
                }
            }
            val (auto, loose, normal, strict) = starts
            inside && listOf(auto, strict).all { '\u3005' !in it && '\u3041' !in it } && '\u3041' in normal && '\u3005' !in normal &&
                '\u3041' in loose && '\u3005' in loose
        }
        // The sample sets its digits and letters full-width, so in the vertical chapter they stand
        // upright as the Japanese beside them does (#508).
        check("css-epub-text-transform") {
            val wide = (0 until pages(0)).flatMap { glyphRuns(0, it) }.filter { r -> r.text.any { it in '\uFF10'..'\uFF5A' } }
            textLines(0).any { "\uFF11\uFF12\uFF13\uFF14\uFF15\uFF16\uFF41\uFF42\uFF43\uFF44\uFF45\uFF46" in it.text } &&
                wide.isNotEmpty() && wide.none { abs(it.textToDevice.b) > 1e-9 }
        }
        check("css-epub-writing-mode") { verticalChapter(1) && verticalChapter(2) && !verticalChapter(0) }
        // Paragraph 1 keeps the mixed rule, Japanese upright and English turned; paragraph 2 stands its
        // English up, a letter at a time; paragraphs 3 and 4 turn their Japanese with the English it
        // runs into, so a turned run holds Japanese letters (#508).
        check("css-epub-text-orientation") {
            verticalChapter(1) &&
                turnedRun(1, turned = false) { l -> l.all { it.isCjk() } } && turnedRun(1, turned = true) { l -> l.none { it.isCjk() } } &&
                turnedRun(1, turned = false) { l -> l.none { it.isCjk() } } && turnedRun(1, turned = true) { l -> l.any { it.isCjk() } }
        }
        // Chapter 1 keeps the underline at the baseline, where descenders cross it, and chapter 2
        // sets it under them; the layout's em box reaches 0.2 em below the baseline. Chapters 3 and
        // 5 are vertical, the line left of the column and then right of it (#508).
        check("css-epub-text-underline-position") {
            val auto = underlineDepth(0)
            val under = underlineDepth(1)
            auto != null && auto >= 0.0 && auto < 0.2 && under != null && under >= 0.2 &&
                verticalChapter(2) && underlineSide(2) == -1 && verticalChapter(4) && underlineSide(4) == 1
        }
    }

    private fun RecordingCanvas.Call.Fill.points(): List<Pair<Double, Double>> = path.segments.mapNotNull {
        when (it) {
            is KitePath.Segment.MoveTo -> ctm.transformPoint(it.x, it.y)
            is KitePath.Segment.LineTo -> ctm.transformPoint(it.x, it.y)
            else -> null
        }
    }

    /**
     * How far under the baseline the top of the shallowest underline of [chapter] sits, in ems of
     * the text it runs under, or null when no line runs under a glyph run. A line fills a thin
     * rectangle in its run's own text space.
     */
    private fun W3cTestBook.underlineDepth(chapter: Int): Double? {
        val calls = calls(chapter)
        val runs = calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        return calls.filterIsInstance<RecordingCanvas.Call.Fill>().mapNotNull { fill ->
            val run = runs.firstOrNull { it.textToDevice == fill.ctm } ?: return@mapNotNull null
            val toText = fill.ctm.invert() ?: return@mapNotNull null
            -fill.points().maxOf { (x, y) -> toText.transformY(x, y) } / run.fontSize
        }.minOrNull()
    }

    /**
     * Which side of the first column of a vertical [chapter] its underline runs on: -1 left of the
     * glyphs, 1 right of them, 0 across them or with no line. The first column of `vertical-rl`
     * text is the rightmost, and so is its line, on either side.
     */
    private fun W3cTestBook.underlineSide(chapter: Int): Int {
        val calls = calls(chapter)
        val glyphs = calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().filter { it.text.isNotBlank() }.map { run ->
            val advance = run.glyphs.sumOf { it.advanceWidth } * run.fontSize / 1000.0
            val xs = listOf(run.textToDevice.transformX(0.0, 0.0), run.textToDevice.transformX(advance, 0.0))
            xs.min()..xs.max()
        }
        val right = glyphs.maxOfOrNull { it.endInclusive } ?: return 0
        val column = glyphs.filter { abs(it.endInclusive - right) < 0.5 }
        val line = calls.filterIsInstance<RecordingCanvas.Call.Fill>().map { f -> f.points().map { it.first } }
            .maxByOrNull { it.max() } ?: return 0
        return when {
            line.max() <= column.minOf { it.start } -> -1
            line.min() >= column.maxOf { it.endInclusive } -> 1
            else -> 0
        }
    }

    /**
     * Whether a chapter's first page draws its text in columns: one glyph run follows the last
     * further down the same column more often than further along the same line. Text space runs
     * up the page, so down is a smaller y.
     */
    private fun W3cTestBook.verticalChapter(chapter: Int): Boolean {
        var down = 0
        var across = 0
        for ((a, b) in glyphRuns(chapter).filter { it.text.isNotBlank() }.zipWithNext()) {
            val dx = b.textToDevice.e - a.textToDevice.e
            val dy = b.textToDevice.f - a.textToDevice.f
            if (abs(dx) < 1 && dy < 0) down++ else if (abs(dy) < 1 && dx > 0) across++
        }
        return down > across
    }

    /**
     * Whether some glyph run of [chapter], on any of its pages, draws letters that [letters]
     * accepts, turned sideways or upright. A turned run's matrix has a rotation in it.
     */
    private fun W3cTestBook.turnedRun(chapter: Int, turned: Boolean, letters: (String) -> Boolean): Boolean =
        (0 until pages(chapter)).flatMap { glyphRuns(chapter, it) }.any { run ->
            val l = run.text.filter { it.isLetter() }
            l.isNotEmpty() && letters(l) && (abs(run.textToDevice.b) > 1e-9) == turned
        }

    /** A letter of CJK script, from the radicals up. */
    private fun Char.isCjk(): Boolean = code >= 0x2E80

    /** Every line of [chapter], page after page. */
    private fun W3cTestBook.textLines(chapter: Int): List<KiteTextLine> =
        (0 until pages(chapter)).flatMap { p -> page(chapter, p).textContent().blocks.flatMap { it.lines } }

    private fun internationalization() {
        // A title or creator reads right to left by its own dir, else the package's, else by its
        // first strong character, which is the Latin C of "CSS:" in the titles (#510).
        check("pkg-dir-auto_root-rtl") { doc.epubMetadata.title!!.startsWith("CSS:") && !doc.epubMetadata.titleRightToLeft }
        check("pkg-dir-auto_root-unset") { doc.epubMetadata.title!!.startsWith("CSS:") && !doc.epubMetadata.titleRightToLeft }
        check("pkg-dir_but_not_content") { "Test passes if the following list is not rendered right-to-left" in text(0) }
        check("pkg-dir_creator-rtl") { doc.epubMetadata.creators == listOf("Dave Cramer") && doc.epubMetadata.creatorsRightToLeft == listOf(true) }
        check("pkg-dir_rtl-root-ltr") { doc.epubMetadata.title!!.startsWith("CSS:") && doc.epubMetadata.titleRightToLeft }
        check("pkg-dir_rtl-root-unset") { doc.epubMetadata.title!!.startsWith("CSS:") && doc.epubMetadata.titleRightToLeft }
        check("pkg-dir_unset-root-rtl") { doc.epubMetadata.title!!.startsWith("CSS:") && doc.epubMetadata.titleRightToLeft }
        check("pkg-dir_unset-root-unset") { doc.epubMetadata.title!!.startsWith("CSS:") && !doc.epubMetadata.titleRightToLeft }
        check("pkg-lang_but_not_content") { "“Le mieux est l’ennemi du bien”" in text(0) || "“Le mieux est l'ennemi du bien”" in text(0) }
        check("pkg-spine-progression-default") { doc.epubMetadata.rightToLeft }
        check("pkg-spine-progression-pre-paginated") { !doc.epubMetadata.rightToLeft && spreadChapters() == listOf(listOf(0, 1), listOf(2, 3)) && allFixedPages(675.0, 450.0) }
        check("pkg-spine-progression_ltr") { !doc.epubMetadata.rightToLeft && spreadChapters() == listOf(listOf(0, 1), listOf(2, 3)) }
        check("pkg-spine-progression_rtl") { doc.epubMetadata.rightToLeft && spreadChapters() == listOf(listOf(0, 1), listOf(2, 3)) }
    }

    private fun fixedLayout() {
        check("fxl-page-spread-break") { allFixedPages(675.0, 450.0) && spreadChapters(true) == listOf(listOf(0, 1), listOf(2, 3)) }
        check("fxl-page-spread-center") { doc.renditionOf(0).pageSpread == EpubPageSpread.CENTER && spreadChapters() == listOf(listOf(0), listOf(1, 2), listOf(3)) }
        check("fxl-spine-overrides_behave-as-global") {
            doc.renditionOf(0).layout == EpubLayout.PRE_PAGINATED && pages(0) == 1 && sizeIs(0, 750.0, 450.0) &&
                doc.renditionOf(1).layout == EpubLayout.REFLOWABLE && pages(1) > 1
        }
        check("fxl-spine-overrides_behave-as-global-bis") {
            doc.renditionOf(0).layout == EpubLayout.REFLOWABLE && doc.renditionOf(2).layout == EpubLayout.REFLOWABLE &&
                doc.renditionOf(1).layout == EpubLayout.PRE_PAGINATED && pages(1) == 1 && sizeIs(1, 750.0, 450.0)
        }
        check("fxl-spine-overrides_duplicate") { doc.renditionOf(1).layout == EpubLayout.REFLOWABLE }
        check("lay-fxl-layout-default") { doc.epubMetadata.rendition.layout == EpubLayout.REFLOWABLE && pages(0) > 1 }
        check("lay-fxl-layout-pre-paginated") { allFixedPages(675.0, 450.0) && (0 until 4).all { "Page ${it + 1}" in text(it) } }
        check("lay-fxl-layout-pre-paginated-spreads") { spreadChapters(true) == listOf(listOf(0, 1), listOf(2, 3)) && spreadChapters(false) == listOf(listOf(0, 1), listOf(2, 3)) }
        check("lay-fxl-orientation-default") { (0 until chapters).all { doc.renditionOf(it).orientation == EpubOrientation.AUTO } }
        check("lay-fxl-page-spread-combined") { spreadChapters() == listOf(listOf(0), listOf(1, 2), listOf(3)) }
        check("lay-fxl-page-spread-left") { spreadChapters() == listOf(listOf(0), listOf(1, 2), listOf(3)) }
        check("lay-fxl-page-spread-right") { spreadChapters() == listOf(listOf(0), listOf(1, 2), listOf(3)) && spreadSides().first() == SpreadSide.RIGHT }
        check("lay-page-layout-both-spread") { spreads().last() == listOf(4 to 0) && spreadSides().last() == SpreadSide.LEFT }
        check("lay-fxl-spread-auto") { doc.epubMetadata.rendition.spread == EpubSpread.AUTO && spreadChapters() == listOf(listOf(0, 1), listOf(2, 3)) }
        check("lay-fxl-spread-both") {
            doc.renditionOf(0).spread == EpubSpread.BOTH && spreadChapters(true) == listOf(listOf(0, 1), listOf(2, 3)) &&
                spreadChapters(false) == listOf(listOf(0, 1), listOf(2, 3))
        }
        check("lay-fxl-spread-default") { doc.renditionOf(0).spread == EpubSpread.AUTO && spreadChapters() == listOf(listOf(0, 1), listOf(2, 3)) }
        check("lay-fxl-spread-landscape") {
            doc.renditionOf(0).spread == EpubSpread.LANDSCAPE && spreadChapters(true) == listOf(listOf(0, 1), listOf(2, 3)) &&
                spreadChapters(false) == listOf(listOf(0), listOf(1), listOf(2), listOf(3))
        }
        check("lay-fxl-spread-none") {
            doc.renditionOf(0).spread == EpubSpread.NONE && spreadChapters(true) == listOf(listOf(0), listOf(1), listOf(2), listOf(3))
        }
        check("lay-fxl-svg-icb_multi") { sizeIs(1, 675.0, 450.0) && sizeIs(2, 900.0, 450.0) }
        check("lay-fxl-xhtml-icb") { sizeIs(0, 675.0, 450.0) && gridPaints(0) }
        check("lay-fxl-xhtml-icb_device_sizes") { gridPaints(0) }
        check("lay-fxl-xhtml-icb_invalid_meta") { sizeIs(1, 675.0, 450.0) && gridPaints(0) && gridPaints(1) }
        check("lay-fxl-xhtml-icb_multi") { sizeIs(0, 675.0, 450.0) && sizeIs(1, 1050.0, 450.0) && gridPaints(0) && gridPaints(1) }
        check("lay-fxl-xhtml-icb_multi_declarations") { sizeIs(1, 750.0, 450.0) }
        check("lay-fxl-xhtml-icb_repeated-in-meta") { sizeIs(1, 675.0, 450.0) }
        check("lay-fxl-xhtml-icb_units") { sizeIs(1, 750.0, 450.0) }
    }

    private fun prePaginatedLayout() {
        check("lay-page-layout-both") {
            doc.renditionOf(1).layout == EpubLayout.REFLOWABLE && pages(1) >= 2 &&
                listOf(0, 2, 3).all { doc.renditionOf(it).layout == EpubLayout.PRE_PAGINATED && pages(it) == 1 && sizeIs(it, 675.0, 450.0) }
        }
        check("lay-pkg-flow-paginated") { (1 until chapters).all { pages(it) > 1 } }
        check("lay-pkg-flow-scrolled-continuous") { pages(1) == 1 }
        check("lay-pkg-flow-scrolled-doc") { pages(1) == 1 }
        check("lay-pp-embedded-images") { allFixedPages(1255.5, 1033.5) && (0 until chapters).all { images(it).size == 1 } && doc.renditionOf(0).pageSpread == EpubPageSpread.CENTER }
        check("lay-pp-embedded-images-svg") { allFixedPages(1255.5, 1033.5) && (0 until chapters).all { images(it).size == 1 } }
        check("lay-pp-images-in-spine") { doc.chapterPath(0) == "EPUB/fallbacks/Title.xhtml" && "A APPLE PIE" in text(0) && doc.chapterPath(1) == "EPUB/fallbacks/A.xhtml" }
        check("lay-pp-images-mixed") {
            doc.chapterPath(3) == "EPUB/fallbacks/C.xhtml" && "C CUT IT" in text(3) && "D DEALT IT" in text(4) &&
                listOf(0, 1, 2, 5, 6, 7, 8).all { sizeIs(it, 1255.5, 1033.5) && images(it).size == 1 }
        }
        check("lay-pp-layout-default") { doc.epubMetadata.rendition.layout == EpubLayout.REFLOWABLE && pages(0) > 1 && "renders as a reflowable document" in text(0) }
        check("lay-pp-layout-pre-paginated") { allFixedPages(675.0, 450.0) && (0 until 4).all { "Page ${it + 1}" in text(it) } }
        check("lay-pp-layout-pre-paginated-spreads") { spreadChapters(true) == listOf(listOf(0, 1), listOf(2, 3)) && spreadChapters(false) == listOf(listOf(0, 1), listOf(2, 3)) }
        check("lay-pp-page-spread-combined") { spreadChapters() == listOf(listOf(0), listOf(1, 2), listOf(3)) }
        check("lay-pp-page-spread-left") { spreadChapters() == listOf(listOf(0), listOf(1, 2), listOf(3)) }
        check("lay-pp-page-spread-right") { spreadChapters() == listOf(listOf(0), listOf(1, 2), listOf(3)) && spreadSides().first() == SpreadSide.RIGHT }
        check("lay-pp-spine-overrides_behave-as-global") {
            doc.renditionOf(0).layout == EpubLayout.PRE_PAGINATED && pages(0) == 1 && sizeIs(0, 750.0, 450.0) &&
                doc.renditionOf(1).layout == EpubLayout.REFLOWABLE && pages(1) > 1
        }
        check("lay-pp-spine-overrides_behave-as-global-bis") {
            doc.renditionOf(0).layout == EpubLayout.REFLOWABLE && doc.renditionOf(2).layout == EpubLayout.REFLOWABLE &&
                doc.renditionOf(1).layout == EpubLayout.PRE_PAGINATED && pages(1) == 1 && sizeIs(1, 750.0, 450.0)
        }
        check("lay-pp-spine-overrides_image-only-pp") {
            doc.renditionOf(0).layout == EpubLayout.PRE_PAGINATED && sizeIs(0, 750.0, 450.0) && images(0).size == 1 &&
                doc.renditionOf(1).layout == EpubLayout.REFLOWABLE && pages(1) > 1
        }
        check("lay-pp-spine-overrides_image-only-reflow") {
            doc.renditionOf(1).layout == EpubLayout.PRE_PAGINATED && sizeIs(1, 750.0, 450.0) && images(1).size == 1 &&
                doc.renditionOf(0).layout == EpubLayout.REFLOWABLE && doc.renditionOf(2).layout == EpubLayout.REFLOWABLE
        }
        check("lay-pp-spine-overrides_image-spine-pp") {
            doc.chapterPath(0) == "EPUB/page_001.xhtml" && doc.renditionOf(0).layout == EpubLayout.PRE_PAGINATED &&
                sizeIs(0, 750.0, 450.0) && images(0).size == 1 && doc.renditionOf(1).layout == EpubLayout.REFLOWABLE
        }
        check("lay-pp-spine-overrides_image-spine-reflow") {
            doc.chapterPath(1) == "EPUB/page_002.xhtml" && doc.renditionOf(1).layout == EpubLayout.PRE_PAGINATED &&
                sizeIs(1, 750.0, 450.0) && images(1).size == 1 && doc.renditionOf(0).layout == EpubLayout.REFLOWABLE
        }
        check("lay-pp-spread-none") {
            doc.renditionOf(0).spread == EpubSpread.NONE && spreadChapters(true) == listOf(listOf(0), listOf(1), listOf(2), listOf(3))
        }
        check("lay-pp-svg-icb_multi") { sizeIs(1, 675.0, 450.0) && sizeIs(2, 900.0, 450.0) }
        check("lay-pp-xhtml-icb") { sizeIs(0, 675.0, 450.0) && gridPaints(0) }
        check("lay-pp-xhtml-icb_invalid_meta") { sizeIs(1, 675.0, 450.0) && gridPaints(0) && gridPaints(1) }
        check("lay-pp-xhtml-icb_multi") { sizeIs(0, 675.0, 450.0) && sizeIs(1, 1050.0, 450.0) && gridPaints(0) && gridPaints(1) }
        check("lay-pp-xhtml-icb_multi_declarations") { sizeIs(1, 750.0, 450.0) }
        check("lay-pp-xhtml-icb_repeated-in-meta") { sizeIs(1, 675.0, 450.0) }
        check("lay-pp-xhtml-icb_units") { sizeIs(1, 750.0, 450.0) }
        check("lay-rendition-flow-pre-pag") {
            listOf(1, 2).all { doc.renditionOf(it).layout == EpubLayout.PRE_PAGINATED && pages(it) == 1 && sizeIs(it, 675.0, 450.0) }
        }
        check("lay-viewport-meta-prop") { allFixedPages(675.0, 450.0) }
    }

    private fun rollLayout() {
        // KitePDF has no roll layout: a roll book reads as reflowable, one letterboxed plate a page.
        for (id in listOf("lay-roll-embedded-images", "lay-roll-embedded-images-svg", "lay-roll-images-in-spine", "lay-roll-images-mixed")) {
            check(id) { (0 until chapters).all { c -> pages(c) == 1 && abs(page(c).height / page(c).width - 1378.0 / 1674.0) < 0.02 } }
        }
    }

    private fun navigationDocuments() {
        check("nav-access") { doc.tableOfContents.entries.map { it.label } == listOf("Test passes if you can see this link") && doc.outline.size == 1 }
        check("nav-activation") {
            val entry = doc.tableOfContents.entries.firstOrNull { it.href == "EPUB/content_002.xhtml" }
            entry != null && entry.spineIndex == 2 && "Test passes if the navigation link leads here." in text(2)
        }
        for (id in listOf("nav-non-text_img", "nav-non-text_img_title")) {
            check(id) { doc.tableOfContents.entries.any { it.href == "EPUB/senanque.xhtml" && it.label == "Description of the Abbey of S\u00E9nanque" } }
        }
        check("nav-spine_in-spine") { doc.tableOfContents.entries.size == 2 && page(0).links.map { it.href }.containsAll(listOf("EPUB/content_001.xhtml", "EPUB/content_002.xhtml")) }
        for (id in listOf("nav-spine_in-spine-hidden-toc-css", "nav-spine_in-spine-hidden-toc-html")) {
            check(id) {
                doc.tableOfContents.entries.map { it.label } == listOf("The first link", "The second link") &&
                    "The first link" in text(0) && "The second link" !in text(0)
            }
        }
        check("nav-spine_in-spine-no-list-style") { doc.outline.map { it.title }.none { it.isEmpty() || !it.first().isLetter() } && doc.outline.size == 2 }
        check("nav-spine_not-in-spine") {
            doc.tableOfContents.entries.map { it.spineIndex } == listOf(0, 1) && (0 until chapters).none { doc.chapterPath(it) == "EPUB/nav.xhtml" }
        }
    }

    private fun structuralSemantics() {
        check("pss-support_ignore-title") {
            val title = doc.linkTarget("EPUB/content_002.xhtml#the_note")
            (title == null || title.kind == EpubTargetKind.OTHER) && doc.linkTarget("EPUB/content_001.xhtml#the_note")?.kind == EpubTargetKind.FOOTNOTE
        }
    }

    private fun scripting() {
        check("scr-support") { runScripts(); "This reading system supports scripting. Test passes." in text(0) }
        check("scr-readingsystem-support") { runScripts(); "implements the epubReadingSystem object. Test passes." in text(0) }
        check("scr-readingsystem-features") {
            runScripts()
            val text = text(0)
            listOf("dom-manipulation: true", "layout-changes: true", "spine-scripting: true").all { it in text } && "unimplemented" !in text
        }
        check("scr-support_origin") {
            runScripts()
            val origin = Regex("Origin URL: (epub://[0-9a-f]{16})")
            val a = origin.find(text(0))?.groupValues?.get(1)
            a != null && a == origin.find(text(1))?.groupValues?.get(1)
        }
        check("scr-support_origin_unique") {
            runScripts()
            val mine = Regex("Origin URL: (epub://[0-9a-f]{16})").find(text(0))?.groupValues?.get(1)
            val other = W3cTestBook(java.io.File(folder.parentFile, "scr-support_origin")).also { it.runScripts() }
            val theirs = Regex("Origin URL: (epub://[0-9a-f]{16})").find(other.text(0))?.groupValues?.get(1)
            other.close()
            mine != null && theirs != null && mine != theirs
        }
        check("scr-storage-delete") {
            W3cTestBook(folder).also { it.runScripts() }.close()
            runScripts()
            "If you have opened this EPUB for a second time, persistent data is not supported. Hence, this test passes." in chapterText(0)
        }
        check("scr-support_svg") {
            runScripts()
            val drawn = drawnText(0).replace(" ", "")
            runScripts().failures.isEmpty() && "Testpasses" in drawn && "doesnotsupportscripting" !in drawn
        }
        check("scr-readingsystem-support_svg") {
            runScripts()
            val drawn = drawnText(0).replace(" ", "")
            runScripts().failures.isEmpty() && "epubReadingSystem" in drawn && "Testpasses" in drawn
        }
        for ((id, expected) in listOf(
            "scr-support_iframe" to "supports scripting in an iframe",
            "scr-readingsystem-support_iframe" to "Test passes",
            "scr-readingsystem-support_iframe_svg" to "Test passes",
            "scr-not-support_ccscript-modify-host" to "",
            "scr-not-support_ccscript-modify-size" to "",
        )) {
            // The box of an iframe paints its document: the text drawn inside the frame's rectangle.
            check(id) {
                runScripts()
                val frame = page(0).embeds.firstOrNull { it.kind == EpubEmbedKind.FRAME } ?: return@check false
                val inside = glyphRuns(0).filter { run -> run.text.isNotBlank() && insideFrame(run, frame.rect) }
                inside.isNotEmpty() && expected.replace(" ", "") in inside.joinToString("") { it.text }.replace(" ", "")
            }
        }
        check("scr-support_scrolled-continuous") { pages(3) == 1 }
        check("scr-support_scrolled-doc") { pages(3) == 1 }
    }

    /** Whether a glyph run starts inside [rect], a box in display space whose y runs down. */
    private fun W3cTestBook.insideFrame(run: RecordingCanvas.Call.Glyphs, rect: io.github.yuroyami.kitepdf.core.KiteRectangle): Boolean {
        val x = run.textToDevice.e
        val y = page(0).height - run.textToDevice.f
        return x >= rect.left && x <= rect.right && y >= minOf(rect.top, rect.bottom) && y <= maxOf(rect.top, rect.bottom)
    }

    private fun mediaOverlays() {
        // A media overlay file that several chapters share: each chapter keeps the clips of its own text.
        check("mol-support_xhtml-load") { doc.mediaOverlayOf(2)?.clips?.size == 2 }
        check("mol-support_xhtml-load-fxl") { doc.mediaOverlayOf(2)?.clips?.size == 2 }
        check("mol-timing-synchronization_fxl") { (1..3).all { doc.mediaOverlayOf(it)?.clips?.size == 1 } }
        // A clip's text inside a spine-level SVG document has a place on the page.
        check("mol-timing-synchronization_svg") { doc.locateFragment("EPUB/mobydick.svg#first") != null }
        check("mol-timing-synchronization_svg-fxl") { doc.locateFragment("EPUB/mobydick.svg#first") != null }
        check("mol-audio") {
            val clip = doc.mediaOverlayOf(1)?.clips?.singleOrNull()
            clip != null && clip.audioHref == "EPUB/audio/mobydick_1.mp3" && near(clip.clipBegin, 29.268, 0.001) && near(clip.clipEnd ?: 0.0, 44.783, 0.001)
        }
        check("mol-audio-no-clipbegin") { doc.mediaOverlayOf(1)?.clips?.firstOrNull()?.let { it.clipBegin == 0.0 && near(it.clipEnd ?: 0.0, 44.783, 0.001) } == true }
        check("mol-audio-no-clipend") { doc.mediaOverlayOf(1)?.clips?.let { it.size == 2 && it[1].clipEnd == null } == true }
    }
}
