package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** CSS Fonts 4, section 5: try declared families in order (#104). */
class FontFamilyListTest {
    private fun render(families: String, faceName: String = "Publisher", font: ByteArray = EpubFixtures.squareTtf()): RecordingCanvas.Call.Glyphs {
        val body = """
            <style>@font-face{font-family:'$faceName';src:url(font.ttf)}</style>
            <p style="font-family:$families">AAA</p>
        """.trimIndent()
        val doc = EpubDocument.open(EpubFixtures.epub(body, listOf("OEBPS/font.ttf" to font)))
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas)
        return canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().single()
    }

    @Test
    fun unavailable_families_are_skipped_until_the_embedded_face_resolves() {
        val direct = render("Publisher,serif")
        for (stack in listOf("Georgia,Publisher,serif", "Missing,'Publisher',serif", "Missing,Other,Publisher")) {
            val fallback = render(stack)
            assertTrue(fallback.hasOutlines, "embedded face in $stack")
            assertTrue(fallback.glyphs.all { it.outline != null })
            assertEquals(direct.unitsPerEm, fallback.unitsPerEm)
            assertEquals(direct.glyphs.map { it.advanceWidth }, fallback.glyphs.map { it.advanceWidth })
        }
    }

    @Test
    fun a_generic_family_stops_the_search_before_later_embedded_faces() {
        val run = render("Georgia,monospace,Publisher")
        assertTrue(!run.hasOutlines)
        assertEquals(KiteFontFamily.Monospace, run.fontSpec.family)
    }

    @Test
    fun quoted_commas_and_generic_words_are_family_names() {
        assertTrue(render("Missing,'Publisher, Serif',serif", "Publisher, Serif").hasOutlines)
        assertTrue(render("Missing,'serif',sans-serif", "serif").hasOutlines)
    }

    @Test
    fun corrupt_font_keeps_the_declared_generic_fallback() {
        val run = render("Missing,Publisher,sans-serif", font = "bad font".encodeToByteArray())
        assertTrue(!run.hasOutlines)
        assertEquals(KiteFontFamily.SansSerif, run.fontSpec.family)
        assertEquals("AAA", run.text)
    }

    @Test
    fun a_nested_inline_inherits_the_whole_family_list() {
        val doc = EpubDocument.open(EpubFixtures.epub(
            """<style>@font-face{font-family:Publisher;src:url(font.ttf)}</style>
                <p style="font-family:Georgia,Publisher,serif"><em>A</em></p>""",
            listOf("OEBPS/font.ttf" to EpubFixtures.squareTtf()),
        ))
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas)
        assertTrue(canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().single().hasOutlines)
    }
}
