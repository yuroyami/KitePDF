package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * OpenType-CFF (`.otf`) `@font-face` rendering — proves the CFF outline path added
 * to [EmbeddedFace]. The in-repo Noto `.otf` fonts are pure CFF (no `glyf` table),
 * so any non-null outline they yield must have come from the `CFF ` program, not
 * TrueType `glyf`. Runs on the JVM so it can read the fonts from disk; skips if the
 * checkout has no `.otf` fonts.
 */
class CffFontRenderTest {

    private fun repoRoot(): File? {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        val marker = "mupdf-master/resources/fonts/noto"
        while (d != null && !File(d, marker).exists()) d = d.parentFile
        return d
    }

    private fun otfFonts(): List<File> {
        val root = repoRoot() ?: return emptyList()
        return File(root, "mupdf-master/resources/fonts/noto")
            .listFiles { f -> f.extension.equals("otf", true) }?.sorted() ?: emptyList()
    }

    @Test
    fun otf_cff_faces_yield_outlines() {
        val otfs = otfFonts()
        if (otfs.isEmpty()) return // no CFF fonts in this checkout — skip
        var withOutlines = 0
        for (f in otfs.take(8)) {
            val face = FontRegistry.face("cff", bold = false, italic = false, f.readBytes()) ?: continue
            assertTrue(face.unitsPerEm > 0, "${f.name}: CFF face carries unitsPerEm")
            // Pure .otf has no glyf, so a non-null outline can only be the CFF program.
            val hasOutline = (1 until 400).any { gid -> face.outline(gid) != null }
            if (hasOutline) withOutlines++
        }
        assertTrue(withOutlines > 0, "at least one .otf CFF face produced glyph outlines")
    }

    @Test
    fun otf_renders_through_epub_font_face_path() {
        val otfs = otfFonts()
        if (otfs.isEmpty()) return
        for (f in otfs.take(20)) {
            val bytes = f.readBytes()
            val face = FontRegistry.face("cff", bold = false, italic = false, bytes) ?: continue
            // A BMP codepoint the font both maps (cmap) and can outline (CFF).
            var cp = -1
            for (c in 0x21..0xFFFF) {
                val gid = face.gidFor(c)
                if (gid > 0 && face.outline(gid) != null) { cp = c; break }
            }
            if (cp < 0) continue
            val text = cp.toChar().toString()
            val css = "@font-face{font-family:'CFF';src:url(font.otf)}p{font-family:'CFF'}"
            val epub = EpubFixtures.epub(
                "<body><style>$css</style><p>$text</p></body>",
                listOf("OEBPS/font.otf" to bytes),
            )
            val doc = EpubDocument.open(epub)
            val runs = doc.pages.flatMap { page ->
                RecordingCanvas().also { page.renderTo(it) }.calls
                    .filterIsInstance<RecordingCanvas.Call.Glyphs>()
            }
            if (runs.any { r -> r.hasOutlines && r.glyphs.any { it.outline != null } }) return // success
        }
        assertTrue(otfs.isEmpty(), "an .otf @font-face should render CFF outlines through the EPUB path")
    }
}
