package io.github.yuroyami.kitepdf.epub.script

import io.github.yuroyami.kitepdf.core.xml.KiteXml
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import io.github.yuroyami.kitepdf.epub.script.ScriptDom.Attribute
import io.github.yuroyami.kitepdf.epub.script.ScriptDom.Companion.XMLNS_NS
import io.github.yuroyami.kitepdf.epub.script.ScriptDom.Companion.XML_NS
import io.github.yuroyami.kitepdf.epub.script.ScriptDom.Name

/**
 * A strict parser of XML 1.0 with namespaces, for the markup a script hands over as XML:
 * `DOMParser` and `innerHTML` in an XML document. Where [KiteXml] reads on past a mistake, this
 * stops at the first well-formedness error (XML 1.0, 2.1, and Namespaces in XML 1.0, 7) and
 * reports where it is, as a browser's XML parser does.
 *
 * It makes the tree as [KiteXmlNode] nodes. What those cannot hold comes back beside them: the
 * name and attributes of each element, which text nodes were CDATA sections, and which comment
 * nodes stand for a processing instruction or a document type.
 */
internal class XmlReader private constructor(text: String, private var htmlEntities: Boolean) {

    /** A document type declaration: its name and its public and system ids, empty when absent. */
    class Doctype(val name: String, val publicId: String, val systemId: String)

    /** The first well-formedness error, and where it is: line and column count from 1. */
    class Error(val message: String, val line: Int, val column: Int) {
        override fun toString(): String = "line $line, column $column: $message"
    }

    /** What a parse made. A comment node in [instructions] is a processing instruction, and its data is the comment's text. */
    class Result(
        val nodes: List<KiteXmlNode>,
        val names: Map<KiteXmlNode.Element, Name>,
        val attributes: Map<KiteXmlNode.Element, List<Attribute>>,
        val cdata: Set<KiteXmlNode.Text>,
        val instructions: Map<KiteXmlNode.Comment, String>,
        val doctypes: Map<KiteXmlNode.Comment, Doctype>,
        val error: Error?,
        /** The XML declaration of a document, as browsers write it back: `<?xml version="1.0"?>`, or null without one. */
        val declaration: String? = null,
    )

    private class Failure(val at: Int, override val message: String) : Exception(message)

    /** The prefixes in scope, and the default namespace, null for none. */
    private class Scope(val prefixes: Map<String, String>, val default: String?)

    /** An element that is open, with what is in scope inside it. */
    private class Open(val element: KiteXmlNode.Element?, val qualifiedName: String?, val children: MutableList<KiteXmlNode>, val scope: Scope)

    // XML 1.0, 2.11: a parser reads each line break as a line feed.
    private val s = text.replace("\r\n", "\n").replace('\r', '\n')
    private var i = 0
    private val names = HashMap<KiteXmlNode.Element, Name>()
    private val attributes = HashMap<KiteXmlNode.Element, List<Attribute>>()
    private val cdata = HashSet<KiteXmlNode.Text>()
    private val instructions = HashMap<KiteXmlNode.Comment, String>()
    private val doctypes = HashMap<KiteXmlNode.Comment, Doctype>()
    private val entities = HashMap<String, String>()
    private var declaration: String? = null

    private fun fail(message: String, at: Int = i): Nothing = throw Failure(at, message)

    private fun result(nodes: List<KiteXmlNode>, failure: Failure?): Result = Result(
        nodes, names, attributes, cdata, instructions, doctypes,
        failure?.let { f ->
            val at = f.at.coerceIn(0, s.length)
            val lineStart = s.lastIndexOf('\n', at - 1) + 1
            Error(f.message, s.count('\n', at) + 1, at - lineStart + 1)
        },
        declaration,
    )

    private fun String.count(c: Char, end: Int): Int {
        var n = 0
        for (k in 0 until end) if (this[k] == c) n++
        return n
    }

    // ---- Documents and fragments ----------------------------------------------------------

