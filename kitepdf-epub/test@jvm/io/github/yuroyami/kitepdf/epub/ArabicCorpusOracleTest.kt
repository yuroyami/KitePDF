package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.epub.HarfBuzzOracle.fontsDir
import io.github.yuroyami.kitepdf.epub.HarfBuzzOracle.harfbuzz
import io.github.yuroyami.kitepdf.epub.HarfBuzzOracle.hbShape
import io.github.yuroyami.kitepdf.epub.HarfBuzzOracle.kitepdf
import io.github.yuroyami.kitepdf.epub.HarfBuzzOracle.loadFace
import io.github.yuroyami.kitepdf.epub.HarfBuzzOracle.positioned
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Arabic words of the corpus book shape and position as HarfBuzz does (#622): the glyph ids
 * through GSUB, then the mark offsets and the advances of the laid-out text through GPOS, in Noto
 * Naskh Arabic. Skips without `hb-shape`, the Noto fonts or the corpus book.
 */
class ArabicCorpusOracleTest {

    private fun repoFile(relative: String): File? {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, relative).exists()) d = d.parentFile
        return d?.let { File(it, relative) }
    }

    private fun naskh(): File {
        val dir = fontsDir().orSkip("The Noto fonts of mupdf-master/resources")
        return File(dir, "NotoNaskhArabic-Regular.otf").takeIf { it.exists() }.orSkip("NotoNaskhArabic-Regular.otf")
    }

    /**
     * The distinct words of Arabic letters and marks in the chapters of the corpus book, the most
     * frequent first, up to [limit]. They come from the XHTML, in the order they are typed.
     */
    private fun corpusWords(limit: Int): List<String> {
        val book = repoFile("corpus/epub/idpf-regime-anticancer-arabic.epub").orSkip("the Arabic corpus book")
        val arabic = Regex("[\\u0621-\\u064A\\u064B-\\u0652\\u0670\\u0671-\\u06D3]+")
        val counts = HashMap<String, Int>()
        ZipFile(book).use { zip ->
            for (entry in zip.entries()) {
                if (!entry.name.endsWith(".xhtml") && !entry.name.endsWith(".html")) continue
                val text = zip.getInputStream(entry).readBytes().decodeToString()
                for (m in arabic.findAll(text)) counts[m.value] = (counts[m.value] ?: 0) + 1
            }
        }
        return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }.take(limit)
    }

    @Test
    fun corpus_words_shape_to_the_glyphs_harfbuzz_gives() {
        val tool = hbShape().orSkip("hb-shape")
        val font = naskh()
        val face = loadFace(font)
        val words = corpusWords(300)
        assertTrue(words.size >= 100, "the corpus book gives too few words: ${words.size}")
        val theirs = harfbuzz(tool, font, words)
        val failures = words.indices.filter { kitepdf(face, words[it]) != theirs[it] }
            .map { "${words[it]}: KitePDF ${kitepdf(face, words[it])}, HarfBuzz ${theirs[it]}" }
        println("arabic corpus glyphs: ${words.size - failures.size} of ${words.size} words match")
        assertTrue(failures.isEmpty(), failures.take(20).joinToString("\n"))
    }

    @Test
    fun corpus_words_lay_out_with_the_positions_harfbuzz_gives() {
        val tool = hbShape().orSkip("hb-shape")
        val font = naskh()
        val face = loadFace(font)
        val words = corpusWords(300)
        val bytes = font.readBytes()
        // One word per paragraph, small enough that no word wraps, so each paragraph is one glyph run.
        val css = "@font-face{font-family:'N';src:url(n.otf)} p{font-family:'N';font-size:40px;direction:rtl;margin:0 0 4px 0}"
        val body = "<body><style>$css</style>" + words.joinToString("") { "<p>$it</p>" } + "</body>"
        val doc = EpubDocument.open(EpubFixtures.epub(body, listOf("OEBPS/n.otf" to bytes), language = "ar"))
        val runs = doc.pages.flatMap { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        }
        assertEquals(words.size, runs.size, "one glyph run per word")
        val theirs = positioned(tool, font, words)
        val failures = ArrayList<String>()
        for ((i, word) in words.withIndex()) {
            // Both list the glyphs of the word from the left.
            val ours = runs[i].glyphs
            val hb = theirs[i]
            if (ours.map { it.gid } != hb.map { it.gid }) {
                failures += "$word: glyphs KitePDF ${ours.map { it.gid }}, HarfBuzz ${hb.map { it.gid }}"
                continue
            }
            for ((g, ref) in ours.zip(hb)) {
                // The advance is in thousandths of an em, the offsets in font units, as hb-shape prints them.
                val advance = ref.advance * 1000.0 / face.unitsPerEm
                if (abs(g.xOffset - ref.dx) > 1.0 || abs(g.yOffset - ref.dy) > 1.0 || abs(g.advanceWidth - advance) > 1.5) {
                    failures += "$word glyph ${g.gid}: KitePDF (${g.xOffset}, ${g.yOffset}, ${g.advanceWidth}), HarfBuzz (${ref.dx}, ${ref.dy}, $advance)"
                }
            }
        }
        println("arabic corpus positions: ${words.size - failures.size} of ${words.size} words match")
        assertTrue(failures.isEmpty(), failures.take(20).joinToString("\n"))
    }
}
