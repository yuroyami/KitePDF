package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.font.TrueTypeFont
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import io.github.yuroyami.kitepdf.writer.EmbeddedFont
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * End-to-end validation of OpenType/CFF (`.otf`) embedding + CFF subsetting
 * (Milestone B'). Uses MuPDF's bundled `NotoSans-Regular.otf` (a CFF font).
 *
 * Correctness oracle for the from-scratch CFF subsetter:
 *  - **Cross-engine agreement.** KitePDF's renderer and `mutool` are two
 *    independent CFF parsers + CID→glyph resolvers + rasterisers. If they render
 *    the embedded subset to near-identical pixels, the emitted CFF (INDEX/DICT
 *    layout, charset, FDSelect, offsets) is structurally and semantically sound.
 *  - **Ink present.** Rules out the "both engines drew `.notdef`/blank" trap —
 *    a wrong charset direction collapses to `.notdef` and would show here.
 *  - **Subset == full.** The subset must render identically to the all-glyph
 *    embed (same glyphs, same positions).
 */
class CffEmbedOracleTest {

    private val text = "Hello"   // plain ASCII — no seac accent composition

    private fun otfFile(): File? {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            val f = File(d, "mupdf-master/resources/fonts/noto/NotoSans-Regular.otf")
            if (f.isFile) return f
            d = d.parentFile
        }
        return null
    }

    @Test
    fun cff_otf_subsets_embeds_and_renders_correctly() {
        val otf = otfFile()
        assumeTrue("NotoSans-Regular.otf not found — skipping.", otf != null)
        val bytes = otf!!.readBytes()

        // Sanity: this really is a CFF font and the glyphs we draw are real (non-.notdef).
        assertTrue(EmbeddedFont.load(bytes).isCff, "expected a CFF/OTTO font")
        val parsed = TrueTypeFont.parse(bytes)
        for (c in text) assertTrue(parsed.glyphIdForCodePoint(c.code) != 0, "'$c' is .notdef in the test font")

        val subsetPdf = PdfBuilder()
            .page { text(EmbeddedFont.load(bytes), 48.0, 72.0, 400.0, text) }
            .build()
        val fullPdf = PdfBuilder()
            .page { text(EmbeddedFont.load(bytes, subset = false), 48.0, 72.0, 400.0, text) }
            .build()

        // Structure: composite Type0 → CIDFontType0 with a bare-CFF /FontFile3, no
        // /CIDToGIDMap (CFF selects glyphs via its charset), subset-tagged BaseFont.
        val raw = subsetPdf.toString(Charsets.ISO_8859_1)
        assertTrue(raw.contains("/CIDFontType0"), "not a CIDFontType0 descendant")
        assertTrue(raw.contains("/FontFile3"), "no /FontFile3 program")
        assertTrue(raw.contains("/CIDFontType0C"), "FontFile3 /Subtype not CIDFontType0C")
        assertTrue(Regex("/BaseFont\\s*/[A-Z]{6}\\+").containsMatchIn(raw), "no subset tag on /BaseFont")

        // Subset must drop glyphs → smaller than the all-glyph embed.
        assertTrue(subsetPdf.size < fullPdf.size, "subset (${subsetPdf.size}) not < full (${fullPdf.size})")

        // Reader recovers the text via /ToUnicode.
        assertContains(KitePDF.open(subsetPdf).pages[0].extractText(), text)

        assumeTrue("mutool not found — skipping render oracle.", MuPdfOracle.binary != null)
        val subFile = File.createTempFile("kite-cff-sub-", ".pdf").apply { writeBytes(subsetPdf) }
        val fullFile = File.createTempFile("kite-cff-full-", ".pdf").apply { writeBytes(fullPdf) }
        try {
            val muSub = MuPdfOracle.render(subFile, 1, 144)
            val muFull = MuPdfOracle.render(fullFile, 1, 144)
            val kiteSub = AwtPdfRasterizer.renderToImage(KitePDF.open(subsetPdf).pages[0], scale = 2.0)
            assertTrue(muSub != null && muFull != null, "mutool failed to render the CFF embed")

            // Ink present — the glyphs actually drew (not blank / not .notdef-empty).
            assertTrue(ImageDiff.nonBackgroundPixels(kiteSub) > 1000, "KitePDF render is blank")
            assertTrue(ImageDiff.nonBackgroundPixels(muSub!!) > 1000, "mutool render is blank")

            // Cross-engine agreement: two independent CFF engines must concur.
            val crossMae = ImageDiff.compare(kiteSub, muSub).meanAbsError
            assertTrue(crossMae < 0.06, "KitePDF vs mutool disagree on the CFF embed (MAE=$crossMae)")

            // Subset renders identically to the all-glyph embed.
            val subVsFull = ImageDiff.compare(muSub, muFull!!).meanAbsError
            assertTrue(subVsFull < 0.02, "subset differs from full embed (MAE=$subVsFull)")
        } finally {
            subFile.delete()
            fullFile.delete()
        }
    }
}
