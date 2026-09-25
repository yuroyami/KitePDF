package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.font.OpenTypeGsub
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Shapes words through GSUB the way [BoxLayout] does and compares the glyph ids with
 * HarfBuzz's `hb-shape` (#211): real words of each script, random words of the Indic
 * scripts, Khmer, Myanmar, the Universal Shaping Engine, Arabic, Urdu and Syriac, and random
 * words with combining marks. Skips when `hb-shape` or the Noto fonts of the MuPDF resources
 * are missing.
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
            // HarfBuzz lists the glyphs of a right-to-left word from its end.
            if (isRightToLeft(word)) gids.reversed() else gids
        }
    }

    /**
     * True when HarfBuzz shapes [word] right to left: the first character of a script, by the
     * JDK's own Unicode data, is of a script that HarfBuzz's hb_script_get_horizontal_direction
     * lists as right to left.
     */
    private fun isRightToLeft(word: String): Boolean {
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

    private val faces = HashMap<File, EmbeddedFace>()

    private fun face(font: File): EmbeddedFace =
        faces.getOrPut(font) { FontRegistry.face("t", bold = false, italic = false, font.readBytes()) ?: error("${font.name} does not parse") }

    /** The glyph ids KitePDF gives [word] in [font], through the same steps as [BoxLayout]. */
    private fun kitepdf(font: File, word: String): List<Int> {
        val face = face(font)
        val gsub = face.gsub ?: OpenTypeGsub.EMPTY
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
        "NotoSerifKhmer-Regular.otf" to listOf(
            "ភាសាខ្មែរ", "កម្ពុជា", "ស្រី", "ក្រុង", "ព្រះ", "ខ្ញុំ", "សួស្តី", "អរគុណ", "ឆ្នាំ", "ប្រទេស", "កើត", "ចៅ",
        ),
        "NotoSerifSinhala-Regular.otf" to listOf(
            "සිංහල", "ශ්‍රී", "ලංකාව", "කෙසේද", "ස්තූතියි", "ක්‍ෂ", "පොත", "කෝ", "රෞ", "ද්‍ය",
        ),
        "NotoSerifTibetan-Regular.otf" to listOf("བོད་ཡིག", "སྐད", "བཀྲ་ཤིས", "བསྒྲུབས", "རྒྱལ", "ཧཱུྃ", "ཨོཾ"),
        "NotoSerifMyanmar-Regular.otf" to listOf(
            "မြန်မာ", "ဘာသာ", "ကျေးဇူးတင်ပါတယ်", "မင်္ဂလာပါ", "သင်္ချိုင်း", "ကြောင်", "ပြည်", "စာအုပ်", "ရှင်", "လျှော့",
        ),
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
     * Random words of Khmer shape to the glyphs HarfBuzz gives (#317): consonants with subscript
     * consonants after a coeng, now and then a Robat sign, a vowel, a sign or a joiner, and
     * strings of any characters of the block, which make broken syllables.
     */
    @Test
    fun random_khmer_words_shape_to_the_glyphs_harfbuzz_gives() {
        val dir = fontsDir().orSkip("The Noto fonts of mupdf-master/resources")
        val tool = hbShape().orSkip("hb-shape")
        val font = File(dir, "NotoSerifKhmer-Regular.otf").orSkipIfMissing()
        val consonants = (0x1780..0x17A2).toList() + listOf(0x179A, 0x179A)
        val vowels = (0x17B6..0x17C5).toList()
        val signs = (0x17C6..0x17D1).toList() + listOf(0x17D3, 0x17DD)
        val any = (0x1780..0x17DD).filter { face(font).gidFor(it) != 0 } + listOf(0x200C, 0x200D, 0x25CC)
        val random = Random(0x1780)
        val words = List(800) {
            buildString {
                if (random.nextInt(4) == 0) repeat(2 + random.nextInt(5)) { appendCodePoint(any.random(random)) }
                else repeat(1 + random.nextInt(3)) {
                    appendCodePoint(consonants.random(random))
                    repeat(random.nextInt(3)) { appendCodePoint(0x17D2); appendCodePoint(consonants.random(random)) }
                    if (random.nextInt(5) == 0) appendCodePoint(listOf(0x17C9, 0x17CA, 0x17CC).random(random))
                    if (random.nextBoolean()) appendCodePoint(vowels.random(random))
                    if (random.nextInt(3) == 0) appendCodePoint(signs.random(random))
                    if (random.nextInt(8) == 0) appendCodePoint(listOf(0x200C, 0x200D).random(random))
                }
            }
        }.distinct()
        val theirs = harfbuzz(tool, font, words)
        val failures = words.indices.filter { kitepdf(font, words[it]) != theirs[it] }.map { i ->
            "${words[i].codePoints().toArray().joinToString(" ") { "%04X".format(it) }}: KitePDF ${kitepdf(font, words[i])}, HarfBuzz ${theirs[i]}"
        }
        println("random khmer words: ${words.size - failures.size} of ${words.size} match")
        assertTrue(failures.isEmpty(), failures.take(20).joinToString("\n"))
    }

    /**
     * Random words of Myanmar shape to the glyphs HarfBuzz gives (#317): consonants with a kinzi
     * before them, a stacked consonant, medials, vowels and tones, and strings of any characters
     * of the block, which make broken syllables.
     */
    @Test
    fun random_myanmar_words_shape_to_the_glyphs_harfbuzz_gives() {
        val dir = fontsDir().orSkip("The Noto fonts of mupdf-master/resources")
        val tool = hbShape().orSkip("hb-shape")
        val font = File(dir, "NotoSerifMyanmar-Regular.otf").orSkipIfMissing()
        val consonants = (0x1000..0x1020).toList() + listOf(0x1004, 0x101B, 0x1004, 0x101B)
        val medials = listOf(0x103B, 0x103C, 0x103D, 0x103E)
        val vowels = listOf(0x102B, 0x102C, 0x102D, 0x102E, 0x102F, 0x1030, 0x1031, 0x1032, 0x1036)
        val tones = listOf(0x1037, 0x1038, 0x103A)
        val any = (0x1000..0x104F).filter { face(font).gidFor(it) != 0 } + listOf(0x200C, 0x200D, 0x25CC)
        val random = Random(0x1000)
        val words = List(800) {
            buildString {
                if (random.nextInt(4) == 0) repeat(2 + random.nextInt(5)) { appendCodePoint(any.random(random)) }
                else repeat(1 + random.nextInt(3)) {
                    if (random.nextInt(6) == 0) { appendCodePoint(0x1004); appendCodePoint(0x103A); appendCodePoint(0x1039) }
                    appendCodePoint(consonants.random(random))
                    if (random.nextInt(4) == 0) { appendCodePoint(0x1039); appendCodePoint(consonants.random(random)) }
                    repeat(random.nextInt(3)) { appendCodePoint(medials.random(random)) }
                    repeat(random.nextInt(3)) { appendCodePoint(vowels.random(random)) }
                    if (random.nextBoolean()) appendCodePoint(tones.random(random))
                    if (random.nextInt(8) == 0) appendCodePoint(listOf(0x200C, 0x200D).random(random))
                }
            }
        }.distinct()
        val theirs = harfbuzz(tool, font, words)
        val failures = words.indices.filter { kitepdf(font, words[it]) != theirs[it] }.map { i ->
            "${words[i].codePoints().toArray().joinToString(" ") { "%04X".format(it) }}: KitePDF ${kitepdf(font, words[i])}, HarfBuzz ${theirs[i]}"
        }
        println("random myanmar words: ${words.size - failures.size} of ${words.size} match")
        assertTrue(failures.isEmpty(), failures.take(20).joinToString("\n"))
    }

    /** The Noto font of each script of the Universal Shaping Engine, with its block. */
    private val use = listOf(
        "NotoSerifSinhala-Regular.otf" to 0x0D80..0x0DFF, "NotoSerifTibetan-Regular.otf" to 0x0F00..0x0FFF,
        "NotoSansMongolian-Regular.otf" to 0x1800..0x18AF, "NotoSansTagalog-Regular.otf" to 0x1700..0x171F,
        "NotoSansHanunoo-Regular.otf" to 0x1720..0x173F, "NotoSansBuhid-Regular.otf" to 0x1740..0x175F,
        "NotoSansTagbanwa-Regular.otf" to 0x1760..0x177F, "NotoSansLimbu-Regular.otf" to 0x1900..0x194F,
        "NotoSansTaiLe-Regular.otf" to 0x1950..0x197F, "NotoSansBuginese-Regular.otf" to 0x1A00..0x1A1F,
        "NotoSansTaiTham-Regular.otf" to 0x1A20..0x1AAF, "NotoSerifBalinese-Regular.otf" to 0x1B00..0x1B7F,
        "NotoSansSundanese-Regular.otf" to 0x1B80..0x1BBF, "NotoSansBatak-Regular.otf" to 0x1BC0..0x1BFF,
        "NotoSansLepcha-Regular.otf" to 0x1C00..0x1C4F, "NotoSansTifinagh-Regular.otf" to 0x2D30..0x2D7F,
        "NotoSansSylotiNagri-Regular.otf" to 0xA800..0xA82F, "NotoSansPhagsPa-Regular.otf" to 0xA840..0xA87F,
        "NotoSansSaurashtra-Regular.otf" to 0xA880..0xA8DF, "NotoSansKayahLi-Regular.otf" to 0xA900..0xA92F,
        "NotoSansRejang-Regular.otf" to 0xA930..0xA95F, "NotoSansJavanese-Regular.otf" to 0xA980..0xA9DF,
        "NotoSansCham-Regular.otf" to 0xAA00..0xAA5F, "NotoSansTaiViet-Regular.otf" to 0xAA80..0xAADF,
        "NotoSansMeeteiMayek-Regular.otf" to 0xABC0..0xABFF, "NotoSansNKo-Regular.otf" to 0x07C0..0x07FF,
        "NotoSansMandaic-Regular.otf" to 0x0840..0x085F,
        // Outside the Basic Multilingual Plane (#319)
        "NotoSansAdlam-Regular.otf" to 0x1E900..0x1E95F, "NotoSerifAhom-Regular.otf" to 0x11700..0x1174F,
        "NotoSansBhaiksuki-Regular.otf" to 0x11C00..0x11C6F, "NotoSansBrahmi-Regular.otf" to 0x11000..0x1107F,
        "NotoSansChakma-Regular.otf" to 0x11100..0x1114F, "NotoSansChorasmian-Regular.otf" to 0x10FB0..0x10FDF,
        "NotoSansCyproMinoan-Regular.otf" to 0x12F90..0x12FFF, "NotoSerifDivesAkuru-Regular.otf" to 0x11900..0x1195F,
        "NotoSerifDogra-Regular.otf" to 0x11800..0x1184F, "NotoSansDuployan-Regular.otf" to 0x1BC00..0x1BCAF,
        "NotoSansEgyptianHieroglyphs-Regular.otf" to 0x13000..0x1345F,
        "NotoSansElymaic-Regular.otf" to 0x10FE0..0x10FFF, "NotoSansGunjalaGondi-Regular.otf" to 0x11D60..0x11DAF,
        "NotoSansMasaramGondi-Regular.otf" to 0x11D00..0x11D5F, "NotoSerifGrantha-Regular.otf" to 0x11300..0x1137F,
        "NotoSansHanifiRohingya-Regular.otf" to 0x10D00..0x10D3F,
        "NotoSansPahawhHmong-Regular.otf" to 0x16B00..0x16B8F,
        "NotoSerifNyiakengPuachueHmong-Regular.otf" to 0x1E100..0x1E14F,
        "NotoSansKawi-Regular.otf" to 0x11F00..0x11F5F, "NotoSansKharoshthi-Regular.otf" to 0x10A00..0x10A5F,
        "NotoSerifKhojki-Regular.otf" to 0x11200..0x1124F,
        "NotoSerifKhitanSmallScript-Regular.otf" to 0x18B00..0x18CFF, "NotoSansKaithi-Regular.otf" to 0x11080..0x110CF,
        "NotoSansMahajani-Regular.otf" to 0x11150..0x1117F, "NotoSerifMakasar-Regular.otf" to 0x11EE0..0x11EFF,
        "NotoSansManichaean-Regular.otf" to 0x10AC0..0x10AFF, "NotoSansMarchen-Regular.otf" to 0x11C70..0x11CBF,
        "NotoSansMedefaidrin-Regular.otf" to 0x16E40..0x16E9F, "NotoSansModi-Regular.otf" to 0x11600..0x1165F,
        "NotoSansMultani-Regular.otf" to 0x11280..0x112AF, "NotoSansNagMundari-Regular.otf" to 0x1E4D0..0x1E4FF,
        "NotoSansNandinagari-Regular.otf" to 0x119A0..0x119FF, "NotoSansNewa-Regular.otf" to 0x11400..0x1147F,
        "NotoSansOldSogdian-Regular.otf" to 0x10F00..0x10F2F, "NotoSerifOldUyghur-Regular.otf" to 0x10F70..0x10FAF,
        "NotoSansPsalterPahlavi-Regular.otf" to 0x10B80..0x10BAF, "NotoSansMiao-Regular.otf" to 0x16F00..0x16F9F,
        "NotoSansSharada-Regular.otf" to 0x11180..0x111DF, "NotoSansSiddham-Regular.otf" to 0x11580..0x115FF,
        "NotoSansKhudawadi-Regular.otf" to 0x112B0..0x112FF, "NotoSansSogdian-Regular.otf" to 0x10F30..0x10F6F,
        "NotoSansSoyombo-Regular.otf" to 0x11A50..0x11AAF, "NotoSansTakri-Regular.otf" to 0x11680..0x116CF,
        "NotoSansTirhuta-Regular.otf" to 0x11480..0x114DF, "NotoSansTangsa-Regular.otf" to 0x16A70..0x16ACF,
        "NotoSerifToto-Regular.otf" to 0x1E290..0x1E2BF, "NotoSerifVithkuqi-Regular.otf" to 0x10570..0x105BF,
        "NotoSansWancho-Regular.otf" to 0x1E2C0..0x1E2FF, "NotoSerifYezidi-Regular.otf" to 0x10E80..0x10EBF,
        "NotoSansZanabazarSquare-Regular.otf" to 0x11A00..0x11A4F,
    )

    /**
     * Random words of the scripts of the Universal Shaping Engine shape to the glyphs HarfBuzz
     * gives (#317, #319): letters followed by signs of the block, now and then a joiner, and
     * strings of any characters of the block, which make broken clusters. Four of the fonts have
     * no GSUB, so these words also check the reordering of a font without one.
     */
    @Test
    fun random_use_words_shape_to_the_glyphs_harfbuzz_gives() {
        val dir = fontsDir().orSkip("The Noto fonts of mupdf-master/resources")
        val tool = hbShape().orSkip("hb-shape")
        val failures = ArrayList<String>()
        var total = 0
        for ((name, block) in use) {
            val font = File(dir, name).takeIf { it.exists() } ?: continue
            val chars = block.filter { face(font).gidFor(it) != 0 }
            val letters = chars.filter { Character.getType(it) == Character.OTHER_LETTER.toInt() }.ifEmpty { chars }
            val signs = (chars - letters.toSet()).ifEmpty { chars }
            // The Khitan filler joins the characters of a Khitan block.
            val extra = listOf(0x200C, 0x200D) + listOf(0x25CC, 0x16FE4).filter { face(font).gidFor(it) != 0 }
            val random = Random(block.first)
            val words = List(200) {
                buildString {
                    if (random.nextInt(4) == 0) repeat(2 + random.nextInt(5)) { appendCodePoint((chars + extra).random(random)) }
                    else repeat(1 + random.nextInt(3)) {
                        appendCodePoint(letters.random(random))
                        repeat(random.nextInt(4)) { appendCodePoint(signs.random(random)) }
                        if (random.nextInt(10) == 0) appendCodePoint(extra.random(random))
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
        println("random use words: ${total - failures.size} of $total match")
        assertTrue(failures.isEmpty(), failures.take(20).joinToString("\n"))
    }

    private fun File.orSkipIfMissing(): File = takeIf { it.exists() }.orSkip(name)

    /** The Noto font of scripts outside the Basic Multilingual Plane that HarfBuzz shapes with its default shaper, with their characters. */
    private val supplementary: List<Pair<String, List<Int>>> = listOf(
        "NotoSansGothic-Regular.otf" to 0x10330..0x1034F, "NotoSansDeseret-Regular.otf" to 0x10400..0x1044F,
        "NotoSansOsage-Regular.otf" to 0x104B0..0x104FF, "NotoSansOldItalic-Regular.otf" to 0x10300..0x1032F,
        "NotoSansOldPermic-Regular.otf" to 0x10350..0x1037F, "NotoSansOldHungarian-Regular.otf" to 0x10C80..0x10CFF,
        "NotoSansOldTurkic-Regular.otf" to 0x10C00..0x10C4F, "NotoSansPhoenician-Regular.otf" to 0x10900..0x1091F,
        "NotoSansImperialAramaic-Regular.otf" to 0x10840..0x1085F, "NotoSansNabataean-Regular.otf" to 0x10880..0x108AF,
        "NotoSansPalmyrene-Regular.otf" to 0x10860..0x1087F, "NotoSansHatran-Regular.otf" to 0x108E0..0x108FF,
        "NotoSansAvestan-Regular.otf" to 0x10B00..0x10B3F,
        "NotoSansInscriptionalPahlavi-Regular.otf" to 0x10B60..0x10B7F,
        "NotoSansInscriptionalParthian-Regular.otf" to 0x10B40..0x10B5F,
        "NotoSansLydian-Regular.otf" to 0x10920..0x1093F, "NotoSansLycian-Regular.otf" to 0x10280..0x1029F,
        "NotoSansCarian-Regular.otf" to 0x102A0..0x102DF, "NotoSansCypriot-Regular.otf" to 0x10800..0x1083F,
        "NotoSansMeroitic-Regular.otf" to 0x109A0..0x109FF, "NotoSansOldSouthArabian-Regular.otf" to 0x10A60..0x10A7F,
        "NotoSansOldNorthArabian-Regular.otf" to 0x10A80..0x10A9F,
        "NotoSansMendeKikakui-Regular.otf" to 0x1E800..0x1E8DF, "NotoSansBassaVah-Regular.otf" to 0x16AD0..0x16AFF,
        "NotoSansMro-Regular.otf" to 0x16A40..0x16A6F, "NotoSansSoraSompeng-Regular.otf" to 0x110D0..0x110FF,
        "NotoSansElbasan-Regular.otf" to 0x10500..0x1052F, "NotoSansCaucasianAlbanian-Regular.otf" to 0x10530..0x1056F,
        "NotoSansWarangCiti-Regular.otf" to 0x118A0..0x118FF, "NotoSansPauCinHau-Regular.otf" to 0x11AC0..0x11AFF,
        "NotoSansLinearB-Regular.otf" to 0x10000..0x1007F, "NotoSansUgaritic-Regular.otf" to 0x10380..0x1039F,
        "NotoSansOldPersian-Regular.otf" to 0x103A0..0x103DF, "NotoSansShavian-Regular.otf" to 0x10450..0x1047F,
        "NotoSansOsmanya-Regular.otf" to 0x10480..0x104AF,
        // The combining letters of the supplement, over the letters of the Basic Multilingual Plane.
        "NotoSansGlagolitic-Regular.otf" to (0x2C00..0x2C5F) + (0x1E000..0x1E02F),
        "NotoMusic-Regular.otf" to 0x1D100..0x1D1FF, "NotoSansMath-Regular.otf" to 0x1D400..0x1D7FF,
        "NotoSansSignWriting-Regular.otf" to 0x1D800..0x1DAAF, "NotoSansBamum-Regular.otf" to 0x16800..0x16A3F,
        "NotoSansAnatolianHieroglyphs-Regular.otf" to 0x14400..0x1467F,
    ).map { (name, chars) -> name to chars.toList() }

    /**
     * Random words of the scripts outside the Basic Multilingual Plane that HarfBuzz shapes with
     * its default shaper shape to the glyphs it gives (#319): characters of the font, some with
     * marks after them, and now and then a joiner. The musical symbols decompose, and several of
     * the scripts run from right to left.
     */
    @Test
    fun random_words_outside_the_bmp_shape_to_the_glyphs_harfbuzz_gives() {
        val dir = fontsDir().orSkip("The Noto fonts of mupdf-master/resources")
        val tool = hbShape().orSkip("hb-shape")
        val marksTypes = setOf(Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK).map { it.toInt() }
        val failures = ArrayList<String>()
        var total = 0
        for ((name, range) in supplementary) {
            val font = File(dir, name).takeIf { it.exists() } ?: continue
            val chars = range.filter { face(font).gidFor(it) != 0 }
            val marks = chars.filter { Character.getType(it) in marksTypes }
            val bases = (chars - marks.toSet()).ifEmpty { chars }
            val random = Random(range.first())
            val words = List(150) {
                buildString {
                    repeat(1 + random.nextInt(3)) {
                        appendCodePoint(bases.random(random))
                        if (marks.isNotEmpty()) repeat(random.nextInt(3)) { appendCodePoint(marks.random(random)) }
                        if (random.nextInt(12) == 0) appendCodePoint(listOf(0x200C, 0x200D).random(random))
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
        println("random words outside the bmp: ${total - failures.size} of $total match")
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
     * Right-to-left words with brackets and other mirrored characters shape to the glyphs HarfBuzz
     * gives (#321): a character becomes its mirror when the font has it, and `rtlm` reaches the
     * rest. Syriac takes the Arabic shaper, Adlam and Hanifi Rohingya the Universal Shaping
     * Engine, and Hebrew and Phoenician the default shaper. In Noto Sans Math, the Hebrew letters
     * make the words right to left, and `rtlm` gives the mirrored forms of symbols such as the
     * square root, which have no mirror character.
     */
    @Test
    fun right_to_left_words_mirror_their_brackets_as_harfbuzz_does() {
        val dir = fontsDir().orSkip("The Noto fonts of mupdf-master/resources")
        val tool = hbShape().orSkip("hb-shape")
        val mirrored = listOf(
            0x28, 0x29, 0x3C, 0x3E, 0x5B, 0x5D, 0x7B, 0x7D, 0xAB, 0xBB, 0x2039, 0x203A, 0x2208, 0x220B,
            0x2211, 0x221A, 0x222B, 0x2264, 0x2265, 0x2282, 0x2283,
        )
        val sets = listOf(
            "NotoSansSyriac-Regular.otf" to (0x0710..0x072C).toList(),
            "NotoSansAdlam-Regular.otf" to (0x1E900..0x1E943).toList(),
            "NotoSansHanifiRohingya-Regular.otf" to (0x10D00..0x10D23).toList(),
            "NotoSerifHebrew-Regular.otf" to (0x05D0..0x05EA).toList(),
            "NotoSansPhoenician-Regular.otf" to (0x10900..0x10915).toList(),
            "NotoSansMath-Regular.otf" to (0x05D0..0x05EA).toList(),
        )
        val failures = ArrayList<String>()
        var total = 0
        for ((index, set) in sets.withIndex()) {
            val (name, letters) = set
            val font = File(dir, name).takeIf { it.exists() } ?: continue
            val random = Random(300 + index)
            val words = List(150) {
                buildString {
                    if (random.nextBoolean()) appendCodePoint(mirrored.random(random))
                    repeat(1 + random.nextInt(4)) { appendCodePoint(letters.random(random)) }
                    if (random.nextInt(3) == 0) appendCodePoint(mirrored.random(random))
                    if (random.nextBoolean()) appendCodePoint(mirrored.random(random))
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
        println("right-to-left words with mirrored characters: ${total - failures.size} of $total match")
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
