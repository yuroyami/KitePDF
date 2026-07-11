package io.github.yuroyami.kitepdf

/**
 * Parsed view of an XMP (Extensible Metadata Platform) packet stored in the
 * catalog's `/Metadata` stream. The XMP spec is ISO 16684-1; in PDF it's
 * referenced from ISO 32000-1 §14.3.2 (PDF 2.0 §14.3) as the preferred way
 * to carry document metadata. PDF 2.0 deprecates the trailer `/Info` dict
 * but in practice readers consult both.
 *
 * We extract the most common Dublin Core / Adobe PDF / XMP-basic
 * properties. Anything else can be pulled from the [xml] string by the
 * caller using their preferred XML tool — we don't ship a full XML parser.
 *
 * Date fields are exposed as raw ISO 8601 strings (XMP uses 8601 rather
 * than PDF's `D:YYYYMMDDHHmmSS...` form).
 */
public data class PdfXmpMetadata(
    /** dc:title — language alternative; the x-default (or first) value. */
    val title: String? = null,
    /** dc:creator — ordered sequence of author names. */
    val authors: List<String> = emptyList(),
    /** dc:description — language alternative; the x-default (or first) value. */
    val description: String? = null,
    /** pdf:Keywords — flat string, semicolons or commas at the author's discretion. */
    val keywords: String? = null,
    /** dc:subject — unordered Bag of subject tags. */
    val subjects: List<String> = emptyList(),
    /** pdf:Producer — the producing tool. */
    val producer: String? = null,
    /** xmp:CreatorTool — the originating authoring tool. */
    val creatorTool: String? = null,
    /** xmp:CreateDate — ISO 8601. */
    val createDate: String? = null,
    /** xmp:ModifyDate — ISO 8601. */
    val modifyDate: String? = null,
    /** xmp:MetadataDate — ISO 8601; when the XMP packet itself was last modified. */
    val metadataDate: String? = null,
    /** pdf:PDFVersion — e.g. "1.7" or "2.0". */
    val pdfVersion: String? = null,
    /** Raw XML packet (UTF-8 decoded). */
    val xml: String,
) {

    public companion object {
        public fun parse(xml: String): PdfXmpMetadata = XmpExtractor.extract(xml)
    }
}

/**
 * Lightweight XMP extractor. We don't ship a full XML parser; instead we do
 * focused string scanning for the handful of property paths the public
 * surface exposes. The scanner is whitespace-tolerant, handles both element
 * and attribute forms (rdf:Description with attributes vs nested elements),
 * and decodes the five core XML entities.
 *
 * What we deliberately do NOT do:
 *  - validate against the XMP / RDF schema
 *  - preserve qualifiers (xml:lang, rdf:value chains beyond the first)
 *  - parse RDF type relationships
 *  - resolve XML namespace prefix declarations (we hard-code the
 *    conventional prefixes since virtually every XMP packet uses them)
 *
 * If a packet uses unusual prefixes, the affected field will read as null
 * and the caller can use [PdfXmpMetadata.xml] for custom parsing.
 */
internal object XmpExtractor {

    fun extract(xml: String): PdfXmpMetadata {
        return PdfXmpMetadata(
            title = elementOrAttribute(xml, "dc:title")?.firstOrLangDefault(),
            authors = elementOrAttribute(xml, "dc:creator")?.values ?: emptyList(),
            description = elementOrAttribute(xml, "dc:description")?.firstOrLangDefault(),
            keywords = elementOrAttribute(xml, "pdf:Keywords")?.firstOrLangDefault(),
            subjects = elementOrAttribute(xml, "dc:subject")?.values ?: emptyList(),
            producer = elementOrAttribute(xml, "pdf:Producer")?.firstOrLangDefault(),
            creatorTool = elementOrAttribute(xml, "xmp:CreatorTool")?.firstOrLangDefault(),
            createDate = elementOrAttribute(xml, "xmp:CreateDate")?.firstOrLangDefault(),
            modifyDate = elementOrAttribute(xml, "xmp:ModifyDate")?.firstOrLangDefault(),
            metadataDate = elementOrAttribute(xml, "xmp:MetadataDate")?.firstOrLangDefault(),
            pdfVersion = elementOrAttribute(xml, "pdf:PDFVersion")?.firstOrLangDefault(),
            xml = xml,
        )
    }

    /**
     * Collected values for an XMP property. Some properties are simple
     * (single value), others are language alternatives (lang → value), and
     * others are ordered/unordered collections.
     */
    private data class XmpValues(
        val values: List<String>,
        /** xml:lang attribute paired with each entry in [values], same order. */
        val langs: List<String?>,
    ) {
        /** Pick the `x-default` value, or fall back to the first. Returns null on empty. */
        fun firstOrLangDefault(): String? {
            if (values.isEmpty()) return null
            val defaultIdx = langs.indexOf("x-default")
            return if (defaultIdx >= 0) values[defaultIdx] else values[0]
        }
    }

    /**
     * Try element form first (`<ns:Name>…</ns:Name>` inside rdf:Description);
     * if missing, look for the attribute form on rdf:Description.
     */
    private fun elementOrAttribute(xml: String, qname: String): XmpValues? {
        elementContent(xml, qname)?.let { content ->
            val inner = content.trim()
            // If the content holds an Alt/Seq/Bag, walk into rdf:li children.
            val container = detectContainer(inner)
            if (container != null) {
                return extractLiItems(container)
            }
            // Otherwise the content is the value itself (after entity decode).
            return XmpValues(listOf(decodeEntities(inner)), listOf(null))
        }
        // Attribute form: scan rdf:Description tags.
        val attr = attributeValueOnDescription(xml, qname) ?: return null
        return XmpValues(listOf(attr), listOf(null))
    }

