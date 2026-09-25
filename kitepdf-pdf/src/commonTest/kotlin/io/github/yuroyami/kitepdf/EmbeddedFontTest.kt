package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.filters.FilterChain
import io.github.yuroyami.kitepdf.core.font.TrueTypeFont
import io.github.yuroyami.kitepdf.core.font.TtfFormatException
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.writer.EmbeddedFont
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Guards [EmbeddedFont.load]'s input validation. The full embed/round-trip path
 * (TrueType and OpenType/CFF) needs a real font file and runs in the JVM oracle
 * tests (`EmbeddedFontOracleTest`, `CffEmbedOracleTest`), where the filesystem
 * and `mutool` are available.
 */
class EmbeddedFontTest {

    @Test fun load_rejects_malformed_sfnt() {
        // An "OTTO" scaler with no table directory. OpenType/CFF is supported now,
        // but this truncated header has no parseable tables, so it's a format error.
        val otto = byteArrayOf(0x4F, 0x54, 0x54, 0x4F, 0, 0, 0, 0, 0, 0, 0, 0)
        assertFailsWith<TtfFormatException> { EmbeddedFont.load(otto) }
    }

    @Test fun load_rejects_a_collection_without_faces() {
        // A "ttcf" header that lists no font.
        val ttc = byteArrayOf(0x74, 0x74, 0x63, 0x66, 0, 1, 0, 0, 0, 0, 0, 0)
        assertFailsWith<TtfFormatException> { EmbeddedFont.load(ttc) }
    }

    @Test fun load_embeds_the_face_of_a_collection_it_is_asked_for() {
        val ttc = TestFonts.collectionOf(TestFonts.squareAndSpaceTtf(500), TestFonts.squareAndSpaceTtf(300))
        assertEquals(2, TrueTypeFont.faceCount(ttc))
        for (subset in listOf(false, true)) {
            val font = EmbeddedFont.load(ttc, subset = subset, faceIndex = 1)
            val pdf = PdfBuilder().page(width = 100.0, height = 100.0) { text(font, 20.0, 10.0, 10.0, "A") }.build(compress = false)
            val program = embeddedProgram(PdfDocument.open(pdf))
            // A PDF font program holds one font (#199).
            assertEquals(1, TrueTypeFont.faceCount(program), "subset=$subset")
            val ttf = TrueTypeFont.parse(program)
            assertTrue((0 until ttf.numGlyphs).any { ttf.outline(it)?.bbox?.xMax == 300 }, "face 1 draws A, subset=$subset")
        }
    }

    /** The decoded /FontFile2 of the first font on the first page. */
    private fun embeddedProgram(doc: PdfDocument): ByteArray {
        val fonts = doc.pages[0].resources!!.getDict("Font", doc)!!
        val font = fonts.map.values.first().resolve(doc) as PdfDictionary
        val descendant = font.getArray("DescendantFonts", doc)!!.first().resolve(doc) as PdfDictionary
        val descriptor = descendant.getDict("FontDescriptor", doc)!!
        return FilterChain.decode(descriptor["FontFile2"]!!.resolve(doc) as PdfStream)
    }

    @Test fun load_rejects_tiny_input() {
        assertFailsWith<IllegalArgumentException> { EmbeddedFont.load(byteArrayOf(1, 2, 3)) }
    }
}
