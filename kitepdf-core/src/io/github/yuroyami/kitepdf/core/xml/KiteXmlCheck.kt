package io.github.yuroyami.kitepdf.core.xml

/**
 * A place where a document breaks the well-formedness rules of XML 1.0 or of Namespaces in XML,
 * as [KiteXml.wellFormednessErrors] finds it. [line] and [column] start at 1 and count UTF-16
 * units.
 */
public data class KiteXmlError(val line: Int, val column: Int, val message: String)

/**
 * The strict reading of [xml] that [KiteXml.tokenize] does not do: it walks the markup once and
 * notes each break of a well-formedness constraint of XML 1.0 and of Namespaces in XML 1.0, as a
 * non-validating processor sees them (#517). It stops after [limit] errors.
 */
internal class XmlWellFormedness(private val s: String, private val limit: Int) {

    private val errors = ArrayList<KiteXmlError>()
    private val n = s.length
    private var i = 0

    /** The open elements, innermost last: each one's name and where its start tag begins. */
    private val open = ArrayList<Pair<String, Int>>()

    /** The prefixes each open element declares, innermost last, for the namespace checks. */
    private val scopes = ArrayList<Map<String, String>>()

    private var rootSeen = false
    private var rootClosed = false
    private var doctypeSeen = false

    /** The general entities that the internal subset declares. */
    private val declared = HashSet<String>()

    /**
     * Whether a DTD outside the document may declare more entities: an external subset or a
     * parameter entity reference. A non-validating processor then cannot know an undeclared
     * entity is an error (XML 1.0, 4.1, "Entity Declared").
     */
    private var moreDeclarations = false

    fun run(): List<KiteXmlError> {
        if (s.startsWith('﻿')) i = 1
        if (s.startsWith("<?xml", i) && (i + 5 < n && (s[i + 5].isXmlSpace() || s[i + 5] == '?'))) {
            val end = s.indexOf("?>", i)
            if (end < 0) {
                stop(i, "the XML declaration never ends")
                return errors
            }
            i = end + 2
        }
        while (i < n && errors.size < limit) {
            val c = s[i]
            when {
                c == '<' -> markup()
                c == '&' -> {
                    if (open.isEmpty()) error(i, "a reference outside the root element")
                    reference(inAttribute = false)
                }
                else -> text()
            }
        }
        if (errors.size < limit) {
            for ((name, at) in open.asReversed()) error(at, "the element <$name> is never closed")
            if (!rootSeen) error(n, "the document has no root element")
        }
        return errors.take(limit)
    }

    private fun markup() {
        when {
            s.startsWith("<!--", i) -> comment()
            s.startsWith("<![CDATA[", i) -> {
                if (open.isEmpty()) error(i, "a CDATA section outside the root element")
                val end = s.indexOf("]]>", i + 9)
                if (end < 0) stop(i, "the CDATA section never ends") else i = end + 3
            }
            s.startsWith("<!DOCTYPE", i) -> doctype()
            s.startsWith("<?", i) -> instruction()
            s.startsWith("</", i) -> endTag()
            s.startsWith("<!", i) -> {
                error(i, "markup that is not a comment, a CDATA section or a DOCTYPE")
                skipPast('>')
            }
            else -> startTag()
        }
    }

    private fun text() {
        val start = i
        while (i < n && s[i] != '<' && s[i] != '&') {
            val c = s[i]
            if (c == ']' && s.startsWith("]]>", i)) error(i, "]]> in text; write ]]&gt;")
            if (!c.isXmlChar(s, i)) error(i, "the character U+${c.code.toString(16).uppercase().padStart(4, '0')} may not appear in XML")
            i++
        }
        if (open.isEmpty()) {
            val visible = (start until i).firstOrNull { !s[it].isXmlSpace() } ?: return
            error(visible, if (rootClosed) "text after the root element" else "text before the root element")
        }
    }

    private fun comment() {
        val end = s.indexOf("-->", i + 4)
        if (end < 0) return stop(i, "the comment never ends")
        val body = s.substring(i + 4, end)
        if ("--" in body || body.endsWith("-")) error(i, "-- inside a comment")
        i = end + 3
    }

    private fun instruction() {
        val start = i
        i += 2
        val target = name()
        if (target.isEmpty()) error(start, "a processing instruction without a target")
        else if (target.equals("xml", ignoreCase = true)) error(start, "an XML declaration that is not at the start of the document")
        val end = s.indexOf("?>", i)
        if (end < 0) return stop(start, "the processing instruction never ends")
        i = end + 2
    }

    private fun doctype() {
        val start = i
        if (doctypeSeen || rootSeen) error(start, "a DOCTYPE where none may be")
        doctypeSeen = true
        i += 9
        while (i < n) {
            when (val c = s[i]) {
                '"', '\'' -> i = s.indexOf(c, i + 1).takeIf { it >= 0 } ?: return stop(start, "the DOCTYPE never ends")
                '[' -> i = subset(i + 1) ?: return stop(start, "the DOCTYPE's internal subset never ends")
                '>' -> {
                    i++
                    val head = s.substring(start, i)
                    if (Regex("""\s(SYSTEM|PUBLIC)\s""").containsMatchIn(head.substringBefore('['))) moreDeclarations = true
                    return
                }
            }
            i++
        }
        stop(start, "the DOCTYPE never ends")
    }