    private fun document(): Result {
        val nodes = ArrayList<KiteXmlNode>()
        val failure = try {
            checkCharacters()
            if (s.startsWith('﻿')) i = 1
            if (s.startsWith("<?xml", i) && s.getOrNull(i + 5)?.let(::isSpace) == true) xmlDeclaration()
            misc(nodes)
            if (s.startsWith("<!DOCTYPE", i)) {
                doctype(nodes)
                misc(nodes)
            }
            if (i >= s.length) fail("the document has no root element")
            if (s[i] != '<' || !isNameStart(s.getOrNull(i + 1) ?: ' ')) fail("content is not allowed before the root element")
            tree(nodes, Scope(emptyMap(), null), single = true)
            misc(nodes)
            if (i < s.length) fail("content is not allowed after the root element")
            null
        } catch (f: Failure) {
            f
        }
        return result(nodes, failure)
    }

    private fun fragment(prefixes: Map<String, String>, default: String?): Result {
        val nodes = ArrayList<KiteXmlNode>()
        val failure = try {
            checkCharacters()
            tree(nodes, Scope(prefixes, default), single = false)
            null
        } catch (f: Failure) {
            f
        }
        return result(nodes, failure)
    }

    /** XML 1.0, 2.2: every character is one XML allows, and a surrogate is half of a pair. */
    private fun checkCharacters() {
        var k = 0
        while (k < s.length) {
            val c = s[k]
            when {
                c == '\t' || c == '\n' -> {}
                c < ' ' -> fail("the character U+${c.code.toString(16).padStart(4, '0').uppercase()} is not allowed", k)
                c.isHighSurrogate() -> if (s.getOrNull(k + 1)?.isLowSurrogate() == true) k++ else fail("a surrogate is not paired", k)
                c.isLowSurrogate() -> fail("a surrogate is not paired", k)
                c == '￾' || c == '￿' -> fail("the character U+${c.code.toString(16).uppercase()} is not allowed", k)
            }
            k++
        }
    }

    /** Comments, processing instructions and white space outside the document element; the white space is not kept. */
    private fun misc(into: MutableList<KiteXmlNode>) {
        while (i < s.length) {
            when {
                isSpace(s[i]) -> i++
                s.startsWith("<!--", i) -> into += comment()
                s.startsWith("<?", i) -> into += instruction()
                else -> return
            }
        }
    }

    private fun xmlDeclaration() {
        i += 5
        val end = s.indexOf("?>", i)
        if (end < 0) fail("the XML declaration is not closed")
        val body = s.substring(i, end).trim()
        val match = Regex("""version\s*=\s*(["'])(1\.[0-9]+)\1(\s+encoding\s*=\s*(["'])([A-Za-z][A-Za-z0-9._-]*)\4)?(\s+standalone\s*=\s*(["'])(yes|no)\7)?""")
            .matchEntire(body) ?: fail("the XML declaration is not well-formed")
        val g = match.groupValues
        declaration = buildString {
            append("<?xml version=\"").append(g[2]).append('"')
            if (g[5].isNotEmpty()) append(" encoding=\"").append(g[5]).append('"')
            if (g[8].isNotEmpty()) append(" standalone=\"").append(g[8]).append('"')
            append("?>")
        }
        i = end + 2
    }

    // ---- The document type declaration (XML 1.0, 2.8) -------------------------------------

    private fun doctype(into: MutableList<KiteXmlNode>) {
        val start = i
        i += 9
        requireSpace("the document type declaration")
        val name = name()
        qualified(name, start)
        var publicId = ""
        var systemId = ""
        skipSpace()
        when {
            s.startsWith("SYSTEM", i) -> {
                i += 6
                requireSpace("SYSTEM")
                systemId = quoted("the system id")
            }
            s.startsWith("PUBLIC", i) -> {
                i += 6
                requireSpace("PUBLIC")
                publicId = quoted("the public id")
                if (publicId.any { it !in PUBLIC_ID_CHARS }) fail("the public id has a character it may not have")
                requireSpace("the public id")
                systemId = quoted("the system id")
            }
        }
        skipSpace()
        if (s.getOrNull(i) == '[') {
            i++
            internalSubset()
            skipSpace()
        }
        if (s.getOrNull(i) != '>') fail("the document type declaration is not closed")
        i++
        // HTML 13.2 (XML documents): a browser reads HTML's named characters in a document of a known XHTML type.
        if (publicId in XHTML_PUBLIC_IDS) htmlEntities = true
        val node = KiteXmlNode.Comment("")
        doctypes[node] = Doctype(name, publicId, systemId)
        into += node
    }

