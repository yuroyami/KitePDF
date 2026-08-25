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
        while (i < n) {
            val c = xml[i]
            if (c == '<') {
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
                        val end = xml.indexOf('>', i)
                        if (end < 0) { i = n } else {
                            parseTag(xml.substring(i + 1, end))?.let(out::add)
                            i = end + 1
                        }
                    }
                }
            } else {
                val end = xml.indexOf('<', i).let { if (it < 0) n else it }
                val raw = xml.substring(i, end)
                if (raw.isNotEmpty()) out.add(KiteXmlToken.Text(decodeEntities(raw)))
                i = end
            }
        }
        return out
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
        for (t in tokenize(xml)) when (t) {
            is KiteXmlToken.Open -> {
                val el = KiteXmlNode.Element(t.name, t.attrs)
                el.parent = stack.last()
                stack.last().children.add(el)
                if (!t.selfClose) stack.add(el)
            }
            is KiteXmlToken.Close -> {
                val at = stack.indexOfLast { it.tag == t.name }
                if (at > 0) while (stack.size > at) stack.removeAt(stack.size - 1)
            }
            is KiteXmlToken.Text -> stack.last().children.add(KiteXmlNode.Text(t.text))
        }
        return root
    }
}