    /** Return the substring between `<qname …>` and `</qname>`, or null. */
    private fun elementContent(xml: String, qname: String): String? {
        var searchFrom = 0
        while (true) {
            val open = xml.indexOf("<$qname", searchFrom)
            if (open < 0) return null
            val nameEnd = open + 1 + qname.length
            // Ensure the next char is a tag-boundary (whitespace, '>', or '/').
            val nextChar = xml.getOrNull(nameEnd) ?: return null
            if (nextChar !in WHITESPACE && nextChar != '>' && nextChar != '/') {
                searchFrom = open + 1
                continue
            }
            val tagClose = xml.indexOf('>', nameEnd)
            if (tagClose < 0) return null
            // Self-closing tag → empty content.
            if (xml[tagClose - 1] == '/') return ""
            val close = xml.indexOf("</$qname>", tagClose + 1)
            if (close < 0) return null
            return xml.substring(tagClose + 1, close)
        }
    }

    /**
     * If the trimmed content opens with `<rdf:Alt>`, `<rdf:Seq>`, or
     * `<rdf:Bag>`, return its inner body so we can extract the rdf:li
     * children. Otherwise null.
     */
    private fun detectContainer(content: String): String? {
        for (tag in CONTAINER_TAGS) {
            val opening = "<$tag"
            if (content.startsWith(opening)) {
                val tagClose = content.indexOf('>')
                if (tagClose < 0) return null
                val closing = "</$tag>"
                val closeIdx = content.lastIndexOf(closing)
                if (closeIdx < 0) return null
                return content.substring(tagClose + 1, closeIdx)
            }
        }
        return null
    }

    private fun extractLiItems(content: String): XmpValues {
        val values = mutableListOf<String>()
        val langs = mutableListOf<String?>()
        var p = 0
        while (true) {
            val open = content.indexOf("<rdf:li", p)
            if (open < 0) break
            val tagEnd = content.indexOf('>', open + 7)
            if (tagEnd < 0) break
            val openTagBody = content.substring(open + 7, tagEnd)
            val lang = parseAttribute(openTagBody, "xml:lang")
            // Self-closing → empty value.
            if (content[tagEnd - 1] == '/') {
                values += ""
                langs += lang
                p = tagEnd + 1
                continue
            }
            val close = content.indexOf("</rdf:li>", tagEnd + 1)
            if (close < 0) break
            values += decodeEntities(content.substring(tagEnd + 1, close).trim())
            langs += lang
            p = close + 9
        }
        return XmpValues(values, langs)
    }

    /**
     * Scan all rdf:Description tags and return the first matching attribute.
     * Real XMP packets usually have only one Description block, but the
     * spec permits multiple (one per namespace).
     */
    private fun attributeValueOnDescription(xml: String, qname: String): String? {
        var p = 0
        while (true) {
            val open = xml.indexOf("<rdf:Description", p)
            if (open < 0) return null
            val tagEnd = xml.indexOf('>', open + 16)
            if (tagEnd < 0) return null
            val body = xml.substring(open + 16, tagEnd)
            parseAttribute(body, qname)?.let { return it }
            p = tagEnd + 1
        }
    }

    /**
     * Find an attribute named [name] in [tagBody] (the substring between the
     * tag's element-name and the closing `>`). Handles both single and
     * double quotes; returns the entity-decoded value or null.
     */
    private fun parseAttribute(tagBody: String, name: String): String? {
        var p = 0
        while (p < tagBody.length) {
            // Skip whitespace.
            while (p < tagBody.length && tagBody[p] in WHITESPACE) p++
            // Read attribute name.
            val nameStart = p
            while (p < tagBody.length && tagBody[p] != '=' && tagBody[p] !in WHITESPACE) p++
            if (p == nameStart) break
            val attrName = tagBody.substring(nameStart, p)
            // Skip whitespace + '='.
            while (p < tagBody.length && tagBody[p] in WHITESPACE) p++
            if (p >= tagBody.length || tagBody[p] != '=') {
                if (attrName == name) return ""
                continue
            }
            p++ // '='
            while (p < tagBody.length && tagBody[p] in WHITESPACE) p++
            val quote = tagBody.getOrNull(p) ?: return null
            if (quote != '"' && quote != '\'') return null
            p++
            val valStart = p
            while (p < tagBody.length && tagBody[p] != quote) p++
            val value = tagBody.substring(valStart, p)
            if (p < tagBody.length) p++ // closing quote
            if (attrName == name) return decodeEntities(value)
        }
        return null
    }

    private fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        val sb = StringBuilder(s.length)
        var p = 0
        while (p < s.length) {
            val c = s[p]
            if (c != '&') { sb.append(c); p++; continue }
            val semi = s.indexOf(';', p + 1)
            if (semi < 0) { sb.append(c); p++; continue }
            val entity = s.substring(p + 1, semi)
            val resolved: Char? = when {
                entity == "lt" -> '<'
                entity == "gt" -> '>'
                entity == "amp" -> '&'
                entity == "apos" -> '\''
                entity == "quot" -> '"'
                entity.startsWith("#x") || entity.startsWith("#X") ->
                    entity.substring(2).toIntOrNull(16)?.toChar()
                entity.startsWith("#") ->
                    entity.substring(1).toIntOrNull()?.toChar()
                else -> null
            }
            if (resolved != null) {
                sb.append(resolved); p = semi + 1
            } else {
                sb.append(c); p++
            }
        }
        return sb.toString()
    }

    private val WHITESPACE = setOf(' ', '\t', '\n', '\r')
    private val CONTAINER_TAGS = listOf("rdf:Alt", "rdf:Seq", "rdf:Bag")
}
