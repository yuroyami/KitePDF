package io.github.yuroyami.kitepdf.net

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.zip.Crc32
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel

/** The documents and the client the remote loading tests serve. */
internal object NetFixtures {

    fun clientServing(bytes: ByteArray) = HttpClient(
        MockEngine { respond(ByteReadChannel(bytes), HttpStatusCode.OK, headersOf()) },
    )

    /** A 2x1 24-bit BMP: a red pixel and a blue one. */
    fun bmp2x1(): ByteArray {
        val h = ByteArray(54)
        h[0] = 'B'.code.toByte(); h[1] = 'M'.code.toByte()
        fun le32(o: Int, v: Int) { var s = 0; var i = o; while (s < 32) { h[i++] = ((v ushr s) and 0xFF).toByte(); s += 8 } }
        fun le16(o: Int, v: Int) { h[o] = (v and 0xFF).toByte(); h[o + 1] = ((v ushr 8) and 0xFF).toByte() }
        le32(2, 62); le32(10, 54); le32(14, 40); le32(18, 2); le32(22, 1)
        le16(26, 1); le16(28, 24); le32(34, 8)
        return h + byteArrayOf(0, 0, 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0)
    }

    fun samplePdf(): ByteArray = PdfBuilder()
        .page { text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "page one") }
        .page { text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "page two") }
        .build()

    fun sampleEpub(body: String = "<p>downloaded</p>"): ByteArray {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="id">urn:uuid:kite-net</dc:identifier>
                <dc:title>Remote Fixture</dc:title>
              </metadata>
              <manifest><item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="c1"/></spine>
            </package>"""
        val ch1 = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body>$body</body></html>"""
        return storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
                "OEBPS/ch1.xhtml" to ch1.encodeToByteArray(),
            ),
        )
    }

    /** A ZIP of [entries], stored without compression (APPNOTE 6.3.10, 4.3), as an EPUB's mimetype must be. */
    fun storedZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayBuilder(1024)
        val central = ByteArrayBuilder(256)
        fun ByteArrayBuilder.le16(v: Int) { append((v and 0xFF).toByte()); append(((v ushr 8) and 0xFF).toByte()) }
        fun ByteArrayBuilder.le32(v: Long) { le16((v and 0xFFFF).toInt()); le16(((v ushr 16) and 0xFFFF).toInt()) }
        for ((name, data) in entries) {
            val offset = out.size().toLong()
            val nameBytes = name.encodeToByteArray()
            val crc = Crc32.of(data)
            out.le32(0x04034b50); out.le16(10); out.le16(0); out.le16(0); out.le16(0); out.le16(0)
            out.le32(crc); out.le32(data.size.toLong()); out.le32(data.size.toLong())
            out.le16(nameBytes.size); out.le16(0); out.append(nameBytes); out.append(data)
            central.le32(0x02014b50); central.le16(20); central.le16(10); central.le16(0); central.le16(0); central.le16(0); central.le16(0)
            central.le32(crc); central.le32(data.size.toLong()); central.le32(data.size.toLong())
            central.le16(nameBytes.size); central.le16(0); central.le16(0); central.le16(0); central.le16(0); central.le32(0); central.le32(offset)
            central.append(nameBytes)
        }
        val centralAt = out.size().toLong()
        val dir = central.toByteArray()
        out.append(dir)
        out.le32(0x06054b50); out.le16(0); out.le16(0); out.le16(entries.size); out.le16(entries.size)
        out.le32(dir.size.toLong()); out.le32(centralAt); out.le16(0)
        return out.toByteArray()
    }
}
