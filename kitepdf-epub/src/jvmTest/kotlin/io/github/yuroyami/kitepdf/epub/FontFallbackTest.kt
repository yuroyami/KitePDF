package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.render.RecordingCanvas
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * T-61: a codepoint missing from the matched face's cmap must never paint
 * `.notdef` tofu. Fallback order: another registered face carrying the glyph,
 * else the generic system-font path (face null). Uses the in-repo NotoSans
 * (Latin, no CJK) and DroidSansFallback (CJK) as a real missing/present pair;
 * skips when the reference checkout is absent.
 */
class FontFallbackTest {

    private val han = 0x4E2D // 中

    private fun repoFont(rel: String): ByteArray? {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, rel).exists()) d = d.parentFile
        return d?.let { File(it, rel) }?.takeIf { it.exists() }?.readBytes()
    }

    private fun latinFace(): EmbeddedFace? =
        repoFont("mupdf-master/resources/fonts/noto/NotoSans-Regular.otf")
            ?.let { FontRegistry.face("main", bold = false, italic = false, it) }
            ?.takeIf { it.gidFor(han) == 0 } // precondition: really lacks CJK

    private fun cjkFace(): EmbeddedFace? =
        repoFont("mupdf-master/resources/fonts/droid/DroidSansFallback.ttf")
            ?.let { FontRegistry.face("cjk", bold = false, italic = false, it) }
            ?.takeIf { it.gidFor(han) != 0 } // precondition: really has CJK

    private fun glyphRuns(doc: EpubDocument): List<RecordingCanvas.Call.Glyphs> =
        doc.pages.flatMap { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        }

    @Test
    fun fallback_for_prefers_a_face_that_has_the_glyph() {
        val latin = latinFace() ?: return
        val cjk = cjkFace() ?: return
        val registry = FontRegistry(listOf(latin, cjk))
        assertSame(cjk, registry.fallbackFor(han, bold = false, italic = false))
        // A codepoint neither face carries falls through to null (generic path).
        val absentEverywhere = listOf(0x05D0, 0x0E01, 0x10A0, 0x1B05)
            .firstOrNull { latin.gidFor(it) == 0 && cjk.gidFor(it) == 0 }
        if (absentEverywhere != null) {
            assertNull(registry.fallbackFor(absentEverywhere, bold = false, italic = false))
        }
    }

    @Test
    fun missing_glyph_falls_back_to_another_registered_face() {
        val latin = repoFont("mupdf-master/resources/fonts/noto/NotoSans-Regular.otf") ?: return
        val cjk = repoFont("mupdf-master/resources/fonts/droid/DroidSansFallback.ttf") ?: return
        if (latinFace() == null || cjkFace() == null) return
        val css = "@font-face{font-family:'main';src:url(main.otf)}" +
            "@font-face{font-family:'cjk';src:url(cjk.ttf)}" +
            "p{font-family:'main'}"
        val doc = EpubDocument.open(
            EpubFixtures.epub(
                "<body><style>$css</style><p>a中b</p></body>",
                listOf("OEBPS/main.otf" to latin, "OEBPS/cjk.ttf" to cjk),
            ),
        )
        val runs = glyphRuns(doc)
        val hanRun = runs.firstOrNull { r -> r.glyphs.any { it.text == "中" } }
            ?: error("the CJK char was not drawn at all")
        assertTrue(hanRun.hasOutlines, "the CJK char should ride the fallback face's real outlines")
        assertTrue(hanRun.glyphs.none { it.gid == 0 }, "no .notdef in the fallback run")
        // The Latin neighbours still use the matched face.
        assertTrue(runs.any { r -> r.hasOutlines && r.glyphs.any { it.text == "a" } && r.glyphs.none { it.gid == 0 } })
    }

    @Test
    fun missing_glyph_without_any_fallback_face_uses_the_generic_path() {
        val latin = repoFont("mupdf-master/resources/fonts/noto/NotoSans-Regular.otf") ?: return
        if (latinFace() == null) return
        val css = "@font-face{font-family:'main';src:url(main.otf)}p{font-family:'main'}"
        val doc = EpubDocument.open(
            EpubFixtures.epub(
                "<body><style>$css</style><p>a中b</p></body>",
                listOf("OEBPS/main.otf" to latin),
            ),
        )
        val runs = glyphRuns(doc)
        val hanRun = runs.firstOrNull { r -> r.glyphs.any { it.text == "中" } }
            ?: error("the CJK char was not drawn at all")
        assertTrue(!hanRun.hasOutlines, "no face has the glyph: the generic system-font path draws it")
        assertEquals(-1, hanRun.glyphs.first { it.text == "中" }.gid, "generic cells carry gid -1, never 0")
    }

    /** Corpus gate: with fallback in place, no embedded-outline run draws `.notdef`. */
    @Test
    fun real_book_draws_no_notdef_with_outlines() {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
        val book = d?.let { File(it, "corpus/epub") }?.listFiles { f -> f.extension == "epub" }
            ?.minByOrNull { it.length() } ?: return // corpus not present: skip
        val doc = EpubDocument.open(book.readBytes())
        for (page in doc.pages) {
            val calls = RecordingCanvas().also { page.renderTo(it) }.calls
            for (run in calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()) {
                if (run.hasOutlines) {
                    assertTrue(
                        run.glyphs.none { it.gid == 0 },
                        "page: .notdef drawn with outlines for text '${run.text}'",
                    )
                }
            }
        }
    }
}
