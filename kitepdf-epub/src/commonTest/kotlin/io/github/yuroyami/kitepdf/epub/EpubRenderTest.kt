package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.render.Matrix
import io.github.yuroyami.kitepdf.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end proof that :kitepdf-epub is a real handler on :kitepdf-core: build
 * a minimal EPUB, open it, and render a page through the SAME [RecordingCanvas]
 * the PDF engine's render tests use. If text reaches drawGlyphs, the shared
 * substrate genuinely serves a second, non-PDF format.
 */
class EpubRenderTest {

    @Test
    fun opens_spine_and_renders_body_text_through_core_canvas() {
        val epub = buildMinimalEpub()

        val doc = EpubDocument.open(epub)
        assertNotNull(doc, "EPUB should open")
        assertTrue(doc.pageCount >= 1, "expected at least one reflowed page")

        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, Matrix.IDENTITY)

        val glyphRuns = canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        assertTrue(glyphRuns.isNotEmpty(), "page should emit drawGlyphs runs")

        // Every EPUB run uses the substitute-serif path (no embedded outlines).
        assertTrue(glyphRuns.all { !it.hasOutlines }, "EPUB body text uses the fallback font path")

        val rendered = glyphRuns.joinToString(" ") { it.text }
        assertTrue(rendered.contains("Hello"), "rendered text should carry the <h1>: <<$rendered>>")
        assertTrue(rendered.contains("kitepdf"), "rendered text should carry the <p>: <<$rendered>>")
    }

    @Test
    fun missing_container_returns_null() {
        // A zip with no META-INF/container.xml is not a readable EPUB.
        val notEpub = storedZip(listOf("random.txt" to "nothing here".encodeToByteArray()))
        assertTrue(EpubDocument.open(notEpub) == null)
    }

    // ---- fixtures -----------------------------------------------------------

    private fun buildMinimalEpub(): ByteArray {
        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()

        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <manifest>
                <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="c1"/>
              </spine>
            </package>
        """.trimIndent()

        val chapter = """
            <?xml version="1.0"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>Chapter</title></head>
              <body>
                <h1>Hello from the EPUB handler</h1>
                <p>This paragraph is laid out and painted through the shared kitepdf-core
                   Canvas, the same one the PDF engine uses. One engine, many formats.</p>
              </body>
            </html>
        """.trimIndent()

        return storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
                "OEBPS/chapter1.xhtml" to chapter.encodeToByteArray(),
            ),
        )
    }

    /** Build a STORED (uncompressed) zip. CRCs are left zero; [ZipReader] does not verify them. */
    private fun storedZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out.add((v and 0xFF).toByte()); out.add(((v ushr 8) and 0xFF).toByte()) }
        fun u32(v: Long) { var s = 0; while (s < 32) { out.add(((v ushr s) and 0xFF).toByte()); s += 8 } }
        fun raw(b: ByteArray) { for (x in b) out.add(x) }

        data class Cd(val name: ByteArray, val offset: Int, val size: Int)
        val cds = ArrayList<Cd>()

        for ((name, data) in entries) {
            val nb = name.encodeToByteArray()
            val offset = out.size
            u32(0x04034b50L); u16(20); u16(0); u16(0); u16(0); u16(0)  // sig ver flags method time date
            u32(0L); u32(data.size.toLong()); u32(data.size.toLong())  // crc csize usize
            u16(nb.size); u16(0)                                       // nameLen extraLen
            raw(nb); raw(data)
            cds.add(Cd(nb, offset, data.size))
        }

        val cdStart = out.size
        for (cd in cds) {
            u32(0x02014b50L); u16(20); u16(20); u16(0); u16(0)         // sig verMade verNeed flags method
            u16(0); u16(0); u32(0L)                                    // time date crc
            u32(cd.size.toLong()); u32(cd.size.toLong())              // csize usize
            u16(cd.name.size); u16(0); u16(0)                          // nameLen extraLen commentLen
            u16(0); u16(0); u32(0L)                                    // diskStart intAttr extAttr
            u32(cd.offset.toLong())                                    // local header offset
            raw(cd.name)
        }
        val cdSize = out.size - cdStart

        u32(0x06054b50L); u16(0); u16(0)                              // sig disk cdDisk
        u16(cds.size); u16(cds.size)                                  // entriesThisDisk entriesTotal
        u32(cdSize.toLong()); u32(cdStart.toLong()); u16(0)          // cdSize cdOffset commentLen
        return out.toByteArray()
    }
}
