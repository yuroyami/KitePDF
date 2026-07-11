package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-47 acceptance: WOFF2 fonts ([Woff2.toSfnt] + the `FontRegistry.face`
 * unwrap). No `.woff2` ships in the repo or corpus, so the test compresses
 * the in-repo DroidSansFallback TTF with the reference `woff2_compress`
 * tool (Google's encoder: brotli stream + transformed glyf/loca + optional
 * hmtx transform) and asserts our decoder reverses all of it: same cmap,
 * same advances, real outlines, end-to-end through an EPUB `@font-face`.
 *
 * Skipped silently when the TTF or the encoder binary is unavailable
 * (matches WoffTest; CI has neither).
 */
class Woff2Test {

    private fun droidSans(): File? {
        val rel = "mupdf-master/resources/fonts/droid/DroidSansFallback.ttf"
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, rel).exists()) d = d.parentFile
        return d?.let { File(it, rel) }?.takeIf { it.exists() }
    }

    /** Compress [ttf] with the reference encoder, or null when it's not installed. */
    private fun woff2Compress(ttf: File): ByteArray? {
        val dir = File(System.getProperty("java.io.tmpdir"), "kitepdf-woff2-test").apply { mkdirs() }
        val src = File(dir, "font.ttf")
        ttf.copyTo(src, overwrite = true)
        val out = File(dir, "font.woff2")
        out.delete()
        return runCatching {
            val p = ProcessBuilder("woff2_compress", src.absolutePath)
                .redirectErrorStream(true)
                .start()
            p.inputStream.readBytes()
            check(p.waitFor() == 0)
            out.readBytes()
        }.getOrNull()
    }

    @Test
    fun woff2_round_trips_cmap_advances_and_outlines() {
        val ttf = droidSans()?.readBytes() ?: return
        val woff2 = woff2Compress(droidSans()!!) ?: return
        assertTrue(Woff2.isWoff2(woff2), "encoder output is recognised as WOFF2")

        val fromTtf = FontRegistry.face("f", bold = false, italic = false, ttf)
        val fromWoff2 = FontRegistry.face("f", bold = false, italic = false, woff2)
        assertNotNull(fromTtf, "raw TTF parses")
        assertNotNull(fromWoff2, "WOFF2 face registers")
        assertEquals(fromTtf!!.unitsPerEm, fromWoff2!!.unitsPerEm, "unitsPerEm survives")

        for (ch in listOf('A', 'g', '中', '文', 'あ')) {
            val gidT = fromTtf.gidFor(ch.code)
            val gidW = fromWoff2.gidFor(ch.code)
            assertEquals(gidT, gidW, "cmap for '$ch' survives the WOFF2 round-trip")
            assertTrue(gidW > 0, "'$ch' maps to a real glyph")
            assertNotNull(fromWoff2.outline(gidW), "'$ch' has a reconstructed outline")
            assertEquals(
                fromTtf.advance1000(gidT), fromWoff2.advance1000(gidW),
                "advance for '$ch' survives the hmtx reconstruction",
            )
        }
    }

    @Test
    fun woff2_font_face_renders_outlines_end_to_end() {
        val ttfFile = droidSans() ?: return
        val woff2 = woff2Compress(ttfFile) ?: return

        val css = "@font-face{font-family:'W2';src:url(font.woff2)}p{font-family:'W2'}"
        val doc = EpubDocument.open(
            EpubFixtures.epub("<body><style>$css</style><p>中文字とテスト</p></body>", listOf("OEBPS/font.woff2" to woff2)),
        )
        assertNotNull(doc, "fixture opens")
        val runs = doc.pages.flatMap { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        }
        assertTrue(runs.isNotEmpty(), "the page draws text")
        assertTrue(
            runs.any { r -> r.hasOutlines && r.glyphs.any { it.outline != null } },
            "WOFF2 @font-face draws real embedded outlines through the EPUB path",
        )
    }
}
