package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.PdfDate

/**
 * Keeps an XMP packet in step with the document information dictionary that [PdfEditor.setInfo]
 * writes. ISO 32000-2, 14.3.3 deprecates the information dictionary for the metadata stream of
 * 14.3.2, so the packet is the copy that newer readers trust, and a writer that changes one copy
 * changes the other (#692).
 */
internal object XmpSync {

    /** The UTC time now, as a PDF date. */
    @OptIn(kotlin.time.ExperimentalTime::class)
    fun now(): PdfDate = dateOf(kotlin.time.Clock.System.now().epochSeconds)

    /** The UTC date of [epochSeconds] (Howard Hinnant's days-to-civil). */
    fun dateOf(epochSeconds: Long): PdfDate {
        val days = epochSeconds.floorDiv(86_400L)
        val secs = epochSeconds - days * 86_400L
        val z = days + 719_468L
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
        return PdfDate(year, month, day, (secs / 3600).toInt(), ((secs / 60) % 60).toInt(), (secs % 60).toInt(), 'Z', 0, 0)
    }

    /** [date] as an XMP date, ISO 8601 in UTC. */
    fun xmpDate(date: PdfDate): String {
        fun two(v: Int) = v.toString().padStart(2, '0')
        return "${date.year.toString().padStart(4, '0')}-${two(date.month)}-${two(date.day)}T${two(date.hour)}:${two(date.minute)}:${two(date.second)}Z"
    }

    /**
     * [xml] with the properties that [setInfo][PdfEditor.setInfo] was given, null for the ones it
     * keeps, and the two dates. A property that the packet holds as an element or as an attribute of
     * its description is rewritten in place; one it does not hold is added to the first description.
     */
    fun update(
        xml: String,
        title: String?, author: String?, subject: String?, keywords: String?, creator: String?, producer: String?,
        date: PdfDate,
    ): String {
        var out = xml
        fun langAlt(v: String) = "<rdf:Alt><rdf:li xml:lang=\"x-default\">${escape(v)}</rdf:li></rdf:Alt>"
        title?.let { out = set(out, "dc:title", langAlt(it), null) }
        author?.let { out = set(out, "dc:creator", "<rdf:Seq><rdf:li>${escape(it)}</rdf:li></rdf:Seq>", null) }
        subject?.let { out = set(out, "dc:description", langAlt(it), null) }
        keywords?.let { out = set(out, "pdf:Keywords", escape(it), it) }
        creator?.let { out = set(out, "xmp:CreatorTool", escape(it), it) }
        producer?.let { out = set(out, "pdf:Producer", escape(it), it) }
        val stamp = xmpDate(date)
        out = set(out, "xmp:ModifyDate", stamp, stamp)
        out = set(out, "xmp:MetadataDate", stamp, stamp)
        return out
    }

    /** [xml] with property [name] set to the element content [content], or to the attribute [attribute] where it is one. */
    private fun set(xml: String, name: String, content: String, attribute: String?): String {
        // [\s\S] spans lines on every platform; DOT_MATCHES_ALL is the JVM's alone.
        val element = Regex("<$name(\\s[^>]*)?>[\\s\\S]*?</$name>")
        element.find(xml)?.let { m ->
            val open = "<$name${m.groupValues[1]}>"
            return xml.replaceRange(m.range, "$open$content</$name>")
        }
        Regex("<$name(\\s[^>]*)?/>").find(xml)?.let { m -> return xml.replaceRange(m.range, "<$name${m.groupValues[1]}>$content</$name>") }
        if (attribute != null) {
            val attr = Regex("$name\\s*=\\s*(\"[^\"]*\"|'[^']*')")
            attr.find(xml)?.let { m -> return xml.replaceRange(m.range, "$name=\"${escape(attribute)}\"") }
        }
        // Not in the packet: a new element in the first description, whose namespace the packet
        // may not declare, so the element declares it itself.
        val ns = NAMESPACES[name.substringBefore(':')] ?: return xml
        val desc = Regex("<rdf:Description(\\s[^>]*)?>").find(xml) ?: return xml
        val prefix = name.substringBefore(':')
        val added = "<$name xmlns:$prefix=\"$ns\">$content</$name>"
        if (desc.value.endsWith("/>")) {
            val opened = desc.value.removeSuffix("/>") + ">" + added + "</rdf:Description>"
            return xml.replaceRange(desc.range, opened)
        }
        return xml.replaceRange(desc.range.last + 1, desc.range.last + 1, added)
    }

    private val NAMESPACES = mapOf(
        "dc" to "http://purl.org/dc/elements/1.1/",
        "pdf" to "http://ns.adobe.com/pdf/1.3/",
        "xmp" to "http://ns.adobe.com/xap/1.0/",
    )

    private fun escape(v: String): String =
        v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