    /** Reads the internal subset from [from] and returns the index of its `]`, or null. */
    private fun subset(from: Int): Int? {
        var j = from
        while (j < n) {
            val c = s[j]
            j = when {
                s.startsWith("<!--", j) -> s.indexOf("-->", j + 4).takeIf { it >= 0 }?.plus(3) ?: return null
                s.startsWith("<?", j) -> s.indexOf("?>", j + 2).takeIf { it >= 0 }?.plus(2) ?: return null
                c == '"' || c == '\'' -> s.indexOf(c, j + 1).takeIf { it >= 0 }?.plus(1) ?: return null
                s.startsWith("<!ENTITY", j) -> {
                    val m = ENTITY_NAME.matchAt(s, j)
                    if (m != null && m.groupValues[1].isEmpty()) declared += m.groupValues[2]
                    // Past the name, so the % of a parameter entity's declaration is not taken for a reference.
                    m?.range?.last?.plus(1) ?: (j + 8)
                }
                c == '%' -> {
                    moreDeclarations = true
                    j + 1
                }
                c == ']' -> return j
                else -> j + 1
            }
        }
        return null
    }

    private fun startTag() {
        val start = i
        i++
        val name = name()
        if (name.isEmpty()) {
            error(start, "a < that starts no tag; write &lt;")
            return
        }
        qualified(name, start)
        val attributes = LinkedHashMap<String, String>()
        var selfClosing = false
        while (true) {
            val spaced = skipSpace()
            if (i >= n) return stop(start, "the start tag <$name> never ends")
            if (s.startsWith("/>", i)) {
                i += 2
                selfClosing = true
                break
            }
            if (s[i] == '>') {
                i++
                break
            }
            if (!spaced) error(i, "no white space before the attribute")
            val at = i
            val attribute = name()
            if (attribute.isEmpty()) {
                error(i, "a character that starts no attribute name in <$name>")
                skipPast('>')
                break
            }
            skipSpace()
            if (i >= n || s[i] != '=') {
                error(at, "the attribute $attribute has no value")
                continue
            }
            i++
            skipSpace()
            val quote = s.getOrNull(i)
            if (quote != '"' && quote != '\'') {
                error(at, "the value of $attribute is not in quotes")
                while (i < n && !s[i].isXmlSpace() && s[i] != '>' && !s.startsWith("/>", i)) i++
                continue
            }
            i++
            val valueStart = i
            while (i < n && s[i] != quote) {
                when (s[i]) {
                    '<' -> { error(i, "< in the value of $attribute; write &lt;"); i++ }
                    '&' -> reference(inAttribute = true)
                    else -> i++
                }
            }
            if (i >= n) return stop(at, "the value of $attribute never ends")
            val value = s.substring(valueStart, i)
            i++
            qualified(attribute, at)
            if (attributes.put(attribute, value) != null) error(at, "the attribute $attribute appears twice in <$name>")
        }
        val scope = HashMap<String, String>()
        for ((attribute, value) in attributes) {
            if (attribute == "xmlns") scope[""] = value
            else if (attribute.startsWith("xmlns:")) {
                val prefix = attribute.substring(6)
                when {
                    prefix == "xmlns" -> error(start, "the prefix xmlns may not be declared")
                    prefix == "xml" && value != XML_NAMESPACE -> error(start, "the prefix xml may name only its own namespace")
                    value.isEmpty() -> error(start, "the prefix $prefix is declared with an empty name")
                }
                scope[prefix] = value
            }
        }
        scopes += scope
        prefixOf(name)?.let { if (!declaredPrefix(it)) error(start, "the prefix $it of <$name> is not declared") }
        for (attribute in attributes.keys) {
            val prefix = prefixOf(attribute) ?: continue
            if (prefix != "xmlns" && !declaredPrefix(prefix)) error(start, "the prefix $prefix of $attribute is not declared")
        }
        if (open.isEmpty()) {
            if (rootClosed) error(start, "a second root element")
            rootSeen = true
        }
        if (selfClosing) {
            scopes.removeAt(scopes.lastIndex)
            if (open.isEmpty()) rootClosed = true
        } else {
            open += name to start
        }
    }

    private fun endTag() {
        val start = i
        i += 2
        val name = name()
        skipSpace()
        if (i < n && s[i] == '>') i++
        else {
            error(start, "the end tag </$name> holds more than its name")
            skipPast('>')
        }
        val depth = open.indexOfLast { it.first == name }
        when {
            open.isEmpty() -> error(start, "the end tag </$name> has no start tag")
            depth == open.lastIndex -> close()
            depth >= 0 -> {
                while (open.lastIndex > depth) {
                    val (inner, at) = open.last()
                    error(at, "the element <$inner> is never closed")
                    close()
                }
                close()
            }
            else -> error(start, "the end tag </$name> does not match <${open.last().first}>")
        }
    }

