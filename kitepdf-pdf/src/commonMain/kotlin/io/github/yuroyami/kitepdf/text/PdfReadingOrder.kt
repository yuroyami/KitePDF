package io.github.yuroyami.kitepdf.text

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.core.KiteReadingItem
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
            val contents = contentTexts(page, document)
            // A page whose content carries no ids is not tagged, whatever the document says.
            if (contents.isNotEmpty()) return Builder(pageNumber, contents, document.language).build(roots)
        }
        return page.textContent().blocks.mapNotNull { block ->
            block.lines.joinToString(" ") { it.text.trim() }.trim().takeIf { it.isNotEmpty() }
                ?.let { KiteReadingItem(KiteRole.TEXT, it, language = document.language) }
        }
    }

    /** The text of one marked-content sequence, with its first and last runs for the joins. */
    private class Marked(val text: StringBuilder, var first: PdfTextSpan, var last: PdfTextSpan)

    /** The text that each marked-content id holds on [page], in content order. */
    private fun contentTexts(page: PdfPage, document: PdfDocument): Map<Int, Marked> {
        val collector = TextCollectorCanvas()
        val renderer = PageRenderer(collector, document)
        collector.mcid = { renderer.currentMcid }
        runCatching { renderer.render(page, KiteMatrix.IDENTITY) }
        val out = LinkedHashMap<Int, Marked>()
        for (run in collector.runs) {
            val id = run.mcid ?: continue
            val span = run.toSpan() ?: continue
            val marked = out[id]
            if (marked == null) {
                out[id] = Marked(StringBuilder(span.text), span, span)
            } else {
                join(marked.text, span.text, marked.last, span)
                marked.last = span
            }
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
    private class Builder(val page: Long, val contents: Map<Int, Marked>, val documentLanguage: String?) {
        val out = ArrayList<KiteReadingItem>()

        // The text directly inside block containers, which becomes an item at the next block boundary.
        val pending = StringBuilder()
        var pendingLast: PdfTextSpan? = null
        var pendingRole = KiteRole.TEXT
        var pendingLang: String? = null

        fun build(roots: List<StructElement>): List<KiteReadingItem> {
            for (root in roots) walk(root, KiteRole.TEXT, documentLanguage)
            flush()
            return out
        }

        fun flush() {
            val text = pending.toString().trim()
            if (text.isNotEmpty()) out += KiteReadingItem(pendingRole, text, language = pendingLang)
            pending.clear()
            pendingLast = null
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
                    if (onPage(e)) pendText(e.actualText, null, role, language)
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
                out += KiteReadingItem(role, alt?.trim().orEmpty(), sourceType = e.ownType, language = lang)
                return
            }
            val text = StringBuilder()
            collect(e, text, arrayOfNulls(1))
            val words = text.toString().trim()
            if (words.isNotEmpty()) out += KiteReadingItem(role, words, level, sourceType = e.ownType, language = lang)
        }

        /** The text of [e] on this page: its replacement text, or its content in logical order. */
        fun collect(e: StructElement, sb: StringBuilder, last: Array<PdfTextSpan?>) {
            if (e.type == "Artifact") return
            if (e.actualText != null) {
                if (onPage(e)) { join(sb, e.actualText, last[0], null); last[0] = null }
                return
            }
            for (kid in e.kids) when (kid) {
                is StructKid.Content -> if (kid.page == page) contents[kid.mcid]?.let { m ->
                    join(sb, m.text.toString(), last[0], m.first)
                    last[0] = m.last
                }
                is StructKid.Element -> collect(kid.element, sb, last)
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
