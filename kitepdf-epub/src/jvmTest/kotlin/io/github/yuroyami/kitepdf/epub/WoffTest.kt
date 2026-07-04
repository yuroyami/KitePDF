package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.compression.Zlib
import io.github.yuroyami.kitepdf.render.RecordingCanvas
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * WOFF 1.0 support ([Woff.toSfnt] + the `FontRegistry.face` unwrap). No `.woff`
 * font ships in the repo, so the test WOFF-encodes the in-repo DroidSans TTF
 * (compressing each table with the core [Zlib]) and asserts the decoder round-trips
 * it back to byte-identical SFNT tables and renders real outlines end-to-end.
 */
class WoffTest {

    private fun droidSans(): ByteArray? {
        val rel = "mupdf-master/resources/fonts/droid/DroidSansFallback.ttf"
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, rel).exists()) d = d.parentFile
        return d?.let { File(it, rel) }?.takeIf { it.exists() }?.readBytes()
    }

    // ---- a minimal, spec-correct WOFF 1.0 encoder (test-only) ----------------

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
    private fun putU16(b: ByteArray, o: Int, v: Int) { b[o] = ((v ushr 8) and 0xFF).toByte(); b[o + 1] = (v and 0xFF).toByte() }
    private fun putU32(b: ByteArray, o: Int, v: Long) {
        b[o] = ((v ushr 24) and 0xFF).toByte(); b[o + 1] = ((v ushr 16) and 0xFF).toByte()
        b[o + 2] = ((v ushr 8) and 0xFF).toByte(); b[o + 3] = (v and 0xFF).toByte()
    }
    private fun align4(x: Int) = (x + 3) and 3.inv()

    private class Tbl(val tag: Long, val checksum: Long, val data: ByteArray)

    private fun sfntTables(sfnt: ByteArray): List<Tbl> {
        val n = u16(sfnt, 4)
        return (0 until n).map { i ->
            val d = 12 + i * 16
            val off = u32(sfnt, d + 8).toInt(); val len = u32(sfnt, d + 12).toInt()
            Tbl(u32(sfnt, d), u32(sfnt, d + 4), sfnt.copyOfRange(off, off + len))
        }
    }

    private fun toWoff(sfnt: ByteArray): ByteArray {
        val tables = sfntTables(sfnt)
        val n = tables.size
        val comps = tables.map { t ->
            val z = Zlib.encode(t.data)
            if (z.size < t.data.size) z else t.data // WOFF stores when compression doesn't help
        }
        val dirEnd = 44 + n * 20
        val offsets = IntArray(n)
        var pos = dirEnd
        for (i in 0 until n) { offsets[i] = pos; pos += align4(comps[i].size) }
        val out = ByteArray(pos)
        out[0] = 'w'.code.toByte(); out[1] = 'O'.code.toByte(); out[2] = 'F'.code.toByte(); out[3] = 'F'.code.toByte()
        putU32(out, 4, u32(sfnt, 0)) // flavor = sfnt scalerType
        putU32(out, 8, pos.toLong())
        putU16(out, 12, n)
        putU32(out, 16, sfnt.size.toLong()) // totalSfntSize (informational)
        for (i in 0 until n) {
            val d = 44 + i * 20
            putU32(out, d, tables[i].tag)
            putU32(out, d + 4, offsets[i].toLong())
            putU32(out, d + 8, comps[i].size.toLong()) // compLength
            putU32(out, d + 12, tables[i].data.size.toLong()) // origLength
            putU32(out, d + 16, tables[i].checksum)
            comps[i].copyInto(out, offsets[i])
        }
        return out
    }

    @Test
    fun woff_round_trips_to_byte_identical_sfnt_tables() {
        val ttf = droidSans() ?: return
        val woff = toWoff(ttf)
        assertTrue(Woff.isWoff(woff), "encoded bytes are recognised as WOFF")
        val sfnt = Woff.toSfnt(woff)
        assertNotNull(sfnt, "WOFF decodes to an SFNT")

        val orig = sfntTables(ttf).associate { it.tag to it.data }
        val round = sfntTables(sfnt).associate { it.tag to it.data }
        assertEquals(orig.keys, round.keys, "same set of tables survive the round-trip")
        for ((tag, bytes) in orig) {
            assertTrue(bytes.contentEquals(round[tag]), "table 0x${tag.toString(16)} round-trips byte-identical")
        }
    }

    @Test
    fun woff_font_face_renders_outlines() {
        val ttf = droidSans() ?: return
        val woff = toWoff(ttf)
        // The face built from WOFF must render the same outlines as the raw TTF.
        val fromTtf = FontRegistry.face("f", bold = false, italic = false, ttf)
        val fromWoff = FontRegistry.face("f", bold = false, italic = false, woff)
        assertNotNull(fromWoff, "WOFF face parses")
        val gid = fromWoff!!.gidFor('中'.code)
        assertTrue(gid > 0, "cmap survives the WOFF round-trip")
        assertNotNull(fromWoff.outline(gid), "WOFF face yields a glyph outline")
        assertEquals(fromTtf!!.advance1000(gid), fromWoff.advance1000(gid), "advances match the raw TTF")

        // Full EPUB @font-face path with the WOFF file.
        val css = "@font-face{font-family:'W';src:url(font.woff)}p{font-family:'W'}"
        val doc = EpubDocument.open(
            EpubFixtures.epub("<body><style>$css</style><p>中文字</p></body>", listOf("OEBPS/font.woff" to woff)),
        )
        assertNotNull(doc)
        val runs = doc!!.pages.flatMap { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        }
        assertTrue(
            runs.any { r -> r.hasOutlines && r.glyphs.any { it.outline != null } },
            "WOFF @font-face draws real outlines through the EPUB path",
        )
    }
}