    /** The declarations between the brackets. Only an internal general entity is kept; the others are read past. */
    private fun internalSubset() {
        while (true) {
            skipSpace()
            when {
                i >= s.length -> fail("the internal subset is not closed")
                s[i] == ']' -> { i++; return }
                s.startsWith("<!--", i) -> comment()
                s.startsWith("<?", i) -> instruction()
                s[i] == '%' -> { i++; name(); expect(';', "a parameter entity reference") }
                s.startsWith("<!ENTITY", i) -> entityDeclaration()
                s.startsWith("<!ELEMENT", i) || s.startsWith("<!ATTLIST", i) || s.startsWith("<!NOTATION", i) -> declaration()
                else -> fail("the internal subset holds markup that is not a declaration")
            }
        }
    }

    private fun entityDeclaration() {
        i += 8
        requireSpace("ENTITY")
        val parameter = s.getOrNull(i) == '%'
        if (parameter) { i++; requireSpace("%") }
        val name = name()
        requireSpace("the entity name")
        if (s.getOrNull(i) == '"' || s.getOrNull(i) == '\'') {
            val value = entityValue()
            if (!parameter && name !in entities) entities[name] = value
        } else {
            externalId()
        }
        skipSpace()
        if (!parameter && s.startsWith("NDATA", i)) { i += 5; requireSpace("NDATA"); name(); skipSpace() }
        expect('>', "the entity declaration")
    }

    /** XML 1.0, 4.5: an entity's value with its character references read, and its other references kept for its use. */
    private fun entityValue(): String {
        val quote = s[i++]
        val out = StringBuilder()
        while (true) {
            if (i >= s.length) fail("the entity value is not closed")
            val c = s[i]
            when {
                c == quote -> { i++; return out.toString() }
                c == '&' && s.getOrNull(i + 1) == '#' -> out.appendCodePoint(characterReference())
                c == '%' -> fail("a parameter entity reference in the internal subset may not be in an entity value")
                else -> { out.append(c); i++ }
            }
        }
    }

    private fun externalId() {
        when {
            s.startsWith("SYSTEM", i) -> { i += 6; requireSpace("SYSTEM"); quoted("the system id") }
            s.startsWith("PUBLIC", i) -> { i += 6; requireSpace("PUBLIC"); quoted("the public id"); requireSpace("the public id"); quoted("the system id") }
            else -> fail("the entity has neither a value nor an external id")
        }
    }

    /** A declaration this reader does not need, read to its end past quoted strings. */
    private fun declaration() {
        while (i < s.length) {
            when (val c = s[i]) {
                '>' -> { i++; return }
                '"', '\'' -> { val end = s.indexOf(c, i + 1); if (end < 0) fail("a quoted string is not closed"); i = end + 1 }
                else -> i++
            }
        }
        fail("a declaration is not closed")
    }

    // ---- Elements and their content (XML 1.0, 3) ------------------------------------------

    /**
     * Content into [into], with [scope] in scope: one element and nothing around it when [single],
     * as a document's root, or anything content may hold, as a fragment. An explicit stack keeps
     * a deeply nested document off the call stack.
     */
    private fun tree(into: MutableList<KiteXmlNode>, scope: Scope, single: Boolean) {
        val stack = ArrayList<Open>()
        stack += Open(null, null, into, scope)
        val text = StringBuilder()
        fun flush() {
            if (text.isEmpty()) return
            stack.last().children += KiteXmlNode.Text(text.toString())
            text.setLength(0)
        }
        while (true) {
            if (i >= s.length) {
                if (stack.size > 1) fail("the element ${stack.last().qualifiedName} is not closed")
                flush()
                return
            }
            val c = s[i]
            when {
                c == '<' && s.startsWith("</", i) -> {
                    val start = i
                    if (stack.size == 1) fail("the end tag has no start tag")
                    i += 2
                    val name = name()
                    skipSpace()
                    expect('>', "the end tag")
                    val open = stack.last()
                    if (name != open.qualifiedName) fail("the end tag $name does not match the start tag ${open.qualifiedName}", start)
                    flush()
                    stack.removeAt(stack.lastIndex)
                    if (single && stack.size == 1) return
                }
                c == '<' && s.startsWith("<!--", i) -> { flush(); stack.last().children += comment() }
                c == '<' && s.startsWith("<![CDATA[", i) -> {
                    flush()
                    val end = s.indexOf("]]>", i + 9)
                    if (end < 0) fail("the CDATA section is not closed")
                    val node = KiteXmlNode.Text(s.substring(i + 9, end))
                    cdata += node
                    stack.last().children += node
                    i = end + 3
                }
                c == '<' && s.startsWith("<?", i) -> { flush(); stack.last().children += instruction() }
                c == '<' && s.startsWith("<!", i) -> fail("markup that is not allowed in content")
                c == '<' -> {
                    flush()
                    val open = stack.last()
                    val (element, qualifiedName, inner, empty) = startTag(open.scope)
                    open.children += element
                    element.parent = open.element
                    if (!empty) stack += Open(element, qualifiedName, element.children, inner)
                    else if (single && stack.size == 1) return
                }
                c == '&' -> text.append(reference())
                c == ']' && s.startsWith("]]>", i) -> fail("]]> is not allowed in content")
                else -> { text.append(c); i++ }
            }
        }
    }

