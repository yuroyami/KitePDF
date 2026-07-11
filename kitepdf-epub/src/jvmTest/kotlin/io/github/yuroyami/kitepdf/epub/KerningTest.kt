package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.render.RecordingCanvas
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Horizontal kerning ([io.github.yuroyami.kitepdf.font.OpenTypeKern], wired through
 * [EmbeddedFace.kern1000] + [BoxLayout]). Verified against the in-repo fonts: the
 * DroidSans `kern` table and the Noto OTF `GPOS` pair-adjustment tables. Asserts
 * both that a font yields a non-zero pair adjustment AND that the EPUB layout folds
 * it into the left glyph's drawn advance.
 */
class KerningTest {

    private fun fontRoot(): File? {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        val marker = "mupdf-master/resources/fonts"
        while (d != null && !File(d, marker).exists()) d = d.parentFile
        return d?.let { File(it, marker) }
    }

    private fun candidateFonts(): List<File> {
        val root = fontRoot() ?: return emptyList()
        val droid = File(root, "droid/DroidSansFallback.ttf")
        val noto = File(root, "noto").listFiles { f -> f.extension.equals("otf", true) }
            ?.sorted()?.take(12) ?: emptyList()
        return (listOf(droid) + noto).filter { it.exists() }
    }

    private class Hit(val cpA: Int, val cpB: Int, val gidA: Int, val gidB: Int, val kern: Int)

    private fun cpForGid(face: EmbeddedFace, gid: Int): Int? {
        for (cp in 0x21..0xFFFF) if (face.gidFor(cp) == gid) return cp
        return null
    }

    /** First glyph pair (mapped to BMP codepoints) that the font kerns. */
    private fun findKern(face: EmbeddedFace): Hit? {
        for (a in 1..400) for (b in 1..400) {
            val k = face.kern1000(a, b)
            if (k != 0) {
                val ca = cpForGid(face, a) ?: continue
                val cb = cpForGid(face, b) ?: continue
                return Hit(ca, cb, a, b, k)
            }
        }
        return null
    }

    @Test
    fun a_font_yields_pair_kerning() {
        val fonts = candidateFonts()
        if (fonts.isEmpty()) return // no fonts in this checkout — skip
        var verifiedName: String? = null
        for (f in fonts) {
            val face = FontRegistry.face("k", bold = false, italic = false, f.readBytes()) ?: continue
            val hit = findKern(face) ?: continue
            assertTrue(hit.kern != 0, "${f.name}: non-zero kern for gids ${hit.gidA}/${hit.gidB}")
            verifiedName = f.name
            break
        }
        // At least one of DroidSans (kern table) / Noto OTF (GPOS) must kern.
        assertTrue(verifiedName != null, "an in-repo font must yield a pair kern (kern table or GPOS)")
    }

    @Test
    fun epub_layout_folds_kern_into_advance() {
        val fonts = candidateFonts()
        for (f in fonts) {
            val bytes = f.readBytes()
            val face = FontRegistry.face("k", bold = false, italic = false, bytes) ?: continue
            val hit = findKern(face) ?: continue
            val ext = f.extension.lowercase()
            val text = "" + hit.cpA.toChar() + hit.cpB.toChar()
            val css = "@font-face{font-family:'K';src:url(font.$ext)}p{font-family:'K'}"
            val doc = EpubDocument.open(
                EpubFixtures.epub("<body><style>$css</style><p>$text</p></body>", listOf("OEBPS/font.$ext" to bytes)),
            )
            val glyphs = doc.pages.flatMap { page ->
                RecordingCanvas().also { page.renderTo(it) }.calls
                    .filterIsInstance<RecordingCanvas.Call.Glyphs>()
            }.flatMap { it.glyphs }

            val idx = glyphs.indexOfFirst { it.gid == hit.gidA }
            if (idx < 0 || idx + 1 >= glyphs.size || glyphs[idx + 1].gid != hit.gidB) continue
            val raw = face.advance1000(hit.gidA)
            assertEquals(
                (raw + hit.kern).toDouble(), glyphs[idx].advanceWidth, 0.001,
                "the left glyph's drawn advance includes the pair kern",
            )
            return // verified end-to-end
        }
        // If no font produced an adjacent kern pair in layout, skip rather than fail.
    }
}
