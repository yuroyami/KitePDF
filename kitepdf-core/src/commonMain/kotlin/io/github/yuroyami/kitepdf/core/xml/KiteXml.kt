package io.github.yuroyami.kitepdf.core.xml

/**
 * A tiny, forgiving XML reader: enough for EPUB's container.xml and OPF, for
 * (X)HTML content, and for SVG.
 *
 * Comments, the XML/DOCTYPE prologue, CDATA delimiters and processing
 * instructions are skipped, but for the comments of a caller that asks for
 * them, and malformed markup is salvaged rather than rejected, because real
 * files are messy. Names lose their namespace prefix
 * and are lowercased, so `<epub:switch epub:type="x">` reads as tag `switch`
 * with attribute `type`.
 *
 * ```kotlin
 * val root = KiteXml.parse(svgBytes.decodeToString())
 * val width = root.attrs["width"]
 * ```
 */
public sealed class KiteXmlToken {
    public data class Open(
        public val name: String,
        public val attrs: Map<String, String>,
        public val selfClose: Boolean,
    ) : KiteXmlToken()
    public data class Close(public val name: String) : KiteXmlToken()
    public data class Text(public val text: String) : KiteXmlToken()
    /** A comment's data, the text between `<!--` and `-->`, which only a tokenizer asked to keep them gives. */
    public data class Comment(public val text: String) : KiteXmlToken()
}

public object KiteXml {

    /**
     * The flat token stream: start tags with attributes, end tags, and text.
     * Tag soup is salvaged, never rejected. Use [parse] for a tree; callers
     * that need HTML's implied end tags fold the tokens themselves. A comment is
     * a [KiteXmlToken.Comment] for [keepComments], and is skipped otherwise.
     *
     * For [keepNames], an attribute keeps its name as the markup writes it, with
     * its prefix and its case, and of two with one name the first wins, as HTML's
     * tokenizer has it, for a caller that names attributes as a DOM does. Tag names
     * lose their prefix and case either way.
     */
    public fun tokenize(xml: String, keepComments: Boolean = false, keepNames: Boolean = false): List<KiteXmlToken> {
        val out = ArrayList<KiteXmlToken>()
        var i = 0
        val n = xml.length
        // The script or style element whose text is being read: a < in it opens nothing but its end
        // tag, a comment or a CDATA section, so a lone < of its code stays text.
        var rawText: String? = null
        // Where an end tag of each raw text element was last looked for in vain: none follows from there on.
        val noEndTagFrom = HashMap<String, Int>()
        // The entities that the DOCTYPE's internal subset declares.
        var declared: DeclaredEntities? = null
        while (i < n) {
            val c = xml[i]
            if (c == '<' && (rawText == null || opensInRawText(xml, i, rawText))) {
                when {
                    xml.startsWith("<!--", i) -> {
                        // The end is the first --> after <!, so <!--> and <!---> are empty comments, as HTML has them.
                        val end = xml.indexOf("-->", i + 2)
                        if (keepComments) out.add(KiteXmlToken.Comment(if (end < 0) xml.substring(i + 4) else xml.substring(minOf(i + 4, end), end)))
                        i = if (end < 0) n else end + 3
                    }
                    xml.startsWith("<![CDATA[", i) -> {
                        val end = xml.indexOf("]]>", i)
                        val stop = if (end < 0) n else end
                        out.add(KiteXmlToken.Text(xml.substring(i + 9, stop)))
                        i = if (end < 0) n else end + 3
                    }
                    xml.startsWith("<?", i) -> { i = xml.indexOf("?>", i).let { if (it < 0) n else it + 2 } }
                    xml.startsWith("<!DOCTYPE", i, ignoreCase = true) -> {
                        // One whose subset never closes ends at its first > as before, so the rest still reads.
                        val doctype = readDoctype(xml, i + 9)
                        if (doctype == null) {
                            i = xml.indexOf('>', i).let { if (it < 0) n else it + 1 }
                        } else {
                            if (declared == null) declared = doctype.entities
                            i = doctype.end
                        }
                    }
                    xml.startsWith("<!", i) -> { i = xml.indexOf('>', i).let { if (it < 0) n else it + 1 } }
                    else -> {
                        val end = tagEnd(xml, i + 1)
                        if (end < 0) { i = n } else {
                            val token = parseTag(xml.substring(i + 1, end), keepNames, declared)
                            if (token != null) {
                                out.add(token)
                                // Only an element that closes is read raw, so one left open does not take the rest as text.
                                if (token is KiteXmlToken.Open && !token.selfClose && token.name in RAW_TEXT &&
                                    end < (noEndTagFrom[token.name] ?: n)
                                ) {
                                    if (hasEndTag(xml, end, token.name)) rawText = token.name else noEndTagFrom[token.name] = end
                                }
                                // In raw text only the element's own end tag gets this far.
                                if (token is KiteXmlToken.Close) rawText = null
                            }
                            i = end + 1
                        }
                    }
                }
            } else {
                var end = xml.indexOf('<', i + 1).let { if (it < 0) n else it }
                if (rawText != null) {
                    while (end < n && !opensInRawText(xml, end, rawText)) end = xml.indexOf('<', end + 1).let { if (it < 0) n else it }
                }
                val raw = xml.substring(i, end)
                if (raw.isNotEmpty()) out.add(KiteXmlToken.Text(decodeEntities(raw, declared)))
                i = end
            }
        }
        return out
    }

