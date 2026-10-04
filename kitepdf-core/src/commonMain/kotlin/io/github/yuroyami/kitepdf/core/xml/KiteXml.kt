package io.github.yuroyami.kitepdf.core.xml

/**
 * A tiny, forgiving XML reader: enough for EPUB's container.xml and OPF, for
 * (X)HTML content, and for SVG.
 *
 * Comments, the XML/DOCTYPE prologue, CDATA delimiters and processing
 * instructions are skipped, and malformed markup is salvaged rather than
 * rejected, because real files are messy. Names lose their namespace prefix
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
}

public object KiteXml {

    /**
     * The flat token stream: start tags with attributes, end tags, and text.
     * Tag soup is salvaged, never rejected. Use [parse] for a tree; callers
     * that need HTML's implied end tags fold the tokens themselves.
     */
    public fun tokenize(xml: String): List<KiteXmlToken> {
        val out = ArrayList<KiteXmlToken>()
        var i = 0
        val n = xml.length
        // The script or style element whose text is being read: a < in it opens nothing but its end
        // tag, a comment or a CDATA section, so a lone < of its code stays text.
        var rawText: String? = null
        // Where an end tag of each raw text element was last looked for in vain: none follows from there on.
        val noEndTagFrom = HashMap<String, Int>()
        while (i < n) {
            val c = xml[i]
            if (c == '<' && (rawText == null || opensInRawText(xml, i, rawText))) {
                when {
                    xml.startsWith("<!--", i) -> { i = xml.indexOf("-->", i).let { if (it < 0) n else it + 3 } }
                    xml.startsWith("<![CDATA[", i) -> {
                        val end = xml.indexOf("]]>", i)
                        val stop = if (end < 0) n else end
                        out.add(KiteXmlToken.Text(xml.substring(i + 9, stop)))
                        i = if (end < 0) n else end + 3
                    }
                    xml.startsWith("<?", i) -> { i = xml.indexOf("?>", i).let { if (it < 0) n else it + 2 } }
                    xml.startsWith("<!", i) -> { i = xml.indexOf('>', i).let { if (it < 0) n else it + 1 } }
                    else -> {
                        val end = tagEnd(xml, i + 1)
                        if (end < 0) { i = n } else {
                            val token = parseTag(xml.substring(i + 1, end))
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
                if (raw.isNotEmpty()) out.add(KiteXmlToken.Text(decodeEntities(raw)))
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

    private fun parseTag(body: String): KiteXmlToken? {
        val t = body.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("/")) return KiteXmlToken.Close(localName(t.substring(1).trim()))
        val selfClose = t.endsWith("/")
        val core = if (selfClose) t.dropLast(1).trim() else t
        val sp = core.indexOfFirst { it == ' ' || it == '\t' || it == '\n' || it == '\r' }
        val name = localName(if (sp < 0) core else core.substring(0, sp))
        val attrs = if (sp < 0) emptyMap() else parseAttrs(core.substring(sp + 1))
        return KiteXmlToken.Open(name, attrs, selfClose)
    }

    private fun parseAttrs(s: String): Map<String, String> {
        val attrs = LinkedHashMap<String, String>()
        var i = 0
        val n = s.length
        while (i < n) {
            while (i < n && s[i].isWhitespace()) i++
            val keyStart = i
            while (i < n && s[i] != '=' && !s[i].isWhitespace()) i++
            if (i <= keyStart) { i++; continue }
            val key = localName(s.substring(keyStart, i))
            while (i < n && s[i].isWhitespace()) i++
            if (i < n && s[i] == '=') {
                i++
                while (i < n && s[i].isWhitespace()) i++
                if (i < n && (s[i] == '"' || s[i] == '\'')) {
                    val q = s[i]; i++
                    val vStart = i
                    while (i < n && s[i] != q) i++
                    attrs[key] = decodeEntities(s.substring(vStart, i))
                    if (i < n) i++
                } else {
                    val vStart = i
                    while (i < n && !s[i].isWhitespace()) i++
                    attrs[key] = decodeEntities(s.substring(vStart, i))
                }
            } else {
                attrs[key] = ""
            }
        }
        return attrs
    }

    /** Drop any namespace prefix (e.g. `opf:item` -> `item`). */
    private fun localName(name: String): String =
        name.substringAfterLast(':').lowercase()

    private fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i)
                if (semi in (i + 1)..(i + 10)) {
                    val ent = s.substring(i + 1, semi)
                    val rep = when {
                        ent == "amp" -> "&"
                        ent == "lt" -> "<"
                        ent == "gt" -> ">"
                        ent == "quot" -> "\""
                        ent == "apos" -> "'"
                        ent == "nbsp" -> " "
                        ent.startsWith("#x") || ent.startsWith("#X") ->
                            ent.substring(2).toIntOrNull(16)?.let { cp -> charsFor(cp) }
                        ent.startsWith("#") ->
                            ent.substring(1).toIntOrNull()?.let { cp -> charsFor(cp) }
                        else -> null
                    }
                    if (rep != null) { sb.append(rep); i = semi + 1; continue }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

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
     * rows) are not applied, so use it for XML and SVG, not for tag soup.
     */
    public fun parse(xml: String): KiteXmlNode.Element {
        val root = KiteXmlNode.Element("#root", emptyMap())
        val stack = ArrayList<KiteXmlNode.Element>().apply { add(root) }
        // Per-tag open positions make hostile unmatched/outer close tags O(n)
        // overall. Repeated indexOfLast scans made a deeply nested malformed
        // document quadratic.
        val openPositions = HashMap<String, ArrayList<Int>>()
        for (t in tokenize(xml)) when (t) {
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
        }
        return root
    }
}
