package io.github.yuroyami.kitepdf.text

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.core.KiteReadingItem
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.KiteRole
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.render.PageRenderer
import kotlin.math.abs
import kotlin.math.max

/**
 * The reading order of a PDF page (#208). A tagged page follows its structure tree: one item per
 * paragraph, heading, list item, table cell, caption or figure, in logical order, with the text of
 * the marked content each one names, and artifacts left out (ISO 32000-1, 14.8). A page without
 * tagged content reads its text blocks in layout order.
 */
internal object PdfReadingOrder {

    fun of(page: PdfPage, document: PdfDocument): List<KiteReadingItem> {
        val roots = document.structureTree
        val pageNumber = page.reference?.objectNumber
        if (roots != null && pageNumber != null) {
            val contents = markedContent(page, document)
            // A page whose content carries no ids is not tagged, whatever the document says.
            if (contents.isNotEmpty()) return Builder(pageNumber, contents, document.language, page.pageToDeviceBase()).build(roots)
        }
        return page.textContent().blocks.mapNotNull { block ->
            val text = block.lines.joinToString(" ") { it.text.trim() }.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val bounds = block.lines.map { it.bounds }.reduceOrNull { a, b -> a.union(b) }
            KiteReadingItem(KiteRole.TEXT, text, language = document.language, bounds = bounds)
        }
    }

    /**
     * One marked-content sequence: its text, with its first and last runs for the joins, and in
     * page space the box of its text and the box of all it paints. A sequence that paints no
     * text has no runs and no text box.
     */
    private class Marked(val text: StringBuilder, val first: PdfTextSpan?, var last: PdfTextSpan?) {
        var textBox: KiteRectangle? = null
        var inkBox: KiteRectangle? = null
    }

    /** The boxes that an item gathers from its sequences, in page space. */
    private class Boxes {
        var text: KiteRectangle? = null
        var ink: KiteRectangle? = null

        fun add(m: Marked) {
            m.textBox?.let { text = text?.union(it) ?: it }
            m.inkBox?.let { ink = ink?.union(it) ?: it }
        }
    }

    /** What each marked-content id holds on [page]: its text in content order, and its boxes. */
    private fun markedContent(page: PdfPage, document: PdfDocument): Map<Int, Marked> {
        val collector = TextCollectorCanvas()
        val ink = HashMap<Int, KiteRectangle>()
        collector.inkBoxes = ink
        val renderer = PageRenderer(collector, document)
        collector.mcid = { renderer.currentMcid }
        runCatching { renderer.render(page, KiteMatrix.IDENTITY) }
        val out = LinkedHashMap<Int, Marked>()
        for (run in collector.runs) {
            val id = run.mcid ?: continue
            val span = run.toSpan() ?: continue
            val marked = out.getOrPut(id) { Marked(StringBuilder(), span, span) }
            join(marked.text, span.text, marked.last, span)
            marked.last = span
            marked.textBox = marked.textBox?.union(span.bounds) ?: span.bounds
            marked.inkBox = marked.inkBox?.union(span.bounds) ?: span.bounds
        }
        // A sequence that paints only paths or images still marks a place, such as a figure's.
        for ((id, box) in ink) {
            val marked = out.getOrPut(id) { Marked(StringBuilder(), null, null) }
            marked.inkBox = marked.inkBox?.union(box) ?: box
        }
        return out
    }

    /**
     * Appends [text] to [sb], with a space between when the runs [before] and [after] sit apart
     * on one line or on two lines, and neither side has one already.
     */
    private fun join(sb: StringBuilder, text: String, before: PdfTextSpan?, after: PdfTextSpan?) {
        if (text.isEmpty()) return
        if (sb.isNotEmpty() && !sb.last().isWhitespace() && !text.first().isWhitespace() && apart(before, after)) sb.append(' ')
        sb.append(text)
    }

