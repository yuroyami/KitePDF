package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.epub.ArabicJoining.Form
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * GSUB shaping: Arabic contextual joining ([ArabicJoining] + the GSUB `init`/
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
    fun hamza_joins_neither_side_and_a_format_character_is_transparent() {
        // شيء: the yeh is final, because hamza joins neither side (#315).
        assertEquals(listOf(Form.INIT, Form.FINA, null), ArabicJoining.forms(intArrayOf(0x0634, 0x064A, 0x0621)).toList())
        // Two behs join across a right-to-left mark.
        assertEquals(listOf(Form.INIT, null, Form.FINA), ArabicJoining.forms(intArrayOf(0x0628, 0x200F, 0x0628)).toList())
    }

    @Test
    fun syriac_alaph_takes_the_forms_of_its_joining_group() {
        assertEquals(listOf(Form.INIT, Form.FINA), ArabicJoining.forms(intArrayOf(0x0712, 0x0710)).toList())
        // An Alaph inside a word takes med2, after Dalath fin3, and after another Alaph fin2.
        assertEquals(listOf(Form.INIT, Form.MED2, Form.ISOL), ArabicJoining.forms(intArrayOf(0x0712, 0x0710, 0x0712)).toList())
        assertEquals(listOf(Form.ISOL, Form.FIN3), ArabicJoining.forms(intArrayOf(0x0715, 0x0710)).toList())
        assertEquals(listOf(Form.ISOL, Form.FIN2), ArabicJoining.forms(intArrayOf(0x0710, 0x0710)).toList())
    }

    @Test
    fun marks_sort_by_class_and_modifier_marks_go_first() {
        // Small high seen, a modifier mark of class 230, goes before fathatan in Arabic (#318).
        val arabic = Normalizer.normalize(intArrayOf(0x0630, 0x064B, 0x06DC), intArrayOf(0, 1, 2), { true }, arabicMarks = true)
        assertEquals(listOf(0x0630, 0x06DC, 0x064B), arabic.codePoints.toList())
        // Superscript Alaph, class 36, goes before a zqapha below of class 220.
        val syriac = Normalizer.normalize(intArrayOf(0x0724, 0x0734, 0x0711), intArrayOf(0, 1, 2), { true }, arabicMarks = true)
        assertEquals(listOf(0x0724, 0x0711, 0x0734), syriac.codePoints.toList())
    }

    @Test
    fun arabic_word_is_shaped_through_epub() {
        val otf = naskh().orSkip("NotoNaskhArabic-Regular.otf")
        val face = assertNotNull(FontRegistry.face("ar", bold = false, italic = false, otf), "NotoNaskhArabic parses")
        assertTrue(face.hasArabicJoining, "NotoNaskhArabic exposes GSUB joining features")

        val isolHah = face.gidFor(0x062D)
        val mediHah = face.substSingle("medi", isolHah)
        assertTrue(mediHah != isolHah, "the font provides a distinct medial form for hah")

        val text = "بحر" // بحر: beh, hah, reh
        val css = "@font-face{font-family:'AR';src:url(f.otf)}p{font-family:'AR'}"
        val doc = EpubDocument.open(
            EpubFixtures.epub("<body><style>$css</style><p>$text</p></body>", listOf("OEBPS/f.otf" to otf)),
        )
        val gids = doc.pages.flatMap { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls
                .filterIsInstance<RecordingCanvas.Call.Glyphs>()
        }.flatMap { it.glyphs }.map { it.gid }.toSet()

        assertTrue(mediHah in gids, "the medial hah glyph is rendered (contextual joining applied)")
        assertTrue(isolHah !in gids, "the isolated hah glyph is not used mid-word")
    }

    @Test
    fun gpos_mark_to_base_offset_is_parsed() {
        val otf = naskh().orSkip("NotoNaskhArabic-Regular.otf")
        val face = assertNotNull(FontRegistry.face("ar", bold = false, italic = false, otf), "NotoNaskhArabic parses")
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
        val otf = naskh().orSkip("NotoNaskhArabic-Regular.otf")
        // Letters carrying fatha (U+064E) marks; at least one mark must be GPOS-offset.
        val text = "بَحَرَ"
        val css = "@font-face{font-family:'AR';src:url(f.otf)}p{font-family:'AR'}"
        val doc = EpubDocument.open(
            EpubFixtures.epub("<body><style>$css</style><p>$text</p></body>", listOf("OEBPS/f.otf" to otf)),
        )
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
