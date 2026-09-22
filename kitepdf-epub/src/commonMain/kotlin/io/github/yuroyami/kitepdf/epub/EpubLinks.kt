package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode

/**
 * What a link is for, read from the link's own markup: its `epub:type` from the
 * EPUB 3 Structural Semantics Vocabulary, its DPUB-ARIA `role`, or the `rel` that
 * EPUB 2 converters write. A reader can open a note in place instead of turning
 * the page. [EpubLink.kind] carries it.
 */
public enum class EpubLinkKind {
    /** An ordinary link, or one whose markup says nothing more. */
    LINK,

    /** A note reference: `epub:type="noteref"`, `role="doc-noteref"`, or `rel="footnote"`. */
    NOTE_REFERENCE,

    /** A glossary reference: `epub:type="glossref"` or `role="doc-glossref"`. */
    GLOSSARY_REFERENCE,

    /** A bibliography reference: `epub:type="biblioref"` or `role="doc-biblioref"`. */
    BIBLIOGRAPHY_REFERENCE,
}

/**
 * What the element a link points at is, read from its own `epub:type` or `role`,
 * else from the section that holds it.
 */
public enum class EpubTargetKind {
    /** `footnote` or `doc-footnote`, or a note inside a `footnotes` section. */
    FOOTNOTE,

    /** `endnote`, `rearnote` or `doc-endnote`, or a note inside an `endnotes`, `rearnotes` or `doc-endnotes` section. */
    ENDNOTE,

    /** `note`: a note of no stated kind. */
    NOTE,

    /** A glossary term or definition, or anything inside a `glossary` or `doc-glossary`. */
    GLOSSARY_ENTRY,

    /** A bibliography entry, or anything inside a `bibliography` or `doc-bibliography`. */
    BIBLIOGRAPHY_ENTRY,

    /** Any other element. */
    OTHER,
}

/**
 * The element an internal link points at, read from the book's markup without
 * laying its chapter out. [EpubDocument.linkTarget] builds it.
 */
public class EpubLinkTarget internal constructor(
    /** The link as the page gives it: `zipPath#fragment`. */
    public val href: String,
    /** Where the target sits. It stays valid when the book reflows; resolve it with [EpubDocument.locate]. */
    public val bookmark: KiteBookmark.Flow,
    /** What the target is. */
    public val kind: EpubTargetKind,
    /** The `epub:type` or `role` value that [kind] was read from, as written. Null when the markup names none. */
    public val type: String?,
    /**
     * The target's text: one line per block, spaces collapsed, ruby readings and back
     * links left out. For a glossary term, the definitions follow the term.
     */
    public val text: String,
)

/**
 * The `epub:type` tokens of [el], lower-cased, without a vocabulary prefix. The XML
 * reader drops the `epub:` prefix, so the attribute arrives as `type`, which a few
 * HTML elements also use, for a MIME type or a list marker. Those values are skipped.
 */
internal fun epubTypes(el: KiteXmlNode.Element): List<String> {
    val raw = el.attrs["type"]?.trim() ?: return emptyList()
    if ('/' in raw || raw in LIST_MARKER_TYPES) return emptyList()
    return tokens(raw).map { it.substringAfterLast(':') }
}

/** Every semantic word on [el]: its `epub:type` tokens and its `role` tokens. */
private fun semanticWords(el: KiteXmlNode.Element): List<String> =
    epubTypes(el) + tokens(el.attrs["role"])

private fun tokens(raw: String?): List<String> =
    raw?.trim()?.lowercase()?.split(WHITESPACE)?.filter { it.isNotEmpty() }.orEmpty()

/** What the `<a>` element [a] is for. */
internal fun linkKindOf(a: KiteXmlNode.Element): EpubLinkKind {
    val words = semanticWords(a)
    val rel = tokens(a.attrs["rel"])
    return when {
        words.any { it == "noteref" || it == "doc-noteref" } ||
            rel.any { it == "footnote" || it == "endnote" || it == "note" } -> EpubLinkKind.NOTE_REFERENCE
        words.any { it == "glossref" || it == "doc-glossref" } -> EpubLinkKind.GLOSSARY_REFERENCE
        words.any { it == "biblioref" || it == "doc-biblioref" } -> EpubLinkKind.BIBLIOGRAPHY_REFERENCE
        else -> EpubLinkKind.LINK
    }
}

/**
 * Every link in [root] with a kind other than [EpubLinkKind.LINK], keyed by the href
 * as the layout stores it. [resolve] turns an `href` attribute into that form.
 */
internal fun linkKindsIn(root: KiteXmlNode.Element, resolve: (String) -> String): Map<String, EpubLinkKind> {
    val out = HashMap<String, EpubLinkKind>()
    fun walk(el: KiteXmlNode.Element) {
        if (el.tag == "a") {
            val href = el.attrs["href"]?.takeIf { it.isNotBlank() }
            val kind = linkKindOf(el)
            if (href != null && kind != EpubLinkKind.LINK) out[resolve(href)] = kind
        }
        for (c in el.children) if (c is KiteXmlNode.Element) walk(c)
    }
    walk(root)
    return out
}

/** The target of [fragment] in one chapter's tree, or null when the chapter has no such id. */
internal fun linkTargetIn(root: KiteXmlNode.Element, chapter: Int, href: String, fragment: String): EpubLinkTarget? {
    val anchor = elementById(root, fragment) ?: return null
    val holder = noteHolder(anchor)
    var kind = EpubTargetKind.OTHER
    var type: String? = null
    // The nearest element that names a kind wins: the anchor, what holds it, then its sections.
    var el: KiteXmlNode.Element? = anchor
    while (el != null && el.tag != "body" && el.tag != "#root") {
        val found = semanticWords(el).firstNotNullOfOrNull { w -> TARGET_KINDS[w]?.let { w to it } }
        if (found != null) {
            type = found.first; kind = found.second
            break
        }
        el = el.parent
    }
    return EpubLinkTarget(href, KiteBookmark.Flow(chapter, 0, fragment), kind, type, noteText(holder))
}