    private data class StartTag(val element: KiteXmlNode.Element, val qualifiedName: String, val scope: Scope, val empty: Boolean)

    private fun startTag(outer: Scope): StartTag {
        val start = i
        i++
        val qualifiedName = name()
        val raw = ArrayList<Pair<String, String>>()
        while (true) {
            val spaced = skipSpace()
            when {
                i >= s.length -> fail("the start tag $qualifiedName is not closed")
                s[i] == '>' || s.startsWith("/>", i) -> break
                !spaced -> fail("attributes need white space between them")
            }
            val at = i
            val name = name()
            skipSpace()
            expect('=', "the attribute $name")
            skipSpace()
            val value = attributeValue()
            if (raw.any { it.first == name }) fail("the attribute $name appears twice", at)
            raw += name to value
        }
        val empty = s[i] == '/'
        i += if (empty) 2 else 1

        // Namespaces in XML 1.0, 3: the declarations first, then the names they scope.
        var prefixes = outer.prefixes
        var default = outer.default
        for ((name, value) in raw) {
            when {
                name == "xmlns" -> {
                    if (value == XML_NS || value == XMLNS_NS) fail("the default namespace may not be $value", start)
                    default = value.ifEmpty { null }
                }
                name.startsWith("xmlns:") -> {
                    val prefix = name.substring(6)
                    qualified(prefix, start)
                    when {
                        prefix == "xmlns" -> fail("the prefix xmlns may not be declared", start)
                        prefix == "xml" && value != XML_NS -> fail("the prefix xml may not be bound to another namespace", start)
                        prefix != "xml" && (value == XML_NS || value == XMLNS_NS) -> fail("the namespace $value may not be bound to $prefix", start)
                        value.isEmpty() -> fail("the prefix $prefix may not be undeclared", start)
                    }
                    if (prefixes === outer.prefixes) prefixes = HashMap(outer.prefixes)
                    (prefixes as HashMap)[prefix] = value
                }
            }
        }
        val scope = if (prefixes === outer.prefixes && default == outer.default) outer else Scope(prefixes, default)

        qualified(qualifiedName, start)
        val prefix = qualifiedName.substringBefore(':', "").ifEmpty { null }
        val localName = qualifiedName.substringAfter(':')
        val namespace = if (prefix == null) scope.default else resolve(prefix, scope, start)
        val list = ArrayList<Attribute>(raw.size)
        for ((name, value) in raw) {
            list += when {
                name == "xmlns" -> Attribute(XMLNS_NS, null, name, value)
                name.startsWith("xmlns:") -> Attribute(XMLNS_NS, "xmlns", name.substring(6), value)
                else -> {
                    qualified(name, start)
                    val p = name.substringBefore(':', "").ifEmpty { null }
                    if (p == null) Attribute(null, null, name, value) else Attribute(resolve(p, scope, start), p, name.substringAfter(':'), value)
                }
            }
        }
        for (a in list.indices) for (b in 0 until a) {
            if (list[a].namespace != null && list[a].namespace == list[b].namespace && list[a].localName == list[b].localName) {
                fail("the attribute ${list[a].localName} appears twice in the namespace ${list[a].namespace}", start)
            }
        }

        // The layout keys an element by its lowercased local name, and an attribute the same way, the first of two winning.
        val layout = LinkedHashMap<String, String>(list.size)
        for (a in list) layout.getOrPut(a.qualifiedName.substringAfterLast(':').lowercase()) { a.value }
        val element = KiteXmlNode.Element(localName.lowercase(), layout)
        names[element] = Name(namespace, prefix, localName)
        attributes[element] = list
        return StartTag(element, qualifiedName, scope, empty)
    }

