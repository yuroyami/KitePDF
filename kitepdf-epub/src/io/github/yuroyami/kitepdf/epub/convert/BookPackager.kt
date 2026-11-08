package io.github.yuroyami.kitepdf.epub.convert

import io.github.yuroyami.kitepdf.core.zip.Crc32

/**
 * Builds an EPUB 3 package in memory from chapters and resources, so a format that is one file of
 * text or markup (plain text, HTML, FictionBook) opens through the EPUB engine and gets its layout,
 * search, selection and bookmarks (#946, #947). Entries are stored, not compressed.
 */
internal class BookPackager(private val title: String, private val language: String = "en") {

    /** One chapter: its file name in the package, its XHTML or HTML, and its media type. */
    class Chapter(val file: String, val markup: String, val mediaType: String = "application/xhtml+xml")

    /** One table of contents entry and the entries under it. */
    class NavEntry(val label: String, val href: String, val children: List<NavEntry> = emptyList())

    var authors: List<String> = emptyList()
    var description: String? = null
    var coverImage: String? = null
    private val chapters = ArrayList<Chapter>()
    private val resources = ArrayList<Triple<String, ByteArray, String>>()
    private val nav = ArrayList<NavEntry>()

    fun chapter(c: Chapter) { chapters += c }
    fun resource(path: String, bytes: ByteArray, mediaType: String) { if (resources.none { it.first == path }) resources += Triple(path, bytes, mediaType) }
    fun nav(entry: NavEntry) { nav += entry }

    fun build(): ByteArray {
        val manifest = StringBuilder()
        chapters.forEachIndexed { i, c -> manifest.append("""<item id="c$i" href="${attr(c.file)}" media-type="${c.mediaType}"/>""") }
        resources.forEachIndexed { i, r ->
            val props = if (r.first == coverImage) """ properties="cover-image"""" else ""
            manifest.append("""<item id="r$i" href="${attr(r.first)}" media-type="${r.third}"$props/>""")
        }
        manifest.append("""<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
        val spine = chapters.indices.joinToString("") { """<itemref idref="c$it"/>""" }
        val creators = authors.joinToString("") { "<dc:creator>${text(it)}</dc:creator>" }
        val desc = description?.let { "<dc:description>${text(it)}</dc:description>" } ?: ""
        val opf = """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">""" +
            """<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:kitepdf:converted</dc:identifier>""" +
            """<dc:title>${text(title)}</dc:title><dc:language>${text(language)}</dc:language>$creators$desc</metadata>""" +
            """<manifest>$manifest</manifest><spine>$spine</spine></package>"""
        fun list(entries: List<NavEntry>): String = if (entries.isEmpty()) "" else
            "<ol>" + entries.joinToString("") { """<li><a href="${attr(it.href)}">${text(it.label)}</a>${list(it.children)}</li>""" } + "</ol>"
        val navDoc = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">""" +
            """<head><title>${text(title)}</title></head><body><nav epub:type="toc">${list(nav.ifEmpty { chapters.map { NavEntry(it.file, it.file) } })}</nav></body></html>"""
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""" +
            """<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val entries = ArrayList<Pair<String, ByteArray>>()
        entries += "mimetype" to "application/epub+zip".encodeToByteArray()
        entries += "META-INF/container.xml" to container.encodeToByteArray()
        entries += "OEBPS/content.opf" to opf.encodeToByteArray()
        entries += "OEBPS/nav.xhtml" to navDoc.encodeToByteArray()
        for (c in chapters) entries += "OEBPS/${c.file}" to c.markup.encodeToByteArray()
        for (r in resources) entries += "OEBPS/${r.first}" to r.second
        return storedZip(entries)
    }

    companion object {
        /** [s] as XML character data. */
        fun text(s: String): String = buildString(s.length) {
            for (c in s) when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(c)
            }
        }

        /** [s] as an XML attribute value in double quotes. */
        fun attr(s: String): String = text(s).replace("\"", "&quot;")

        /** A ZIP of stored entries, with their CRCs, that the EPUB reader reads. */
        fun storedZip(entries: List<Pair<String, ByteArray>>): ByteArray {
            val out = io.github.yuroyami.kitepdf.core.ByteArrayBuilder()
            fun u16(v: Int) { out.append((v and 0xFF).toByte()); out.append(((v ushr 8) and 0xFF).toByte()) }
            fun u32(v: Long) { for (s in 0 until 32 step 8) out.append(((v ushr s) and 0xFF).toByte()) }
            class Cd(val name: ByteArray, val offset: Int, val size: Int, val crc: Long)
            val cds = ArrayList<Cd>()
            for ((name, data) in entries) {
                val nb = name.encodeToByteArray()
                val offset = out.size()
                val crc = Crc32.of(data)
                // Bit 11 of the flags: the name is UTF-8.
                u32(0x04034b50L); u16(20); u16(0x0800); u16(0); u16(0); u16(0)
                u32(crc); u32(data.size.toLong()); u32(data.size.toLong())
                u16(nb.size); u16(0)
                out.append(nb); out.append(data)
                cds += Cd(nb, offset, data.size, crc)
            }
            val cdStart = out.size()
            for (cd in cds) {
                u32(0x02014b50L); u16(20); u16(20); u16(0x0800); u16(0)
                u16(0); u16(0); u32(cd.crc)
                u32(cd.size.toLong()); u32(cd.size.toLong())
                u16(cd.name.size); u16(0); u16(0)
                u16(0); u16(0); u32(0L)
                u32(cd.offset.toLong())
                out.append(cd.name)
            }
            val cdSize = out.size() - cdStart
            u32(0x06054b50L); u16(0); u16(0)
            u16(cds.size); u16(cds.size)
            u32(cdSize.toLong()); u32(cdStart.toLong()); u16(0)
            return out.toByteArray()
        }
    }
}
