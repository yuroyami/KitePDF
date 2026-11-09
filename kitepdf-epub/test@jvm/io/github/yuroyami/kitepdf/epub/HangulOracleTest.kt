package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.HarfBuzzOracle.harfbuzz
import io.github.yuroyami.kitepdf.epub.HarfBuzzOracle.hbShape
import io.github.yuroyami.kitepdf.epub.HarfBuzzOracle.loadFace
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Korean written as conjoining jamo shapes to the glyphs HarfBuzz gives, in the precomposed
 * syllables of `DroidSansFallbackFull.ttf` from the MuPDF resources (#635). Skips when `hb-shape`
 * or the font is missing.
 */
class HangulOracleTest {

    private fun font(): File? {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "mupdf-master/resources/fonts/droid/DroidSansFallbackFull.ttf").exists()) d = d.parentFile
        return d?.let { File(it, "mupdf-master/resources/fonts/droid/DroidSansFallbackFull.ttf") }
    }

    @Test
    fun jamo_words_shape_as_harfbuzz_shapes_them() {
        val font = font().orOracle("DroidSansFallbackFull.ttf of mupdf-master/resources")
        val tool = hbShape().orOracle("hb-shape")
        val words = listOf(
            "각", // 각
            "한국", // 한국
            "서울", // 서울
            "각", // 가 with a trailing jamo
        )
        val face = loadFace(font)
        val theirs = harfbuzz(tool, font, words)
        for ((i, word) in words.withIndex()) {
            assertEquals(theirs[i], HarfBuzzOracle.kitepdf(face, word), "the glyphs of word $i")
        }
    }
}
