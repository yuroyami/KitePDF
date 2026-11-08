package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals

/** @font-face weights, weight ranges and unicode-range (CSS Fonts 4, 4.4, 4.5 and 5.2; #929). */
class FontFaceMatchTest {

    /** The advance of the drawn `A`, which tells the faces apart: each face has its own. */
    private fun advance(faces: String, style: String = ""): Double {
        val body = "<style>$faces</style><p style=\"font-family:I;$style\">A</p>"
        val fonts = listOf(100, 300, 400, 700).map { "OEBPS/w$it.ttf" to EpubFixtures.squareTtf(advance = 500 + it) }
        val doc = EpubDocument.open(EpubFixtures.epub(body, fonts))
        val canvas = RecordingCanvas().also { doc.pages[0].renderTo(it) }
        return canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().single().glyphs.single().advanceWidth
    }

    private fun face(weight: String, file: Int, extra: String = "") = "@font-face{font-family:I;src:url(w$file.ttf);font-weight:$weight;$extra}"

    @Test
    fun normal_text_takes_the_400_face_however_the_faces_are_ordered() {
        val regular = advance(face("400", 400))
        assertEquals(regular, advance(face("100", 100) + face("400", 400)), "a Thin face before the Regular one")
        assertEquals(advance(face("100", 100)), advance(face("100", 100), "font-weight:400"), "the only face serves")
        assertEquals(regular, advance(face("100", 100) + face("300", 300) + face("400", 400) + face("700", 700)))
        // 5.2: for 400, a face up to 500 comes first, then the lighter ones.
        assertEquals(advance(face("300", 300)), advance(face("300", 300) + face("700", 700)))
        // For bold, the heavier face, else the nearest lighter one.
        assertEquals(advance(face("700", 700)), advance(face("400", 400) + face("700", 700), "font-weight:600"))
    }

    @Test
    fun a_weight_range_serves_every_weight_in_it() {
        val ranged = face("300 700", 300)
        assertEquals(advance(ranged), advance(ranged + face("100", 100), "font-weight:400"))
        assertEquals(advance(ranged), advance(face("100", 100) + ranged, "font-weight:700"))
    }

    @Test
    fun a_character_outside_a_faces_unicode_range_takes_the_face_that_holds_it() {
        val latinFirst = face("400", 100, "unicode-range:U+0-40") + face("400", 400, "unicode-range:U+41-7F")
        assertEquals(advance(face("400", 400)), advance(latinFirst), "A is in the second face's range only")
    }
}
