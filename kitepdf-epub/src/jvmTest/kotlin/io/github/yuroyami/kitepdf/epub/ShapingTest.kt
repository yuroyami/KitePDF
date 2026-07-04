package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.render.RecordingCanvas
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GSUB shaping — Arabic contextual joining ([ArabicJoining] + the GSUB `init`/
 * `medi`/`fina` single substitutions) wired through [BoxLayout]. The pure joining
 * algorithm is asserted directly; the end-to-end path is checked against the
 * in-repo `NotoNaskhArabic-Regular.otf` (verify: the medial glyph of a mid-word
 * letter is substituted in, and its isolated form does NOT appear).
 */
class ShapingTest {

    private fun naskh(): ByteArray? {
        val rel = "mupdf-master/resources/fonts/noto/NotoNaskhArabic-Regular.otf"
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, rel).exists()) d = d.parentFile
        return d?.let { File(it, rel) }?.takeIf { it.exists() }?.readBytes()
    }

    @Test
    fun arabic_joining_forms_are_computed() {
        // beh(dual) + hah(dual) + reh(right): initial, medial, final.
        val forms = ArabicJoining.forms(intArrayOf(0x0628, 0x062D, 0x0631))
        assertEquals(ArabicJoining.Form.INIT, forms[0])
        assertEquals(ArabicJoining.Form.MEDI, forms[1])
        assertEquals(ArabicJoining.Form.FINA, forms[2])
        // A lone letter is isolated; a right-joining letter never takes an initial form.
        assertEquals(ArabicJoining.Form.ISOL, ArabicJoining.forms(intArrayOf(0x0628))[0])
        // reh(R) + reh(R): the first can't join left, so both stay isolated except the
        // second takes final (joins to the previous reh which... reh can't join left) -> both ISOL.
        val rr = ArabicJoining.forms(intArrayOf(0x0631, 0x0631))
        assertEquals(ArabicJoining.Form.ISOL, rr[0])
    }

    @Test
    fun arabic_word_is_shaped_through_epub() {
        val otf = naskh() ?: return
        val face = FontRegistry.face("ar", bold = false, italic = false, otf) ?: return
        assertTrue(face.hasArabicJoining, "NotoNaskhArabic exposes GSUB joining features")

        val isolHah = face.gidFor(0x062D)
        val mediHah = face.substSingle("medi", isolHah)
        assertTrue(mediHah != isolHah, "the font provides a distinct medial form for hah")

        val text = "بحر" // بحر — beh, hah, reh
        val css = "@font-face{font-family:'AR';src:url(f.otf)}p{font-family:'AR'}"
        val doc = EpubDocument.open(
            EpubFixtures.epub("<body><style>$css</style><p>$text</p></body>", listOf("OEBPS/f.otf" to otf)),
        ) ?: return
        val gids = doc.pages.flatMap { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls
                .filterIsInstance<RecordingCanvas.Call.Glyphs>()
        }.flatMap { it.glyphs }.map { it.gid }.toSet()

        assertTrue(mediHah in gids, "the medial hah glyph is rendered (contextual joining applied)")
        assertTrue(isolHah !in gids, "the isolated hah glyph is not used mid-word")
    }

    @Test
    fun gpos_mark_to_base_offset_is_parsed() {
        val otf = naskh() ?: return
        val face = FontRegistry.face("ar", bold = false, italic = false, otf) ?: return
        // Some Arabic letter + harakat pair must have a GPOS mark-to-base anchor.
        var found = false
        val marks = 0x064B..0x0652 // fathatan..sukun
        outer@ for (l in 0x0620..0x064A) {
            val base = face.gidFor(l); if (base <= 0) continue
            val shaped = intArrayOf(base, face.substSingle("isol", base), face.substSingle("init", base))
            for (m in marks) {
                val mg = face.gidFor(m); if (mg <= 0) continue
                if (shaped.any { face.markOffset(it, mg) != null }) { found = true; break@outer }
            }
        }
        assertTrue(found, "NotoNaskhArabic exposes GPOS mark-to-base attachment")
    }

    @Test
    fun arabic_marks_are_positioned_through_epub() {
        val otf = naskh() ?: return
        // Letters carrying fatha (U+064E) marks; at least one mark must be GPOS-offset.
        val text = "بَحَرَ"
        val css = "@font-face{font-family:'AR';src:url(f.otf)}p{font-family:'AR'}"
        val doc = EpubDocument.open(
            EpubFixtures.epub("<body><style>$css</style><p>$text</p></body>", listOf("OEBPS/f.otf" to otf)),
        ) ?: return
        val glyphs = doc.pages.flatMap { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls
                .filterIsInstance<RecordingCanvas.Call.Glyphs>()
        }.flatMap { it.glyphs }
        assertTrue(
            glyphs.any { it.xOffset != 0.0 || it.yOffset != 0.0 },
            "a combining mark was positioned by GPOS mark-to-base",
        )
    }
}
