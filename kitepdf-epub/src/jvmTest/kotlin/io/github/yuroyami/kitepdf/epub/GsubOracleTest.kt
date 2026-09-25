package io.github.yuroyami.kitepdf.epub

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Shapes words through GSUB the way [BoxLayout] does and compares the glyph ids with
 * HarfBuzz's `hb-shape` (#211): real words of each script, random words of the Indic
 * scripts, Arabic, Urdu and Syriac, and random words with combining marks. Skips when
 * `hb-shape` or the Noto fonts of the MuPDF resources are missing.
 */
class GsubOracleTest {

    private fun fontsDir(): File? {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "mupdf-master/resources/fonts/noto").exists()) d = d.parentFile
        return d?.let { File(it, "mupdf-master/resources/fonts/noto") }
    }

    private fun hbShape(): String? =
        listOf("/opt/homebrew/bin/hb-shape", "/usr/local/bin/hb-shape", "/usr/bin/hb-shape").firstOrNull { File(it).canExecute() }

    /** The glyph ids HarfBuzz gives each of [words] in [font], in logical order, from one run of `hb-shape`. */
    private fun harfbuzz(tool: String, font: File, words: List<String>): List<List<Int>> {
        val text = File.createTempFile("gsub-oracle", ".txt").apply { deleteOnExit(); writeText(words.joinToString("\n", postfix = "\n")) }
        val p = ProcessBuilder(tool, "--no-positions", "--no-glyph-names", "--no-clusters", "--text-file=${text.path}", font.path)
            .redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor(60, TimeUnit.SECONDS)
        return words.mapIndexed { i, word ->
            val gids = lines[i].trim().removePrefix("[").removeSuffix("]").split('|').filter { it.isNotBlank() }.map { it.trim().toInt() }
            if (word.any { it.code in 0x0590..0x08FF }) gids.reversed() else gids
        }
    }

    private val faces = HashMap<File, EmbeddedFace>()

    private fun face(font: File): EmbeddedFace =
        faces.getOrPut(font) { FontRegistry.face("t", bold = false, italic = false, font.readBytes()) ?: error("${font.name} does not parse") }

    /** The glyph ids KitePDF gives [word] in [font], through the same steps as [BoxLayout]. */
    private fun kitepdf(font: File, word: String): List<Int> {
        val face = face(font)
        val gsub = face.gsub ?: return word.codePoints().toArray().map { face.gidFor(it) }
        val cps = word.codePoints().toArray()
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
        "NotoSerifThai-Regular.otf" to listOf("ภาษา", "ไทย", "สวัสดี", "ประเทศ", "กรุงเทพ", "น้ำ", "ทำ", "คำ", "กำลัง", "ต่ำ", "จำนวน"),
        "NotoSerifLao-Regular.otf" to listOf("ພາສາ", "ລາວ", "ນ້ຳ", "ຄຳ", "ທຳ"),
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
        "NotoSerifGurmukhi-Regular.otf" to listOf(
            "ਪੰਜਾਬੀ", "ਗੁਰਮੁਖੀ", "ਸਿੱਖ", "ਕਿਤਾਬ", "ਪ੍ਰੇਮ", "ਧਰਮ", "ਸ਼ਬਦ", "ਖ਼ਾਲਸਾ", "ਕ੍ਰਿਪਾ", "ਸ੍ਵਰ", "ਦੁੱਖ", "ਕਿਉਂ",
        ),
        "NotoSerifGujarati-Regular.otf" to listOf(
            "ગુજરાતી", "ભાષા", "કિતાબ", "ધર્મ", "પ્રેમ", "સ્ત્રી", "શ્રી", "ક્ષમા", "કૃપા", "દ્વાર", "ત્ર", "દ્ય", "ર્કિ",
        ),
        "NotoSerifOriya-Regular.otf" to listOf(
            "ଓଡ଼ିଆ", "ଭାଷା", "କି", "ଧର୍ମ", "ପ୍ରେମ", "କୋଣ", "ସ୍ତ୍ରୀ", "କୈ", "କୌ", "ଶ୍ରୀ", "ଯ୍ୟ", "ର୍କି",
        ),
        "NotoSerifTamil-Regular.otf" to listOf("தமிழ்", "மொழி", "கை", "கொ", "கௌ", "ஸ்ரீ", "க்ஷ", "புத்தகம்", "கோ", "நீ", "ஔ", "க்ஷ்மி"),
        "NotoSerifTelugu-Regular.otf" to listOf(
            "తెలుగు", "భాష", "కి", "ధర్మం", "ప్రేమ", "స్త్రీ", "కొ", "శ్రీ", "కై", "ర్\u200Dక", "ఔ", "క్ష", "త్ర్య",
        ),
        "NotoSerifKannada-Regular.otf" to listOf(
            "ಕನ್ನಡ", "ಭಾಷೆ", "ಕಿ", "ಧರ್ಮ", "ಪ್ರೇಮ", "ಸ್ತ್ರೀ", "ಕೊ", "ಶ್ರೀ", "ಕೀ", "ಕೋ", "ಕ್ಷ", "ರ್ಕಿ", "ಕೌ",
        ),
        "NotoSerifMalayalam-Regular.otf" to listOf(
            "മലയാളം", "ഭാഷ", "കി", "ധർമ്മം", "പ്രേമം", "സ്ത്രീ", "കൊ", "ശ്രീ", "ക്ര", "ൎക്ക",
            "അവൻ", "അവന്\u200D", "കാർ", "കാര്\u200D", "ക്ഷ", "ന്റെ",
        ),
    )

    @Test
    fun words_shape_to_the_glyphs_harfbuzz_gives() {
        val dir = fontsDir().orSkip("The Noto fonts of mupdf-master/resources")
        val tool = hbShape().orSkip("hb-shape")
        val failures = ArrayList<String>()
        var total = 0
        for ((name, words) in cases) {
            val font = File(dir, name).takeIf { it.exists() } ?: continue
            val theirs = harfbuzz(tool, font, words)
            for ((i, word) in words.withIndex()) {
                total++
                val ours = kitepdf(font, word)
                val line = "$name $word: KitePDF $ours, HarfBuzz ${theirs[i]}"
                println(line)
                if (ours != theirs[i]) failures += line
            }
        }
        println("gsub oracle: ${total - failures.size} of $total words match")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** The Noto font of each Indic script, with the first character of its block. */
    private val indic = listOf(
        "NotoSerifDevanagari-Regular.otf" to 0x0900, "NotoSerifBengali-Regular.otf" to 0x0980,
        "NotoSerifGurmukhi-Regular.otf" to 0x0A00, "NotoSerifGujarati-Regular.otf" to 0x0A80,
        "NotoSerifOriya-Regular.otf" to 0x0B00, "NotoSerifTamil-Regular.otf" to 0x0B80,
        "NotoSerifTelugu-Regular.otf" to 0x0C00, "NotoSerifKannada-Regular.otf" to 0x0C80,
        "NotoSerifMalayalam-Regular.otf" to 0x0D00,
    )

    /**
     * Random words of each Indic script shape to the glyphs HarfBuzz gives (#211). The words mix
     * the letters and signs of the block with viramas, joiners and placeholders in a fixed random
     * order, so they reach characters and syllable shapes that real words rarely use.
     */
    @Test
    fun random_indic_words_shape_to_the_glyphs_harfbuzz_gives() {
        val dir = fontsDir().orSkip("The Noto fonts of mupdf-master/resources")
        val tool = hbShape().orSkip("hb-shape")
        val failures = ArrayList<String>()
        var total = 0
        for ((name, block) in indic) {
            val font = File(dir, name).takeIf { it.exists() } ?: continue
            val chars = (block until block + 0x80).filter { face(font).gidFor(it) != 0 }
            val letters = chars.filter { it.toChar().category == CharCategory.OTHER_LETTER }
            val signs = chars - letters.toSet()
            val random = Random(block)
            // Every one of these scripts has its virama at 0x4D and its Ra at 0x30 in the block.
            val words = List(400) { randomWord(random, letters, signs, block + 0x4D, block + 0x30) }.distinct()
            val theirs = harfbuzz(tool, font, words)
            for ((i, word) in words.withIndex()) {
                total++
                val ours = kitepdf(font, word)
                if (ours != theirs[i]) {
                    failures += "$name ${word.codePoints().toArray().joinToString(" ") { "%04X".format(it) }}: KitePDF $ours, HarfBuzz ${theirs[i]}"
                }
            }
        }
        println("random indic words: ${total - failures.size} of $total match")
        assertTrue(failures.isEmpty(), failures.take(20).joinToString("\n"))
    }

    /**
     * Random words of Arabic, Urdu and Syriac shape to the glyphs HarfBuzz gives (#315, #316,
     * #318): letters with up to two marks each, and now and then a joiner, a right-to-left mark
     * or a tatweel. The words leave out the Syriac abbreviation mark, which needs `stch`.
     */
    @Test
    fun random_arabic_and_syriac_words_shape_to_the_glyphs_harfbuzz_gives() {
        val dir = fontsDir().orSkip("The Noto fonts of mupdf-master/resources")
        val tool = hbShape().orSkip("hb-shape")
        val joiners = listOf(0x200C, 0x200D, 0x200F, 0x2060, 0x0640)
        val arabicLetters = (0x0621..0x064A).toList() +
            listOf(0x0671, 0x0674, 0x0679, 0x067E, 0x0686, 0x0688, 0x0691, 0x06A9, 0x06AF, 0x06BE, 0x06C1, 0x06CC, 0x06D2, 0x06D5)
        val arabicMarks = (0x064B..0x0655).toList() + listOf(0x0658, 0x0670, 0x06DC, 0x06E3, 0x06E7, 0x06E8)
        val sets = listOf(
            Triple("NotoNaskhArabic-Regular.otf", arabicLetters, arabicMarks),
            Triple("NotoNastaliqUrdu-Regular.otf", arabicLetters, arabicMarks),
            Triple("NotoSansSyriac-Regular.otf", (0x0710..0x072F) + (0x074D..0x074F), (0x0730..0x074A).toList()),
        )
        val failures = ArrayList<String>()
        var total = 0
        for ((index, set) in sets.withIndex()) {
            val (name, letters, marks) = set
            val font = File(dir, name).takeIf { it.exists() } ?: continue
            val random = Random(index)
            val words = List(500) {
                buildString {
                    repeat(2 + random.nextInt(4)) {
                        appendCodePoint(letters.random(random))
                        repeat(random.nextInt(3)) { appendCodePoint(marks.random(random)) }
                        if (random.nextInt(5) == 0) appendCodePoint(joiners.random(random))
                    }
                }
            }.distinct()
            val theirs = harfbuzz(tool, font, words)
            for ((i, word) in words.withIndex()) {
                total++
                val ours = kitepdf(font, word)
                if (ours != theirs[i]) {
                    failures += "$name ${word.codePoints().toArray().joinToString(" ") { "%04X".format(it) }}: KitePDF $ours, HarfBuzz ${theirs[i]}"
                }
            }
        }
        println("random arabic and syriac words: ${total - failures.size} of $total match")
        assertTrue(failures.isEmpty(), failures.take(20).joinToString("\n"))
    }

    /**
     * Random words of Latin, Vietnamese, Greek, Cyrillic, Hebrew, Thai and Lao letters with up to
     * three combining marks each shape to the glyphs HarfBuzz gives (#316, #317). HarfBuzz
     * decomposes a letter that marks follow and composes it again with the marks the font has a
     * glyph for, and it splits Thai and Lao sara am before it puts the marks in order.
     */
    @Test
    fun random_words_with_marks_shape_to_the_glyphs_harfbuzz_gives() {
        val dir = fontsDir().orSkip("The Noto fonts of mupdf-master/resources")
        val tool = hbShape().orSkip("hb-shape")
        val latinMarks = (0x0300..0x0315).toList() + listOf(0x031B, 0x0323, 0x0324, 0x0325, 0x0327, 0x0328, 0x032D, 0x0330, 0x0331)
        val sets = listOf(
            Triple(
                "NotoSerif-Regular.otf", "aeiouyAEIOUYcnszgklrtdhw".map { it.code } +
                    listOf(0x00E9, 0x00EA, 0x00F4, 0x0103, 0x01A1, 0x01B0, 0x1EB9, 0x0131), latinMarks,
            ),
            Triple(
                "NotoSerif-Regular.otf", (0x03B1..0x03C9).toList() + listOf(0x03AC, 0x03AD, 0x1F00, 0x1F10, 0x1F70, 0x1FB6, 0x0391, 0x03A9),
                listOf(0x0300, 0x0301, 0x0304, 0x0306, 0x0308, 0x0313, 0x0314, 0x0342, 0x0345),
            ),
            Triple("NotoSerif-Regular.otf", (0x0430..0x044F).toList() + listOf(0x0415, 0x0418, 0x0456), listOf(0x0300, 0x0301, 0x0306, 0x0308, 0x030F, 0x0311)),
            Triple("NotoSerifHebrew-Regular.otf", (0x05D0..0x05EA).toList(), (0x05B0..0x05BC).toList() + listOf(0x05BF, 0x05C1, 0x05C2, 0x05C7)),
            Triple(
                "NotoSerifThai-Regular.otf", (0x0E01..0x0E2E).toList() + listOf(0x0E32, 0x0E40, 0x0E44),
                listOf(0x0E31, 0x0E33, 0x0E33) + (0x0E34..0x0E3A) + (0x0E47..0x0E4E),
            ),
            Triple(
                "NotoSerifLao-Regular.otf", (0x0E81..0x0EAE).filter { it !in listOf(0x0E83, 0x0E85, 0x0E8B, 0x0EA4, 0x0EA6) } + listOf(0x0EB2, 0x0EC0),
                listOf(0x0EB1, 0x0EB3, 0x0EB3) + (0x0EB4..0x0EBC) + (0x0EC8..0x0ECD),
            ),
        )
        val failures = ArrayList<String>()
        var total = 0
        for ((index, set) in sets.withIndex()) {
            val (name, letters, marks) = set
            val font = File(dir, name).takeIf { it.exists() } ?: continue
            val random = Random(100 + index)
            val words = List(500) {
                buildString {
                    repeat(1 + random.nextInt(4)) {
                        appendCodePoint(letters.random(random))
                        repeat(random.nextInt(4)) { appendCodePoint(marks.random(random)) }
                    }
                }
            }.distinct()
            val theirs = harfbuzz(tool, font, words)
            for ((i, word) in words.withIndex()) {
                total++
                val ours = kitepdf(font, word)
                if (ours != theirs[i]) {
                    failures += "$name ${word.codePoints().toArray().joinToString(" ") { "%04X".format(it) }}: KitePDF $ours, HarfBuzz ${theirs[i]}"
                }
            }
        }
        println("random words with marks: ${total - failures.size} of $total match")
        assertTrue(failures.isEmpty(), failures.take(20).joinToString("\n"))
    }

    /**
     * One to three syllables of letters joined by viramas, some with a joiner after the virama,
     * each followed by signs. Some words start with a sign, a placeholder, or a Ra and virama
     * with or without a ZWJ.
     */
    private fun randomWord(random: Random, letters: List<Int>, signs: List<Int>, virama: Int, ra: Int): String = buildString {
        when (random.nextInt(6)) {
            0 -> appendCodePoint(signs.random(random))
            1 -> appendCodePoint(listOf(0x25CC, 0x00A0).random(random))
            2 -> { appendCodePoint(ra); appendCodePoint(virama); if (random.nextBoolean()) appendCodePoint(0x200D) }
        }
        repeat(1 + random.nextInt(3)) {
            appendCodePoint(letters.random(random))
            while (random.nextInt(3) == 0) {
                appendCodePoint(virama)
                if (random.nextInt(4) == 0) appendCodePoint(if (random.nextBoolean()) 0x200D else 0x200C)
                appendCodePoint(letters.random(random))
            }
            repeat(random.nextInt(3)) { appendCodePoint(signs.random(random)) }
        }
    }
}
