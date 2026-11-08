package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.writer.EmbeddedFont
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.test.Test
import kotlin.test.assertTrue

/** `restore()` brings back the font that `showText` encodes for, as `Q` does (ISO 32000-1, 8.4.2; #733). */
class ContentStreamFontStateTest {

    private val emb = EmbeddedFont.load(TestFonts.squareAndSpaceTtf(500))

    private fun content(block: io.github.yuroyami.kitepdf.writer.ContentStreamBuilder.() -> Unit): String {
        val pdf = PdfBuilder().page(width = 100.0, height = 100.0, block = block).build(compress = false).decodeToString()
        // The page content is the stream that shows text; the font program is another.
        return pdf.split("endstream").map { it.substringAfterLast("stream") }.first { "Tj" in it }
    }

    @Test
    fun aStandardFontInsideSaveDoesNotOutliveRestore() {
        val text = content {
            beginText(); setFont(emb, 10.0); moveText(10.0, 10.0); showText("A"); endText()
            save(); beginText(); setFont(StandardFont.Helvetica, 10.0); showText("A"); endText(); restore()
            beginText(); moveText(10.0, 30.0); showText("A"); endText()
        }
        // After Q the embedded font is current again, so the last show is a two-byte glyph code.
        assertTrue(text.trimEnd().lines().last { "Tj" in it }.trim().startsWith("<"), text)
    }

    @Test
    fun anEmbeddedFontInsideSaveDoesNotOutliveRestore() {
        val text = content {
            beginText(); setFont(StandardFont.Helvetica, 10.0); moveText(10.0, 10.0); showText("A"); endText()
            save(); beginText(); setFont(emb, 10.0); showText("A"); endText(); restore()
            beginText(); moveText(10.0, 30.0); showText("A"); endText()
        }
        assertTrue(text.trimEnd().lines().last { "Tj" in it }.trim().startsWith("(A)"), text)
    }
}