    private fun resolve(prefix: String, scope: Scope, at: Int): String = when (prefix) {
        "xml" -> XML_NS
        "xmlns" -> fail("the prefix xmlns is reserved", at)
        else -> scope.prefixes[prefix] ?: fail("the prefix $prefix is not declared", at)
    }

    /** XML 1.0, 3.3.3: a quoted value, its references read and each white space character a space. */
    private fun attributeValue(): String {
        val quote = s.getOrNull(i)
        if (quote != '"' && quote != '\'') fail("an attribute value is not quoted")
        i++
        val out = StringBuilder()
        while (true) {
            if (i >= s.length) fail("an attribute value is not closed")
            val c = s[i]
            when {
                c == quote -> { i++; return out.toString() }
                c == '<' -> fail("an attribute value may not hold <")
                c == '&' -> {
                    val replaced = reference()
                    // A character reference keeps its white space; an entity's text is normalized as written.
                    out.append(replaced)
                }
                c == '\t' || c == '\n' -> { out.append(' '); i++ }
                else -> { out.append(c); i++ }
            }
        }
    }

    private fun comment(): KiteXmlNode.Comment {
        val start = i
        val end = s.indexOf("--", start + 4)
        if (end < 0) fail("the comment is not closed", start)
        if (s.getOrNull(end + 2) != '>') fail("a comment may not hold --", end)
        i = end + 3
        return KiteXmlNode.Comment(s.substring(start + 4, end))
    }

    private fun instruction(): KiteXmlNode.Comment {
        val start = i
        i += 2
        val target = name()
        if (target.equals("xml", ignoreCase = true)) fail("a processing instruction may not be named $target", start)
        if (':' in target) fail("a processing instruction's target may not hold a colon", start)
        val data = if (s.startsWith("?>", i)) {
            ""
        } else {
            if (!skipSpace()) fail("the target of a processing instruction needs white space after it")
            val end = s.indexOf("?>", i)
            if (end < 0) fail("the processing instruction is not closed", start)
            s.substring(i, end).also { i = end }
        }
        i += 2
        val node = KiteXmlNode.Comment(data)
        instructions[node] = target
        return node
    }

    // ---- References (XML 1.0, 4.1) --------------------------------------------------------

    /** The text a reference at `&` stands for. */
    private fun reference(): String {
        if (s.getOrNull(i + 1) == '#') return StringBuilder().appendCodePoint(characterReference()).toString()
        val start = i
        i++
        if (i >= s.length || !isNameStart(s[i])) fail("& starts no reference")
        val name = name()
        expect(';', "the reference &$name")
        return when (name) {
            "lt" -> "<"
            "gt" -> ">"
            "amp" -> "&"
            "apos" -> "'"
            "quot" -> "\""
            else -> entities[name]?.let(::expand)
                ?: (if (htmlEntities) KiteXml.htmlEntity(name) else null)
                ?: fail("the entity $name is not declared", start)
        }
    }

    /** An entity's replacement text with the predefined references in it read; markup in it stays text. */
    private fun expand(value: String): String =
        if ('&' !in value) value
        else value.replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'").replace("&quot;", "\"").replace("&amp;", "&")

    private fun characterReference(): Int {
        val start = i
        i += 2
        val hex = s.getOrNull(i) == 'x'
        if (hex) i++
        val digitsStart = i
        while (i < s.length && (s[i].isAsciiDigit() || (hex && s[i].lowercaseChar() in 'a'..'f'))) i++
        if (i == digitsStart || s.getOrNull(i) != ';') fail("a character reference is not well-formed", start)
        val code = s.substring(digitsStart, i).toIntOrNull(if (hex) 16 else 10) ?: -1
        i++
        val allowed = code == 0x9 || code == 0xA || code == 0xD || code in 0x20..0xD7FF || code in 0xE000..0xFFFD || code in 0x10000..0x10FFFF
        if (!allowed) fail("a character reference names a character XML does not allow", start)
        return code
    }

    // ---- Names and small pieces -----------------------------------------------------------

