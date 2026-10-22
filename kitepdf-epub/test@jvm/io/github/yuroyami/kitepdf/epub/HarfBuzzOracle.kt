package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.font.OpenTypeGsub
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * What the shaping oracle tests share: where `hb-shape` and the Noto fonts of the MuPDF
 * resources are, how to run `hb-shape`, and how KitePDF shapes the same word (#211, #622).
 */
internal object HarfBuzzOracle {

    /** The Noto fonts of the MuPDF resources, found by walking up from the working directory, or null. */
    fun fontsDir(): File? {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "mupdf-master/resources/fonts/noto").exists()) d = d.parentFile
        return d?.let { File(it, "mupdf-master/resources/fonts/noto") }
    }

    fun hbShape(): String? =
        listOf("/opt/homebrew/bin/hb-shape", "/usr/local/bin/hb-shape", "/usr/bin/hb-shape").firstOrNull { File(it).canExecute() }

    /** One glyph of `hb-shape`: its id, its offsets and its advance, all in font units. */
    data class Positioned(val gid: Int, val dx: Int, val dy: Int, val advance: Int)

    /** The glyph ids HarfBuzz gives each of [words] in [font], in logical order, from one run of `hb-shape`. */
    fun harfbuzz(tool: String, font: File, words: List<String>): List<List<Int>> =
        shape(tool, font, words, positions = false).mapIndexed { i, run ->
            // HarfBuzz lists the glyphs of a right-to-left word from its end.
            run.map { it.gid }.let { if (isRightToLeft(words[i])) it.reversed() else it }
        }

    /**
     * The glyphs HarfBuzz gives each of [words] in [font], in the order it draws them from the
     * left, with their positions. `hb-shape` prints a glyph as `gid@dx,dy+advance` and leaves out
     * `@dx,dy` when both are zero.
     */
    fun positioned(tool: String, font: File, words: List<String>): List<List<Positioned>> =
        shape(tool, font, words, positions = true)

    private val glyph = Regex("(\\d+)(?:@(-?\\d+),(-?\\d+))?(?:\\+(-?\\d+))?")

    private fun shape(tool: String, font: File, words: List<String>, positions: Boolean): List<List<Positioned>> {
        val text = File.createTempFile("hb-oracle", ".txt").apply { deleteOnExit(); writeText(words.joinToString("\n", postfix = "\n")) }
        val flags = listOf("--no-glyph-names", "--no-clusters") + if (positions) emptyList() else listOf("--no-positions")
        val p = ProcessBuilder(listOf(tool) + flags + listOf("--text-file=${text.path}", font.path)).redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor(60, TimeUnit.SECONDS)
        return words.indices.map { i ->
            lines[i].trim().removePrefix("[").removeSuffix("]").split('|').filter { it.isNotBlank() }.map { item ->
                val m = glyph.matchEntire(item.trim()) ?: error("cannot read the hb-shape glyph '$item'")
                Positioned(
                    m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull() ?: 0,
                    m.groupValues[3].toIntOrNull() ?: 0, m.groupValues[4].toIntOrNull() ?: 0,
                )
            }
        }
    }

    /**
     * True when HarfBuzz shapes [word] right to left: the first character of a script, by the
     * JDK's own Unicode data, is of a script that HarfBuzz's hb_script_get_horizontal_direction
     * lists as right to left.
     */
    fun isRightToLeft(word: String): Boolean {
        for (cp in word.codePoints().toArray()) {
            val script = Character.UnicodeScript.of(cp)
            if (script == Character.UnicodeScript.COMMON || script == Character.UnicodeScript.INHERITED ||
                script == Character.UnicodeScript.UNKNOWN
            ) continue
            return script.name in RTL_SCRIPTS
        }
        return false
    }

    private val RTL_SCRIPTS = setOf(
        "ARABIC", "HEBREW", "SYRIAC", "THAANA", "CYPRIOT", "KHAROSHTHI", "PHOENICIAN", "NKO", "LYDIAN", "AVESTAN",
        "IMPERIAL_ARAMAIC", "INSCRIPTIONAL_PAHLAVI", "INSCRIPTIONAL_PARTHIAN", "OLD_SOUTH_ARABIAN", "OLD_TURKIC",
        "SAMARITAN", "MANDAIC", "MEROITIC_CURSIVE", "MEROITIC_HIEROGLYPHS", "MANICHAEAN", "MENDE_KIKAKUI", "NABATAEAN",
        "OLD_NORTH_ARABIAN", "PALMYRENE", "PSALTER_PAHLAVI", "HATRAN", "ADLAM", "HANIFI_ROHINGYA", "OLD_SOGDIAN",
        "SOGDIAN", "ELYMAIC", "CHORASMIAN", "YEZIDI", "OLD_UYGHUR", "GARAY", "SIDETIC",
    )

    /** [font] parsed as the EPUB layout parses an embedded font. A test keeps the faces it loads, so they go with it. */
    fun loadFace(font: File): EmbeddedFace =
        FontRegistry.face("t", bold = false, italic = false, font.readBytes()) ?: error("${font.name} does not parse")

    /** The glyph ids KitePDF gives [word] in [face], through the same steps as [BoxLayout]. */
    fun kitepdf(face: EmbeddedFace, word: String): List<Int> {
        val gsub = face.gsub ?: OpenTypeGsub.EMPTY
        val cps = word.codePoints().toArray()
        val script = TextShaper.script(cps, gsub)
        val forms = if (ArabicJoining.hasArabic(cps)) ArabicJoining.forms(cps) else null
        return TextShaper.shape(face, gsub, script, cps, IntArray(cps.size) { face.gidFor(cps[it]) }, forms, optionalLigatures = true).map { it.gid }
    }
}
