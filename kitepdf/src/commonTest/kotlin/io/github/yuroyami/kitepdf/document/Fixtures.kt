package io.github.yuroyami.kitepdf.document

import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont

/** A two-page PDF, built by the writer so the test owns every byte. */
internal fun samplePdf(): ByteArray = PdfBuilder()
    .page { text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "page one") }
    .page { text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "page two") }
    .build()

/** A minimal but conformant EPUB 3: mimetype first and stored, OCF container, one spine item. */
internal fun sampleEpub(mimetypeFirst: Boolean = true): ByteArray {
    val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
    val opf = """<?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:identifier id="id">urn:uuid:kite-test</dc:identifier>
            <dc:title>Sniffer Fixture</dc:title>
          </metadata>
          <manifest><item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
          <spine><itemref idref="c1"/></spine>
        </package>"""
    val ch1 = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body><p>hello from the fixture</p></body></html>"""
    val mimetype = "mimetype" to "application/epub+zip".encodeToByteArray()
    val rest = listOf(
        "META-INF/container.xml" to container.encodeToByteArray(),
        "OEBPS/content.opf" to opf.encodeToByteArray(),
        "OEBPS/ch1.xhtml" to ch1.encodeToByteArray(),
    )
    return storedZip(if (mimetypeFirst) listOf(mimetype) + rest else rest + listOf(mimetype))
}

/**
 * Pure-Kotlin STORED-only ZIP writer, so the fixtures build on every target
 * (commonTest runs on native and JS too, where java.util.zip does not exist).
 */
internal fun storedZip(entries: List<Pair<String, ByteArray>>): ByteArray {
    val out = ArrayList<Byte>()
    fun u16(v: Int) { out.add((v and 0xFF).toByte()); out.add(((v shr 8) and 0xFF).toByte()) }
    fun u32(v: Int) { u16(v and 0xFFFF); u16((v ushr 16) and 0xFFFF) }
    fun raw(b: ByteArray) { for (x in b) out.add(x) }

    val offsets = ArrayList<Int>()
    val crcs = ArrayList<Int>()
    for ((name, data) in entries) {
        offsets.add(out.size)
        val crc = crc32(data)
        crcs.add(crc)
        u32(0x04034B50); u16(20); u16(0); u16(0); u16(0); u16(0)
        u32(crc); u32(data.size); u32(data.size)
        val nameBytes = name.encodeToByteArray()
        u16(nameBytes.size); u16(0)
        raw(nameBytes); raw(data)
    }
    val cdStart = out.size
    entries.forEachIndexed { i, (name, data) ->
        u32(0x02014B50); u16(20); u16(20); u16(0); u16(0); u16(0); u16(0)
        u32(crcs[i]); u32(data.size); u32(data.size)
        val nameBytes = name.encodeToByteArray()
        u16(nameBytes.size); u16(0); u16(0); u16(0); u16(0); u32(0); u32(offsets[i])
        raw(nameBytes)
    }
    val cdSize = out.size - cdStart
    u32(0x06054B50); u16(0); u16(0); u16(entries.size); u16(entries.size)
    u32(cdSize); u32(cdStart); u16(0)
    return out.toByteArray()
}

private fun crc32(data: ByteArray): Int {
    var crc = 0.inv()
    for (b in data) {
        crc = crc xor (b.toInt() and 0xFF)
        repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1 }
    }
    return crc.inv()
}