    private fun name(): String {
        val start = i
        if (i >= s.length || !isNameStart(s[i])) fail("a name was expected")
        i += if (s[i].isHighSurrogate()) 2 else 1
        while (i < s.length && isNameChar(s[i])) i += if (s[i].isHighSurrogate()) 2 else 1
        return s.substring(start, i)
    }

    /** Namespaces in XML 1.0, 4: no colon, or one between two non-empty parts. */
    private fun qualified(name: String, at: Int) {
        val colon = name.indexOf(':')
        if (colon == 0 || colon == name.length - 1 || (colon > 0 && name.indexOf(':', colon + 1) >= 0)) fail("$name is not a qualified name", at)
        if (colon > 0 && !isNameStart(name[colon + 1])) fail("$name is not a qualified name", at)
    }

    private fun quoted(what: String): String {
        val quote = s.getOrNull(i)
        if (quote != '"' && quote != '\'') fail("$what is not quoted")
        val end = s.indexOf(quote, i + 1)
        if (end < 0) fail("$what is not closed")
        return s.substring(i + 1, end).also { i = end + 1 }
    }

    private fun expect(c: Char, what: String) {
        if (s.getOrNull(i) != c) fail("$what needs $c here")
        i++
    }

    private fun requireSpace(after: String) {
        if (!skipSpace()) fail("$after needs white space after it")
    }

    /** Reads past white space; true when there was some. */
    private fun skipSpace(): Boolean {
        val start = i
        while (i < s.length && isSpace(s[i])) i++
        return i > start
    }

    private fun StringBuilder.appendCodePoint(code: Int): StringBuilder {
        if (code < 0x10000) return append(code.toChar())
        val v = code - 0x10000
        return append((0xD800 + (v shr 10)).toChar()).append((0xDC00 + (v and 0x3FF)).toChar())
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    companion object {
        /** Parses [text] as an XML document. */
        fun document(text: String): Result = XmlReader(text, htmlEntities = false).document()

        /**
         * Parses [text] as the content of an element, with [prefixes] declared and [default] as
         * the default namespace, as HTML's XML fragment parsing algorithm does (13.4). HTML's named
         * characters are read when [htmlEntities], as in a document of a known XHTML type.
         */
        fun fragment(text: String, prefixes: Map<String, String>, default: String?, htmlEntities: Boolean): Result =
            XmlReader(text, htmlEntities).fragment(prefixes, default)

        /** HTML 13.2: the public ids of the document types whose documents read HTML's named characters. */
        val XHTML_PUBLIC_IDS = setOf(
            "-//W3C//DTD XHTML 1.0 Transitional//EN",
            "-//W3C//DTD XHTML 1.1//EN",
            "-//W3C//DTD XHTML 1.0 Strict//EN",
            "-//W3C//DTD XHTML 1.0 Frameset//EN",
            "-//W3C//DTD XHTML Basic 1.0//EN",
            "-//W3C//DTD XHTML 1.1 plus MathML 2.0//EN",
            "-//W3C//DTD XHTML 1.1 plus MathML 2.0 plus SVG 1.1//EN",
            "-//W3C//DTD MathML 2.0//EN",
            "-//WAPFORUM//DTD XHTML Mobile 1.0//EN",
        )

        private const val PUBLIC_ID_CHARS = " \r\nabcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-'()+,./:=?;!*#@\$_%"

        private fun isSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'

        /** XML 1.0, 2.3, NameStartChar. A high surrogate stands for a character from U+10000 to U+EFFFF. */
        fun isNameStart(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c == '_' || c == ':' ||
            c in 'À'..'Ö' || c in 'Ø'..'ö' || c in 'ø'..'˿' || c in 'Ͱ'..'ͽ' ||
            c in 'Ϳ'..'῿' || c in '‌'..'‍' || c in '⁰'..'↏' || c in 'Ⰰ'..'⿯' ||
            c in '、'..'퟿' || c in '豈'..'﷏' || c in 'ﷰ'..'�' || c in '\uD800'..'\uDB7F'

        /** XML 1.0, 2.3, NameChar. */
        fun isNameChar(c: Char): Boolean = isNameStart(c) || c == '-' || c == '.' || c in '0'..'9' || c == '·' ||
            c in '̀'..'ͯ' || c in '‿'..'⁀'
    }
}