    private fun close() {
        open.removeAt(open.lastIndex)
        scopes.removeAt(scopes.lastIndex)
        if (open.isEmpty()) rootClosed = true
    }

    private fun reference(inAttribute: Boolean) {
        val start = i
        i++
        if (i < n && s[i] == '#') {
            i++
            val hex = i < n && s[i] == 'x'
            if (hex) i++
            val digits = i
            while (i < n && (if (hex) s[i].isHexDigit() else s[i] in '0'..'9')) i++
            val code = s.substring(digits, i).takeIf { it.isNotEmpty() && it.length <= 8 }?.toLongOrNull(if (hex) 16 else 10)
            if (code == null || i >= n || s[i] != ';') {
                error(start, "a character reference that does not end with ;")
                return
            }
            i++
            if (!isXmlCodePoint(code)) error(start, "the character reference ${s.substring(start, i)} names a character XML does not allow")
            return
        }
        val name = name()
        if (name.isEmpty() || i >= n || s[i] != ';') {
            error(start, if (inAttribute) "an & that starts no reference in an attribute; write &amp;" else "an & that starts no reference; write &amp;")
            return
        }
        i++
        if (name !in PREDEFINED && name !in declared && !moreDeclarations) error(start, "the entity &$name; is not declared")
    }

    /** Reads an XML Name at [i], or nothing. */
    private fun name(): String {
        val start = i
        if (i >= n || !isNameStart(s[i])) return ""
        i++
        while (i < n && isNameChar(s[i])) i++
        return s.substring(start, i)
    }

    /** Notes [name] when it is not a QName: at most one colon, with a name on each side (Namespaces in XML 1.0, 4). */
    private fun qualified(name: String, at: Int) {
        val colon = name.indexOf(':')
        if (colon < 0) return
        if (colon == 0 || colon == name.lastIndex || name.indexOf(':', colon + 1) >= 0) error(at, "$name is not a name with a namespace prefix")
    }

    private fun prefixOf(name: String): String? = name.indexOf(':').takeIf { it > 0 }?.let { name.substring(0, it) }

    private fun declaredPrefix(prefix: String): Boolean = prefix == "xml" || scopes.any { prefix in it }

    private fun skipSpace(): Boolean {
        val start = i
        while (i < n && s[i].isXmlSpace()) i++
        return i > start
    }

    private fun skipPast(c: Char) {
        val end = s.indexOf(c, i)
        i = if (end < 0) n else end + 1
    }

    private fun error(at: Int, message: String) {
        if (errors.size >= limit) return
        var line = 1
        var lineStart = 0
        for (k in 0 until minOf(at, n)) if (s[k] == '\n') {
            line++
            lineStart = k + 1
        }
        errors += KiteXmlError(line, at - lineStart + 1, message)
    }

    /** Notes an error that leaves nothing more to read. */
    private fun stop(at: Int, message: String) {
        error(at, message)
        i = n
    }

    private companion object {
        const val XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace"
        val PREDEFINED = setOf("lt", "gt", "amp", "apos", "quot")

        /** `<!ENTITY`, a `%` for a parameter entity, and the name. */
        val ENTITY_NAME = Regex("""<!ENTITY\s+(%\s+)?([^\s%"'>]+)""")

        fun Char.isXmlSpace(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r'

        fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

        fun isNameStart(c: Char): Boolean = c == ':' || c == '_' || c in 'A'..'Z' || c in 'a'..'z' ||
            c in 'À'..'Ö' || c in 'Ø'..'ö' || c in 'ø'..'˿' ||
            c in 'Ͱ'..'ͽ' || c in 'Ϳ'..'῿' || c in '‌'..'‍' ||
            c in '⁰'..'↏' || c in 'Ⰰ'..'⿯' || c in '、'..'퟿' ||
            c in '豈'..'﷏' || c in 'ﷰ'..'�' ||
            // A supplementary character, U+10000 to U+EFFFF, arrives as a surrogate pair.
            c in '\uD800'..'\uDFFF'

        fun isNameChar(c: Char): Boolean = isNameStart(c) || c == '-' || c == '.' || c in '0'..'9' ||
            c == '·' || c in '̀'..'ͯ' || c in '‿'..'⁀'

        /** Whether the UTF-16 unit at [at] of [text] is part of a character that XML 1.0, 2.2 allows. */
        fun Char.isXmlChar(text: String, at: Int): Boolean = when (this) {
            '\t', '\n', '\r' -> true
            in '\u0000'..'\u001F', '￾', '￿' -> false
            in '\uD800'..'\uDBFF' -> text.getOrNull(at + 1)?.let { it in '\uDC00'..'\uDFFF' } == true
            in '\uDC00'..'\uDFFF' -> text.getOrNull(at - 1)?.let { it in '\uD800'..'\uDBFF' } == true
            else -> true
        }

        fun isXmlCodePoint(code: Long): Boolean = code == 0x9L || code == 0xAL || code == 0xDL ||
            code in 0x20L..0xD7FFL || code in 0xE000L..0xFFFDL || code in 0x10000L..0x10FFFFL
    }
}