/** The element whose `id` is [id], or a legacy `<a name>` of that name. */
private fun elementById(root: KiteXmlNode.Element, id: String): KiteXmlNode.Element? {
    if (root.attrs["id"] == id || (root.tag == "a" && root.attrs["name"] == id)) return root
    for (c in root.children) if (c is KiteXmlNode.Element) elementById(c, id)?.let { return it }
    return null
}

/**
 * The element that holds the note an id marks. EPUB 2 converters put the id on a
 * short inline anchor, `<p><a id="fn1" href="#r1">1.</a> The note.</p>`, so an inline
 * anchor with a few characters at most stands for the block around it.
 */
private fun noteHolder(anchor: KiteXmlNode.Element): KiteXmlNode.Element {
    if (anchor.tag !in INLINE_TAGS || noteText(anchor).length > 4) return anchor
    var el = anchor.parent
    while (el != null && el.tag != "body" && el.tag != "#root") {
        if (el.tag !in INLINE_TAGS) return el
        el = el.parent
    }
    return anchor
}

/**
 * The text of [holder], one line per block. A glossary term (`<dt>`) takes the
 * definitions (`<dd>`) that follow it.
 */
private fun noteText(holder: KiteXmlNode.Element): String {
    val lines = ArrayList<String>()
    val line = StringBuilder()
    fun endLine() {
        val t = line.toString().replace(WHITESPACE, " ").trim()
        if (t.isNotEmpty()) lines.add(t)
        line.clear()
    }
    fun walk(n: KiteXmlNode) {
        when (n) {
            is KiteXmlNode.Text -> line.append(n.text)
            is KiteXmlNode.Element -> {
                if (n.tag in SKIPPED_TAGS || isBackLink(n)) return
                if (n.tag == "br") { endLine(); return }
                val block = n.tag !in INLINE_TAGS
                if (block) endLine()
                for (c in n.children) walk(c)
                if (block) endLine()
            }
        }
    }
    walk(holder)
    if (holder.tag == "dt") {
        val siblings = holder.parent?.children.orEmpty().filterIsInstance<KiteXmlNode.Element>()
        for (dd in siblings.drop(siblings.indexOf(holder) + 1).takeWhile { it.tag != "dt" }) {
            if (dd.tag == "dd") walk(dd)
        }
    }
    endLine()
    return lines.joinToString("\n")
}

/**
 * A link back to where the note was called from: `epub:type="backlink"`,
 * `role="doc-backlink"`, or a link whose text is only a return arrow.
 */
private fun isBackLink(el: KiteXmlNode.Element): Boolean {
    if (el.tag != "a") return false
    if (semanticWords(el).any { it == "backlink" || it == "doc-backlink" }) return true
    val text = el.textContent().trim()
    return text.isNotEmpty() && text.all { it in RETURN_MARKS }
}

/** Target words and the kind each names, section words included (EPUB 3 SSV, DPUB-ARIA 1.1). */
private val TARGET_KINDS = mapOf(
    "footnote" to EpubTargetKind.FOOTNOTE, "doc-footnote" to EpubTargetKind.FOOTNOTE,
    "footnotes" to EpubTargetKind.FOOTNOTE,
    "endnote" to EpubTargetKind.ENDNOTE, "rearnote" to EpubTargetKind.ENDNOTE, "doc-endnote" to EpubTargetKind.ENDNOTE,
    "endnotes" to EpubTargetKind.ENDNOTE, "rearnotes" to EpubTargetKind.ENDNOTE, "doc-endnotes" to EpubTargetKind.ENDNOTE,
    "note" to EpubTargetKind.NOTE,
    "glossterm" to EpubTargetKind.GLOSSARY_ENTRY, "glossdef" to EpubTargetKind.GLOSSARY_ENTRY,
    "glossary" to EpubTargetKind.GLOSSARY_ENTRY, "doc-glossary" to EpubTargetKind.GLOSSARY_ENTRY,
    "biblioentry" to EpubTargetKind.BIBLIOGRAPHY_ENTRY, "doc-biblioentry" to EpubTargetKind.BIBLIOGRAPHY_ENTRY,
    "bibliography" to EpubTargetKind.BIBLIOGRAPHY_ENTRY, "doc-bibliography" to EpubTargetKind.BIBLIOGRAPHY_ENTRY,
)

/** HTML list marker values of `type` on `<ol>` and `<li>`. */
private val LIST_MARKER_TYPES = setOf("1", "a", "A", "i", "I", "disc", "circle", "square", "none")

/** Elements that flow inside a line. Any other element starts a new line of note text. */
private val INLINE_TAGS = setOf(
    "a", "abbr", "b", "bdi", "bdo", "cite", "code", "data", "dfn", "em", "i", "kbd", "mark", "q",
    "rb", "ruby", "s", "samp", "small", "span", "strong", "sub", "sup", "time", "u", "var", "wbr",
)

/** Elements whose text is not note text: ruby readings and their fallback brackets, scripts and styles. */
private val SKIPPED_TAGS = setOf("rt", "rp", "script", "style", "title")

/** Characters a return link is drawn with, variation selectors and brackets included. */
private const val RETURN_MARKS = "↩↪↑⤴⤶↵⏎^[]()︎️ "

private val WHITESPACE = Regex("\\s+")
