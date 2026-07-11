package io.github.yuroyami.kitepdf.font

import io.github.yuroyami.kitepdf.core.font.TrueTypeFont
import io.github.yuroyami.kitepdf.core.font.TrueTypeSubsetter

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Structural validation of [TrueTypeSubsetter] (Milestone B). Uses MuPDF's
 * bundled `DroidSansFallback.ttf` (TrueType/`glyf` CJK); skips if absent. The
 * decisive checks are the spec checksum (catches `head.checkSumAdjustment` /
 * table-checksum bugs) and a KitePDF re-parse; FreeType-inside-`mutool` is the
 * independent strict parser, exercised by the end-to-end `EmbeddedFontOracleTest`
 * which renders a subset (a malformed glyf/loca/hmtx would fail it).
 */
class TrueTypeSubsetterTest {

    private fun fontFile(): File? {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            val f = File(d, "mupdf-master/resources/fonts/droid/DroidSansFallback.ttf")
            if (f.isFile) return f
            d = d.parentFile
        }
        return null
    }

    @Test
    fun subset_is_small_checksum_correct_and_reparses() {
        val file = fontFile() ?: run {
            println("[TrueTypeSubsetterTest] DroidSansFallback.ttf not found — skipping.")
            return
        }
        val full = file.readBytes()
        val ttf = TrueTypeFont.parse(full)

        val han = '中'.code
        val used = "中文测试Hello".map { ttf.glyphIdForCodePoint(it.code) }.filter { it != 0 }.toSet()
        assertTrue(used.size >= 4, "expected several mapped glyphs, got $used")

        val sub = TrueTypeSubsetter.subset(ttf, used)

        // 1. Dramatically smaller than the whole program.
        assertTrue(sub.fontBytes.size < full.size / 4, "subset not small: ${sub.fontBytes.size} vs ${full.size}")

        // 2. SFNT checksum is spec-correct: the whole-font checksum (the sum of all
        //    big-endian u32 words) must equal the magic 0xB1B0AFBA. This is exactly
        //    what head.checkSumAdjustment is set to guarantee — a wrong adjustment
        //    fails here.
        assertEquals(0xB1B0AFBAL, wholeFontChecksum(sub.fontBytes), "head.checkSumAdjustment wrong")

        // 3. KitePDF re-parses it; glyph count == closure size; used glyphs survive.
        val re = TrueTypeFont.parse(sub.fontBytes)
        assertEquals(sub.oldToNew.size, re.numGlyphs, "subset glyph count != closure size")
        val newHan = sub.oldToNew.getValue(ttf.glyphIdForCodePoint(han))
        assertNotNull(re.outline(newHan), "subset lost the '中' outline")
        for (old in used) assertTrue(sub.oldToNew.getValue(old) < re.numGlyphs)
    }

    @Test
    fun empty_used_set_still_includes_notdef() {
        val file = fontFile() ?: return
        val ttf = TrueTypeFont.parse(file.readBytes())
        val sub = TrueTypeSubsetter.subset(ttf, emptySet())
        assertEquals(0xB1B0AFBAL, wholeFontChecksum(sub.fontBytes))
        assertTrue(sub.oldToNew.containsKey(0), ".notdef (gid 0) must always be present")
    }

    /** Sum of big-endian u32 words over the whole font, mod 2^32. */
    private fun wholeFontChecksum(b: ByteArray): Long {
        var sum = 0L
        var i = 0
        while (i + 3 < b.size) {
            val w = ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or
                ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)
            sum = (sum + w) and 0xFFFFFFFFL
            i += 4
        }
        return sum
    }
}