    private fun apart(before: PdfTextSpan?, after: PdfTextSpan?): Boolean {
        if (before == null || after == null) return true
        val size = max(before.fontSize, after.fontSize)
        val newLine = abs(before.origin.second - after.origin.second) > size * 0.5
        val gap = after.bounds.left - before.bounds.right > size * WORD_GAP_EM
        return newLine || gap
    }

    /** Builds the items of one page from the structure, in logical order. */
    private class Builder(
        val page: Long,
        val contents: Map<Int, Marked>,
        val documentLanguage: String?,
        /** Page space to the display space of the page, where item bounds are. */
        val toDisplay: KiteMatrix,
    ) {
        val out = ArrayList<KiteReadingItem>()

        // The text directly inside block containers, which becomes an item at the next block boundary.
        val pending = StringBuilder()
        var pendingLast: PdfTextSpan? = null
        var pendingRole = KiteRole.TEXT
        var pendingLang: String? = null
        var pendingBoxes = Boxes()

        /** [r], a page-space box, in display space, with the smaller y in bottom. */
        fun display(r: KiteRectangle?): KiteRectangle? = r?.let {
            val xs = doubleArrayOf(toDisplay.transformX(it.left, it.bottom), toDisplay.transformX(it.right, it.top), toDisplay.transformX(it.left, it.top), toDisplay.transformX(it.right, it.bottom))
            val ys = doubleArrayOf(toDisplay.transformY(it.left, it.bottom), toDisplay.transformY(it.right, it.top), toDisplay.transformY(it.left, it.top), toDisplay.transformY(it.right, it.bottom))
            KiteRectangle(xs.min(), ys.min(), xs.max(), ys.max())
        }

        fun build(roots: List<StructElement>): List<KiteReadingItem> {
            for (root in roots) walk(root, KiteRole.TEXT, documentLanguage)
            flush()
            return out
        }

        fun flush() {
            val text = pending.toString().trim()
            if (text.isNotEmpty()) {
                out += KiteReadingItem(pendingRole, text, language = pendingLang, bounds = display(pendingBoxes.text ?: pendingBoxes.ink))
            }
            pending.clear()
            pendingLast = null
            pendingBoxes = Boxes()
        }

        /** [e] reached between blocks: an item of its own, a block that holds more, or inline text. */
        fun walk(e: StructElement, role: KiteRole, lang: String?) {
            if (e.type == "Artifact") return
            val language = e.lang ?: lang
            when (e.type) {
                in ITEMS, "Code", "Note", "FENote" -> {
                    flush()
                    item(e, role, language)
                }
                in BLOCKS -> {
                    flush()
                    val inner = if (e.type == "BlockQuote") KiteRole.QUOTE else role
                    for (kid in e.kids) when (kid) {
                        is StructKid.Content -> pend(kid, inner, language)
                        is StructKid.Element -> walk(kid.element, inner, language)
                    }
                    flush()
                }
                // An inline element between blocks adds its text to the text around it.
                else -> if (e.actualText != null) {
                    if (onPage(e)) {
                        pendText(e.actualText, null, role, language)
                        collectBox(e, pendingBoxes)
                    }
                } else {
                    for (kid in e.kids) when (kid) {
                        is StructKid.Content -> pend(kid, role, language)
                        is StructKid.Element -> walk(kid.element, role, language)
                    }
                }
            }
        }

        fun pend(kid: StructKid.Content, role: KiteRole, lang: String?) {
            if (kid.page != page) return
            val marked = contents[kid.mcid] ?: return
            pendingBoxes.add(marked)
            if (marked.text.isEmpty()) return
            if (pending.isEmpty()) { pendingRole = role; pendingLang = lang }
            join(pending, marked.text.toString(), pendingLast, marked.first)
            pendingLast = marked.last
        }

        fun pendText(text: String, span: PdfTextSpan?, role: KiteRole, lang: String?) {
            if (pending.isEmpty()) { pendingRole = role; pendingLang = lang }
            join(pending, text, pendingLast, span)
            pendingLast = span
        }

        /** One item for [e] and all it holds on this page, when any of it is on this page. */
        fun item(e: StructElement, context: KiteRole, lang: String?) {
            if (!onPage(e)) return
            val (role, level) = when (e.type) {
                "H", "Title" -> KiteRole.HEADING to 1
                "H1", "H2", "H3", "H4", "H5", "H6" -> KiteRole.HEADING to (e.type[1] - '0')
                "LI", "TOCI" -> KiteRole.LIST_ITEM to 0
                "TD", "TH" -> KiteRole.TABLE_CELL to 0
                "Caption" -> KiteRole.CAPTION to 0
                "Figure", "Formula" -> KiteRole.IMAGE to 0
                "Code" -> KiteRole.CODE to 0
                else -> context to 0
            }
            if (role == KiteRole.IMAGE) {
                // An empty alternative text marks a decorative figure.
                val alt = e.alt ?: e.actualText
                if (alt != null && alt.isBlank()) return
                // The box the structure gives, or else the box of what the figure paints.
                val boxes = Boxes().also { collectBox(e, it) }
                out += KiteReadingItem(role, alt?.trim().orEmpty(), sourceType = e.ownType, language = lang, bounds = display(e.bbox ?: boxes.ink))
                return
            }
            val text = StringBuilder()
            val boxes = Boxes()
            collect(e, text, arrayOfNulls(1), boxes)
            val words = text.toString().trim()
            if (words.isNotEmpty()) {
                out += KiteReadingItem(role, words, level, sourceType = e.ownType, language = lang, bounds = display(boxes.text ?: boxes.ink ?: e.bbox))
            }
        }

        /** The text of [e] on this page: its replacement text, or its content in logical order. */
        fun collect(e: StructElement, sb: StringBuilder, last: Array<PdfTextSpan?>, boxes: Boxes) {
            if (e.type == "Artifact") return
            if (e.actualText != null) {
                if (onPage(e)) {
                    join(sb, e.actualText, last[0], null)
                    last[0] = null
                    // The replaced content still marks where the text is.
                    collectBox(e, boxes)
                }
                return
            }
            for (kid in e.kids) when (kid) {
                is StructKid.Content -> if (kid.page == page) contents[kid.mcid]?.let { m ->
                    boxes.add(m)
                    if (m.text.isNotEmpty()) {
                        join(sb, m.text.toString(), last[0], m.first)
                        last[0] = m.last
                    }
                }
                is StructKid.Element -> collect(kid.element, sb, last, boxes)
            }
        }

        /** Adds the boxes of [e]'s content on this page to [boxes]. */
        fun collectBox(e: StructElement, boxes: Boxes) {
            if (e.type == "Artifact") return
            for (kid in e.kids) when (kid) {
                is StructKid.Content -> if (kid.page == page) contents[kid.mcid]?.let(boxes::add)
                is StructKid.Element -> collectBox(kid.element, boxes)
            }
        }

        /** Whether some content of [e] is on this page. */
        fun onPage(e: StructElement): Boolean = e.kids.any { kid ->
            when (kid) {
                is StructKid.Content -> kid.page == page
                is StructKid.Element -> onPage(kid.element)
            }
        }
    }

    /** The types that make one item with everything they hold. */
    private val ITEMS = setOf(
        "P", "H", "H1", "H2", "H3", "H4", "H5", "H6", "Title", "LI", "TOCI", "TD", "TH",
        "Caption", "Figure", "Formula",
    )

    /** The types that hold blocks, whose own text is an item between them. */
    private val BLOCKS = setOf(
        "Document", "DocumentFragment", "Part", "Art", "Sect", "Div", "Aside", "BlockQuote", "TOC",
        "Index", "NonStruct", "Private", "L", "Table", "THead", "TBody", "TFoot", "TR",
    )

    /** A gap between two runs, in em, that reads as a space. */
    private const val WORD_GAP_EM = 0.15
}