    /** Whether an end tag of [name] starts after [from]. */
    private fun hasEndTag(xml: String, from: Int, name: String): Boolean {
        var at = xml.indexOf("</", from)
        while (at >= 0) {
            if (opensInRawText(xml, at, name)) return true
            at = xml.indexOf("</", at + 2)
        }
        return false
    }

    /** The elements whose text is code, where a lone `<` is no tag. */
    private val RAW_TEXT = setOf("script", "style")

    /** Whether the `<` at [at], inside the text of a [rawText] element, opens its end tag, a comment or a CDATA section. */
    private fun opensInRawText(xml: String, at: Int, rawText: String): Boolean {
        if (xml.startsWith("<!--", at) || xml.startsWith("<![CDATA[", at)) return true
        if (!xml.startsWith("</", at)) return false
        // The end tag of the element itself, with or without a namespace prefix.
        var i = at + 2
        while (i < xml.length && (xml[i].isLetterOrDigit() || xml[i] == ':' || xml[i] == '-' || xml[i] == '_')) i++
        return xml.substring(at + 2, i).substringAfterLast(':').equals(rawText, ignoreCase = true)
    }

    /**
     * The index of the `>` that ends the tag whose body starts at [from], or -1. A quoted attribute
     * value may hold a `>` (XML 1.0, 2.3) and in HTML a `<` too, so the end is the first `>` outside
     * one. A quote that never closes is a typo, not a value, so the tag then ends at the first `>`.
     */
    private fun tagEnd(xml: String, from: Int): Int {
        var quote = '\u0000'
        var afterEquals = false
        var i = from
        while (i < xml.length) {
            val c = xml[i]
            if (quote != '\u0000') {
                if (c == quote) quote = '\u0000'
            } else when {
                c == '>' -> return i
                (c == '"' || c == '\'') && afterEquals -> { quote = c; afterEquals = false }
                c == '=' -> afterEquals = true
                c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C' -> Unit
                else -> afterEquals = false
            }
            i++
        }
        return xml.indexOf('>', from)
    }

