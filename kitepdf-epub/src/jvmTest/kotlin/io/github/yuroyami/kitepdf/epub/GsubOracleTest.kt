package io.github.yuroyami.kitepdf.epub

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Shapes words through GSUB the way [BoxLayout] does and compares the glyph ids with
 * HarfBuzz's `hb-shape` (#211). Skips when `hb-shape` or the Noto fonts of the MuPDF
 * resources are missing.
 */
class GsubOracleTest {

    private fun fontsDir(): File? {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "mupdf-master/resources/fonts/noto").exists()) d = d.parentFile
        return d?.let { File(it, "mupdf-master/resources/fonts/noto") }
    }

    private fun hbShape(): String? =
        listOf("/opt/homebrew/bin/hb-shape", "/usr/local/bin/hb-shape", "/usr/bin/hb-shape").firstOrNull { File(it).canExecute() }

    /** The glyph ids HarfBuzz gives [word] in [font], in logical order. */
    private fun harfbuzz(tool: String, font: File, word: String): List<Int> {
        val p = ProcessBuilder(tool, "--no-positions", "--no-glyph-names", "--no-clusters", font.path, word)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor(20, TimeUnit.SECONDS)
        val gids = out.removePrefix("[").removeSuffix("]").split('|').filter { it.isNotBlank() }.map { it.trim().toInt() }
        val rtl = word.any { it.code in 0x0590..0x08FF }
        return if (rtl) gids.reversed() else gids
    }

    /** The glyph ids KitePDF gives [word] in [font], through the same steps as [BoxLayout]. */
    private fun kitepdf(font: File, word: String): List<Int> {
        val face = FontRegistry.face("t", bold = false, italic = false, font.readBytes()) ?: error("${font.name} does not parse")
        val gsub = face.gsub ?: return word.codePoints().toArray().map { face.gidFor(it) }
        val ordered = word.codePoints().toArray().toMutableList()
        CombiningClass.reorder(ordered) { it }
        val cps = ordered.toIntArray()
        val script = TextShaper.script(cps, gsub)
        val forms = if (ArabicJoining.hasArabic(cps)) ArabicJoining.forms(cps) else null
        return TextShaper.shape(face, gsub, script, cps, IntArray(cps.size) { face.gidFor(cps[it]) }, forms, optionalLigatures = true).map { it.gid }
    }

    private val cases = mapOf(
        "NotoSerif-Regular.otf" to listOf(
            "find", "office", "affluent", "ffl", "Quixotic", "fjord", "réfléchi", "f\u0301i", "naïve",
            "Ελληνικά", "ἀρχῇ", "Русский", "ёлка", "Tiếng", "Việt",
        ),
        "NotoNaskhArabic-Regular.otf" to listOf(
            "بسم", "الله", "الرحمن", "الرحيم", "لا", "سلام", "عليكم", "مُحَمَّد", "كتاب", "فلسطين", "لله",
            "ـبـ", "لأن", "إلى", "قُرْآن", "مُسْتَشْفَى", "فارسی", "گفتگو", "لإ", "ﷲ", "١٢٣",
        ),
        "NotoNastaliqUrdu-Regular.otf" to listOf(
            "اردو", "پاکستان", "زبان", "محبت", "کتاب", "نستعلیق", "لاہور", "خوبصورت", "ٹیلیفون", "بھائی", "ہے",
        ),
        "NotoSerifHebrew-Regular.otf" to listOf("שָׁלוֹם", "עברית", "בְּרֵאשִׁית", "יִשְׂרָאֵל", "הַמֶּלֶךְ"),
        "NotoSerifThai-Regular.otf" to listOf("ภาษา", "ไทย", "สวัสดี", "ประเทศ", "กรุงเทพ"),
        "NotoSerifDevanagari-Regular.otf" to listOf(
            "हिन्दी", "नमस्ते", "क्षत्रिय", "कि", "धर्म", "कर्म", "पुत्र", "विद्या", "श्री", "द्वार",
            "प्रेम", "ज़िंदगी", "फ़िल्म", "स्त्री", "आत्मा", "राष्ट्र", "कृष्ण", "हृदय", "र्क", "मैं",
            "क्\u200Dष", "क्\u200Cष", "र्\u200Dया", "द्ध्र्य", "श्र्य", "ि", "१२३", "ॐ", "अँधेरा", "ह्म",
            "र्कि", "र्त्", "ट्ठ", "ङ्क्ष", "प्र्", "ऋषि", "क़ि", "र्ज़", "त्र्य", "न्त्र्य",
        ),
        "NotoSerifBengali-Regular.otf" to listOf(
            "বাংলা", "ভাষা", "কি", "কর্ম", "স্ত্রী", "বিদ্যা", "কোথায়", "শ্রী", "ক্ষমা", "রবীন্দ্রনাথ",
            "র্য", "র\u200Dয", "ক্\u200Dষ", "ৎ", "সৌরভ", "গৈরিক", "ড়", "ঢ়", "য়", "১২৩",
        ),
    )

    /** Scripts whose shaper does more than GSUB alone, printed for information and not asserted. */
    private val informational = mapOf<String, List<String>>()

    @Test
    fun words_shape_to_the_glyphs_harfbuzz_gives() {
        val dir = fontsDir().orSkip("The Noto fonts of mupdf-master/resources")
        val tool = hbShape().orSkip("hb-shape")
        val failures = ArrayList<String>()
        var total = 0
        for ((name, words) in cases) {
            val font = File(dir, name).takeIf { it.exists() } ?: continue
            for (word in words) {
                total++
                val ours = kitepdf(font, word)
                val theirs = harfbuzz(tool, font, word)
                val line = "$name $word: KitePDF $ours, HarfBuzz $theirs"
                println(line)
                if (ours != theirs) failures += line
            }
        }
        println("gsub oracle: ${total - failures.size} of $total words match")
        for ((name, words) in informational) {
            val font = File(dir, name).takeIf { it.exists() } ?: continue
            for (word in words) println("informational $name $word: KitePDF ${kitepdf(font, word)}, HarfBuzz ${harfbuzz(tool, font, word)}")
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