    private fun parseTag(body: String, keepNames: Boolean, declared: DeclaredEntities?): KiteXmlToken? {
        val t = body.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("/")) return KiteXmlToken.Close(localName(t.substring(1).trim()))
        val selfClose = t.endsWith("/")
        val core = if (selfClose) t.dropLast(1).trim() else t
        val sp = core.indexOfFirst { it == ' ' || it == '\t' || it == '\n' || it == '\r' }
        val name = localName(if (sp < 0) core else core.substring(0, sp))
        val attrs = if (sp < 0) emptyMap() else parseAttrs(core.substring(sp + 1), keepNames, declared)
        return KiteXmlToken.Open(name, attrs, selfClose)
    }

    private fun parseAttrs(s: String, keepNames: Boolean, declared: DeclaredEntities?): Map<String, String> {
        val attrs = LinkedHashMap<String, String>()
        var i = 0
        val n = s.length
        while (i < n) {
            while (i < n && s[i].isWhitespace()) i++
            val keyStart = i
            while (i < n && s[i] != '=' && !s[i].isWhitespace()) i++
            if (i <= keyStart) { i++; continue }
            val written = s.substring(keyStart, i)
            val key = if (keepNames) written else localName(written)
            // A name that is already there keeps its first value when names are kept as written.
            val keep = !keepNames || key !in attrs
            while (i < n && s[i].isWhitespace()) i++
            if (i < n && s[i] == '=') {
                i++
                while (i < n && s[i].isWhitespace()) i++
                if (i < n && (s[i] == '"' || s[i] == '\'')) {
                    val q = s[i]; i++
                    val vStart = i
                    while (i < n && s[i] != q) i++
                    if (keep) attrs[key] = decodeEntities(s.substring(vStart, i), declared)
                    if (i < n) i++
                } else {
                    val vStart = i
                    while (i < n && !s[i].isWhitespace()) i++
                    if (keep) attrs[key] = decodeEntities(s.substring(vStart, i), declared)
                }
            } else if (keep) {
                attrs[key] = ""
            }
        }
        return attrs
    }

    /** Drop any namespace prefix (e.g. `opf:item` -> `item`). */
    private fun localName(name: String): String =
        name.substringAfterLast(':').lowercase()

    /**
     * [s] with its character references replaced: XML's five, numeric ones, and the names of
     * HTML's table (HTML, 13.5), which hold the entities the XHTML DTDs declare, so a chapter
     * may write `&mdash;` as its DOCTYPE allows (#570). A name the DOCTYPE's internal subset
     * declares takes its replacement text, before HTML's table (#571). A name that ends in no
     * semicolon, or that no table has, stays as text. [depth] counts the entities being expanded.
     */
    private fun decodeEntities(s: String, declared: DeclaredEntities? = null, depth: Int = 0): String {
        if ('&' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = referenceEnd(s, i + 1)
                if (semi > i + 1) {
                    val ent = s.substring(i + 1, semi)
                    val rep = when {
                        ent == "amp" -> "&"
                        ent == "lt" -> "<"
                        ent == "gt" -> ">"
                        ent == "quot" -> "\""
                        ent == "apos" -> "'"
                        ent.startsWith("#x") || ent.startsWith("#X") ->
                            ent.substring(2).toIntOrNull(16)?.let { cp -> charsFor(cp) }
                        ent.startsWith("#") ->
                            ent.substring(1).toIntOrNull()?.let { cp -> charsFor(cp) }
                        else -> declared?.expand(ent, depth) ?: HtmlEntities.lookup(ent)
                    }
                    if (rep != null) { sb.append(rep); i = semi + 1; continue }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    /**
     * The index of the `;` that ends a reference whose name starts at [from], or -1. A name is
     * letters and digits, or `#` and a number, no longer than the longest of HTML's names, so a
     * lone `&` costs a short look and never a scan of the rest of the text.
     */
    private fun referenceEnd(s: String, from: Int): Int {
        val stop = minOf(s.length, from + HtmlEntities.LONGEST + 1)
        var i = from
        while (i < stop) {
            val c = s[i]
            when {
                c == ';' -> return i
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || (c == '#' && i == from) -> i++
                else -> return -1
            }
        }
        return -1
    }

    /** A DOCTYPE read to its end: the index just past its `>`, and the entities its internal subset declares. */
    private class Doctype(val end: Int, val entities: DeclaredEntities?)

    /**
     * The DOCTYPE whose body starts at [from], just past `<!DOCTYPE`, or null when it never ends.
     * A quoted literal, and an internal subset in brackets with its declarations, comments and
     * literals, may hold a `>` that ends nothing (XML 1.0, 2.8).
     */
    private fun readDoctype(xml: String, from: Int): Doctype? {
        var i = from
        var entities: DeclaredEntities? = null
        while (i < xml.length) {
            when (val c = xml[i]) {
                '"', '\'' -> i = xml.indexOf(c, i + 1).takeIf { it >= 0 } ?: return null
                '[' -> {
                    val close = subsetEnd(xml, i + 1) ?: return null
                    entities = declaredEntities(xml.substring(i + 1, close))
                    i = close
                }
                '>' -> return Doctype(i + 1, entities)
            }
            i++
        }
        return null
    }

    /** The index of the `]` that ends the internal subset starting at [from], past its comments, instructions and literals, or null. */
    private fun subsetEnd(xml: String, from: Int): Int? {
        var i = from
        while (i < xml.length) {
            val c = xml[i]
            i = when {
                xml.startsWith("<!--", i) -> xml.indexOf("-->", i + 4).takeIf { it >= 0 }?.plus(3) ?: return null
                xml.startsWith("<?", i) -> xml.indexOf("?>", i + 2).takeIf { it >= 0 }?.plus(2) ?: return null
                c == '"' || c == '\'' -> xml.indexOf(c, i + 1).takeIf { it >= 0 }?.plus(1) ?: return null
                c == ']' -> return i
                else -> i + 1
            }
        }
        return null
    }

    /**
     * The general entities that [subset] declares, the first declaration of a name binding, or
     * null for none (XML 1.0, 4.2). A parameter entity is left out, and an external one is only
     * named, since a book's DTD never fetches a file.
     */
    private fun declaredEntities(subset: String): DeclaredEntities? {
        val internal = HashMap<String, String>()
        val external = HashSet<String>()
        for (m in ENTITY_DECLARATION.findAll(COMMENT.replace(subset, ""))) {
            val name = m.groupValues[1]
            if (name in internal || name in external) continue
            val literal = m.groups[2]?.value ?: m.groups[3]?.value
            if (literal != null) internal[name] = literal else external += name
        }
        return if (internal.isEmpty() && external.isEmpty()) null else DeclaredEntities(internal, external)
    }

    private val COMMENT = Regex("<!--[\\s\\S]*?-->")

    /** `<!ENTITY name`, then a quoted replacement text, or the keyword of an external identifier. */
    private val ENTITY_DECLARATION = Regex("""<!ENTITY\s+([^\s%"'>]+)\s+(?:"([^"]*)"|'([^']*)'|(?:SYSTEM|PUBLIC)\b)""")

    /**
     * The general entities of a DOCTYPE's internal subset: the replacement text of each internal
     * one, and the names of the external ones, which stand for nothing. A replacement text is read
     * as text, its references expanded where it is used, and markup in it stays as text. The
     * expansion stops at a depth and spends a budget of characters for the whole document, so
     * entities that nest cannot grow a page of markup into gigabytes.
     */
    private class DeclaredEntities(private val internal: Map<String, String>, private val external: Set<String>) {
        private var budget = EXPANSION_BUDGET

        /** What `&name;` stands for, or null when the subset does not declare it or it may expand no further. */
        fun expand(name: String, depth: Int): String? {
            if (name in external) return ""
            val text = internal[name] ?: return null
            if (depth >= MAX_EXPANSION_DEPTH || budget <= 0) return null
            return decodeEntities(text, this, depth + 1).also { budget -= it.length }
        }
    }

    /** How deep entities may nest in one another. */
    private const val MAX_EXPANSION_DEPTH = 16

    /** How many characters the entities of one document may expand to, counted at each level of nesting. */
    private const val EXPANSION_BUDGET = 1_000_000

    private fun charsFor(cp: Int): String =
        if (cp in 0..0x10FFFF) buildString { appendCodePointCompat(cp) } else ""

    private fun StringBuilder.appendCodePointCompat(cp: Int) {
        if (cp <= 0xFFFF) append(cp.toChar())
        else {
            val v = cp - 0x10000
            append((0xD800 + (v ushr 10)).toChar())
            append((0xDC00 + (v and 0x3FF)).toChar())
        }
    }

    /**
     * Fold the tokens into a tree. Strict about nesting only where it can be:
     * an end tag closes the nearest matching open element and is ignored when
     * nothing matches, so a stray `</b>` cannot truncate the document.
     *
     * This is the XML reading; HTML's implied end tags (`<p>`, `<li>`, table
     * rows) are not applied, so use it for XML and SVG, not for tag soup. The
     * tree has a [KiteXmlNode.Comment] for each comment for [keepComments].
     */
    public fun parse(xml: String, keepComments: Boolean = false): KiteXmlNode.Element {
        val root = KiteXmlNode.Element("#root", emptyMap())
        val stack = ArrayList<KiteXmlNode.Element>().apply { add(root) }
        // Per-tag open positions make hostile unmatched/outer close tags O(n)
        // overall. Repeated indexOfLast scans made a deeply nested malformed
        // document quadratic.
        val openPositions = HashMap<String, ArrayList<Int>>()
        for (t in tokenize(xml, keepComments)) when (t) {
            is KiteXmlToken.Open -> {
                val el = KiteXmlNode.Element(t.name, t.attrs)
                el.parent = stack.last()
                stack.last().children.add(el)
                if (!t.selfClose) {
                    stack.add(el)
                    openPositions.getOrPut(t.name, ::ArrayList).add(stack.lastIndex)
                }
            }
            is KiteXmlToken.Close -> {
                val at = openPositions[t.name]?.lastOrNull() ?: continue
                while (stack.lastIndex >= at) {
                    val removed = stack.removeAt(stack.lastIndex)
                    val positions = openPositions.getValue(removed.tag)
                    positions.removeAt(positions.lastIndex)
                    if (positions.isEmpty()) openPositions.remove(removed.tag)
                }
            }
            is KiteXmlToken.Text -> stack.last().children.add(KiteXmlNode.Text(t.text))
            is KiteXmlToken.Comment -> stack.last().children.add(KiteXmlNode.Comment(t.text))
        }
        return root
    }
}
