package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.font.GsubGlyph
import io.github.yuroyami.kitepdf.core.font.OpenTypeGsub

/**
 * The Universal Shaping Engine (#317), after the USE shaper of HarfBuzz 14.4, for Sinhala,
 * Tibetan, Brahmi, Adlam and the other scripts that HarfBuzz shapes with it (#319):
 *
 * 1. [prepare] puts a dotted circle into a Sinhala vowel sequence that would draw like another
 *    vowel, and decomposes and composes the characters as HarfBuzz normalizes them.
 * 2. Each character gets its USE category, and the text splits into clusters. The first glyphs
 *    of a cluster may take `rphf`, and a joining script takes its joining forms.
 * 3. `locl`, `ccmp`, `nukt` and `akhn` apply, then `rphf` and `pref`, which mark the repha and
 *    the pre-base glyphs they form, then `rkrf`, `abvf`, `blwf`, `half`, `pstf`, `vatu` and
 *    `cjct`.
 * 4. Reordering gives a broken cluster a dotted circle, moves the repha after the base, and
 *    moves a pre-base vowel or modifier before it.
 * 5. `isol`, `init`, `medi` and `fina` apply, then `abvs`, `blws`, `haln`, `pres` and `psts`.
 */
internal object UseShaper {

    /* ─── Categories (HarfBuzz's USE categories) ────────────────────────── */

    private const val O = 0
    private const val B = 1
    private const val N = 4
    private const val GB = 5
    private const val CGJ = 6
    private const val SUB = 11
    private const val H = 12
    private const val HN = 13
    private const val ZWNJ = 14
    private const val R = 18
    private const val VPRE = 22
    private const val VMPRE = 23
    private const val FABV = 24
    private const val FBLW = 25
    private const val FPST = 26
    private const val MABV = 27
    private const val MBLW = 28
    private const val MPST = 29
    private const val MPRE = 30
    private const val CMABV = 31
    private const val CMBLW = 32
    private const val VABV = 33
    private const val VBLW = 34
    private const val VPST = 35
    private const val VMABV = 37
    private const val VMBLW = 38
    private const val VMPST = 39
    private const val SMABV = 41
    private const val SMBLW = 42
    private const val CS = 43
    private const val IS = 44
    private const val FMABV = 45
    private const val FMBLW = 46
    private const val FMPST = 47
    private const val SK = 48
    private const val G = 49
    private const val J = 50
    private const val SB = 51
    private const val SE = 52
    private const val HVM = 53
    private const val HM = 54
    private const val HR = 55
    private const val RK = 56

    /** Set in [GsubGlyph.shaperData] for a character of general category Mn, Mc or Me. */
    private const val UNICODE_MARK = 1 shl 16

    /** The OpenType tags of the scripts HarfBuzz sends to this engine. */
    private val SCRIPTS = setOf(
        "adlm", "ahom", "bali", "batk", "berf", "bhks", "brah", "bugi", "buhd", "cakm", "cham", "chrs", "cpmn", "diak",
        "dogr", "dupl", "egyp", "elym", "gara", "gong", "gonm", "gran", "gukh", "hano", "hmng", "hmnp", "java", "kali",
        "kawi", "khar", "khoj", "kits", "krai", "kthi", "lana", "lepc", "limb", "mahj", "maka", "mand", "mani", "marc",
        "medf", "modi", "mong", "mtei", "mult", "nagm", "nand", "newa", "nko ", "onao", "ougr", "phag", "phlp", "plrd",
        "rjng", "rohg", "saur", "shrd", "sidd", "sidt", "sind", "sinh", "sogd", "sogo", "soyo", "sund", "sunu", "sylo",
        "tagb", "takr", "tale", "tavt", "tayo", "tfng", "tglg", "tibt", "tirh", "tnsa", "todr", "tols", "toto", "tutg",
        "vith", "wcho", "yezi", "zanb",
    )

    /** The scripts among [SCRIPTS] that join, as HarfBuzz's has_arabic_joining lists them. */
    private val JOINING = setOf("adlm", "chrs", "mand", "mani", "mong", "nko ", "ougr", "phag", "phlp", "rohg", "sogd")

    /**
     * True when this engine shapes [script] in a font with [gsub]. As in HarfBuzz, a font
     * without a tag for the script that falls back to its `DFLT` or `latn` script takes the
     * default features.
     */
    fun handles(script: String, gsub: OpenTypeGsub): Boolean {
        if (script !in SCRIPTS) return false
        val chosen = listOf(script, "DFLT", "dflt", "latn").firstOrNull { gsub.hasScript(it) }
        return chosen != "DFLT" && chosen != "latn"
    }

    /**
     * The characters of the word [cps] in [script] as HarfBuzz hands them to its USE shaper: a
     * vowel sequence that would draw like another vowel gets a dotted circle before its last
     * character, when the font has one, and then every character decomposes as far as the font
     * has glyphs for the parts, the marks go into canonical order, and a mark composes with a
     * letter before it when the font has the result.
     */
    fun prepare(script: String, cps: IntArray, hasGlyph: (Int) -> Boolean): Normalizer.Result {
        val codes = ArrayList<Int>(cps.size + 2)
        val sources = ArrayList<Int>(cps.size + 2)
        val confusable = CONFUSABLE_VOWELS[script]?.takeIf { hasGlyph(0x25CC) }
        var i = 0
        while (i < cps.size) {
            val lasts = if (confusable != null && i + 1 < cps.size) confusable[cps[i]] else null
            if (lasts != null && cps[i + 1] in lasts) {
                codes += cps[i]; sources += i
                codes += 0x25CC; sources += i + 1
                codes += cps[i + 1]; sources += i + 1
                i += 2
                continue
            }
            codes += cps[i]; sources += i
            i++
        }
        return Normalizer.normalize(codes.toIntArray(), sources.toIntArray(), hasGlyph, shortCircuit = false, compose = ::composition)
    }

    /**
     * Vowel sequences that would draw like another vowel, from the USE script development spec
     * as HarfBuzz reads it, for each script that has them: each vowel with the vowel signs that
     * may not follow it.
     */
    private val CONFUSABLE_VOWELS: Map<String, Map<Int, Set<Int>>> = mapOf(
        "sinh" to mapOf(
            0x0D85 to setOf(0x0DCF, 0x0DD0, 0x0DD1),
            0x0D8B to setOf(0x0DDF),
            0x0D8D to setOf(0x0DD8),
            0x0D8F to setOf(0x0DDF),
            0x0D91 to setOf(0x0DCA, 0x0DD9, 0x0DDA, 0x0DDC, 0x0DDD, 0x0DDE),
            0x0D94 to setOf(0x0DDF),
        ),
        "brah" to mapOf(0x11005 to setOf(0x11038), 0x1100B to setOf(0x1103E), 0x1100F to setOf(0x11042)),
        "khoj" to mapOf(
            0x11200 to setOf(0x1122C, 0x11231, 0x11233),
            0x11206 to setOf(0x1122C),
            0x1122C to setOf(0x11230, 0x11231),
            0x11240 to setOf(0x1122E),
        ),
        "sind" to mapOf(0x112B0 to setOf(0x112E0, 0x112E5, 0x112E6, 0x112E7, 0x112E8)),
        "tirh" to mapOf(
            0x11481 to setOf(0x114B0),
            0x1148B to setOf(0x114BA),
            0x1148D to setOf(0x114BA),
            0x114AA to setOf(0x114B5, 0x114B6),
        ),
        "modi" to mapOf(0x11600 to setOf(0x11639, 0x1163A), 0x11601 to setOf(0x11639, 0x1163A)),
        "takr" to mapOf(0x11680 to setOf(0x116AD, 0x116B4, 0x116B5), 0x11686 to setOf(0x116B2)),
    )

    /** HarfBuzz's compose_use: a split vowel does not compose again. */
    private fun composition(a: Int, b: Int): Int? = if (Normalizer.isMark(a)) null else Normalizer.composition(a, b)

    /**
     * Shapes the [glyphs] of one word, whose characters are [codePoints] from [prepare], in
     * place. [gidFor] gives the glyph of a character, for the dotted circle.
     */
    fun shape(gsub: OpenTypeGsub, script: String, glyphs: MutableList<GsubGlyph>, codePoints: IntArray, gidFor: (Int) -> Int, optionalLigatures: Boolean) {
        for ((i, g) in glyphs.withIndex()) {
            g.shaperData = category(codePoints[i]) or (if (Normalizer.isMark(codePoints[i])) UNICODE_MARK else 0)
        }
        // A joining script takes the joining form of each letter, as HarfBuzz's Arabic plan gives it.
        if (script in JOINING) {
            val forms = ArabicJoining.forms(codePoints)
            for ((i, g) in glyphs.withIndex()) {
                val form = forms[i]
                if (form != null && form.ordinal <= ArabicJoining.Form.FINA.ordinal) g.features += ArabicJoining.feature(form)
            }
        }
        val direction = if (TextShaper.isRightToLeft(script)) "rtla" else "ltra"
        // `ltrm` and `rtlm` reach only the glyphs that the direction of each character gives them (#321).
        gsub.substitute(glyphs, script, null, listOf(listOf("rvrn"), listOf(direction) + TextShaper.MIRRORED), TextShaper.MIRRORED.toSet())
        findClusters(glyphs)
        // The first glyphs of a cluster may form a repha: only the repha itself, when it is one.
        Syllables.forEach(glyphs) { start, end ->
            val limit = if (category(glyphs[start]) == R) 1 else minOf(3, end - start)
            for (i in start until start + limit) glyphs[i].features += "rphf"
        }
        if (script !in JOINING) setUpTopographicalForms(glyphs)

        gsub.substitute(glyphs, script, null, listOf(listOf("locl", "ccmp", "nukt", "akhn")), perSyllable = PER_SYLLABLE, manualZwj = MANUAL_ZWJ)
        for (g in glyphs) g.substituted = false
        gsub.substitute(glyphs, script, null, listOf(listOf("rphf")), POSITIONAL, PER_SYLLABLE, MANUAL_ZWJ)
        // A repha the font formed counts as one from now on.
        Syllables.forEach(glyphs) { start, end ->
            var i = start
            while (i < end && "rphf" in glyphs[i].features) {
                if (glyphs[i].substituted) { setCategory(glyphs[i], R); break }
                i++
            }
        }
        for (g in glyphs) g.substituted = false
        gsub.substitute(glyphs, script, null, listOf(listOf("pref")), POSITIONAL, PER_SYLLABLE, MANUAL_ZWJ)
        // A pre-base glyph the font formed behaves as a pre-base vowel.
        Syllables.forEach(glyphs) { start, end ->
            for (i in start until end) if (glyphs[i].substituted) { setCategory(glyphs[i], VPRE); break }
        }
        gsub.substitute(
            glyphs, script, null, listOf(listOf("rkrf", "abvf", "blwf", "half", "pstf", "vatu", "cjct")),
            POSITIONAL, PER_SYLLABLE, MANUAL_ZWJ,
        )

        Syllables.insertDottedCircles(glyphs, gidFor(0x25CC), BROKEN_CLUSTER, R, B, ::category)
        Syllables.forEach(glyphs) { start, end -> reorder(glyphs, start, end) }

        gsub.substitute(glyphs, script, null, listOf(listOf("isol", "init", "medi", "fina")), POSITIONAL)
        val presentation = listOf("abvs", "blws", "haln", "pres", "psts", "rlig", "rclt", "calt") +
            if (optionalLigatures) listOf("liga", "clig") else emptyList()
        gsub.substitute(glyphs, script, null, listOf(presentation), manualZwj = MANUAL_ZWJ)
        Syllables.mergeClusters(glyphs)
    }

    /** The features that reach only the glyphs chosen for them. */
    private val POSITIONAL = setOf("rphf", "isol", "init", "medi", "fina")

    /** The features HarfBuzz applies inside each cluster. */
    private val PER_SYLLABLE = setOf("locl", "ccmp", "nukt", "akhn", "rphf", "pref", "rkrf", "abvf", "blwf", "half", "pstf", "vatu", "cjct")

    /** The features of the engine that see a ZWJ. */
    private val MANUAL_ZWJ = setOf(
        "akhn", "rphf", "pref", "rkrf", "abvf", "blwf", "half", "pstf", "vatu", "cjct", "abvs", "blws", "haln", "pres", "psts",
    )

    private fun category(g: GsubGlyph): Int = g.shaperData and 0xFF
    private fun setCategory(g: GsubGlyph, cat: Int) { g.shaperData = (g.shaperData and 0xFF.inv()) or cat }

    /**
     * The forms of the clusters of a script that does not join by letters, as HarfBuzz's
     * setup_topographical_masks gives them: a run of clusters that can join takes init, medi
     * and fina, and a cluster alone takes isol.
     */
    private fun setUpTopographicalForms(glyphs: List<GsubGlyph>) {
        var lastStart = 0
        var lastForm: String? = null
        Syllables.forEach(glyphs) { start, end ->
            when (glyphs[start].syllable and 0xF) {
                HIEROGLYPH_CLUSTER, NON_CLUSTER -> lastForm = null
                else -> {
                    val join = lastForm == "fina" || lastForm == "isol"
                    if (join) {
                        val fixed = if (lastForm == "fina") "medi" else "init"
                        for (i in lastStart until start) glyphs[i].features = glyphs[i].features - TOPOGRAPHICAL + fixed
                    }
                    lastForm = if (join) "fina" else "isol"
                    for (i in start until end) glyphs[i].features = glyphs[i].features - TOPOGRAPHICAL + lastForm!!
                }
            }
            lastStart = start
        }
    }

    private val TOPOGRAPHICAL = setOf("isol", "init", "medi", "fina")

    /** The USE category of [cp], from HarfBuzz's table for Unicode 17. */
    private fun category(cp: Int): Int = table[cp]

    private val table by lazy { PackedRuns(CATEGORIES) }

    /**
     * The USE category of every character that has one, less the blocks the Indic, Khmer and
     * Myanmar shapers own, in runs of one category: the first and last code point in six hex
     * digits each, and the category in two. Dumped from HarfBuzz 14.4's hb_use_get_category,
     * which its gen-use-table.py builds from Unicode 17.
     */
    private const val CATEGORIES =
        "00002D00002D050000300000390100005B00005B3300005D00005D3400007B00007B3300007D00007D340000A00000A0050000AD0000AD100000B200" +
        "00B32F0000D70000D70500034F00034F06000640000640010007CA0007EA010007EB0007F3250007FA0007FA010007FD0007FD250008400008580100" +
        "085900085B20000D81000D8125000D82000D8327000D85000D9601000D9A000DB101000DB3000DBB01000DBD000DBD01000DC0000DC601000DCA000D" +
        "CA35000DCF000DD123000DD2000DD321000DD4000DD422000DD6000DD622000DD8000DD823000DD9000DDE16000DDF000DDF23000DE6000DEF01000D" +
        "F2000DF323000F00000F0101000F04000F0601000F18000F1922000F20000F3301000F35000F352E000F37000F372E000F39000F391F000F3E000F3E" +
        "23000F3F000F3F16000F40000F4701000F49000F6C01000F71000F7120000F72000F7222000F73000F7421000F75000F7522000F76000F7921000F7A" +
        "000F7D22000F7E000F7E25000F80000F8022000F81000F8121000F82000F8325000F84000F8422000F86000F8725000F88000F8C01000F8D000F970B" +
        "000F99000FBC0B000FC6000FC62E0017000017110100171200171221001713001714220017150017152300171F001731010017320017322100173300" +
        "1733220017340017342300174000175101001752001752210017530017532200176000176C0100176E00177001001772001772210017730017732200" +
        "1800001800010018070018070100180A00180A0100180B00180D0600180E00180E1000180F00180F0600182000187801001880001884050018850018" +
        "861F0018870018A8010018A90018A9200018AA0018AA0100190000191E01001920001921210019220019222200192300192423001925001928210019" +
        "2900192B0B0019300019311A001932001932260019330019381A0019390019391900193A00193A2500193B00193B2E00194600196D01001970001974" +
        "010019800019AB010019B00019C7010019C80019C9270019D00019DA01001A00001A1601001A17001A1821001A19001A1916001A1A001A1A23001A1B" +
        "001A1B21001A20001A5401001A55001A551E001A56001A561C001A57001A570B001A58001A5918001A5A001A5A1B001A5B001A5E0B001A60001A6030" +
        "001A61001A6123001A62001A6221001A63001A6423001A65001A6821001A69001A6A22001A6B001A6B21001A6C001A6C22001A6D001A6D23001A6E00" +
        "1A7216001A73001A7321001A74001A7925001A7A001A7A21001A7B001A7C25001A7F001A7F26001A80001A8901001A90001A9901001B00001B022500" +
        "1B03001B0318001B04001B0427001B05001B3301001B34001B341F001B35001B3523001B36001B3721001B38001B3B22001B3C001B3D21001B3E001B" +
        "4116001B42001B4321001B44001B440C001B45001B4C01001B50001B5901001B6B001B7329001B80001B8025001B81001B8118001B82001B8227001B" +
        "83001BA001001BA1001BA30B001BA4001BA421001BA5001BA522001BA6001BA616001BA7001BA723001BA8001BA921001BAA001BAA23001BAB001BAB" +
        "2C001BAC001BAD0B001BAE001BE501001BE6001BE61F001BE7001BE723001BE8001BE921001BEA001BEC23001BED001BED21001BEE001BEE23001BEF" +
        "001BEF21001BF0001BF118001BF2001BF338001C00001C2301001C24001C250B001C26001C2623001C27001C2916001C2A001C2B23001C2C001C2C22" +
        "001C2D001C3318001C34001C3517001C36001C362D001C37001C3720001C40001C4901001C4D001C4F01001CD0001CD225001CD4001CD926001CDA00" +
        "1CDB25001CDC001CDF26001CE0001CE025001CE1001CE127001CE2001CE826001CED001CED26001CF4001CF425001CF5001CF62B001CF7001CF72700" +
        "1CF8001CF925001CFA001CFA05001DFB001DFB2D00200B00200B1000200C00200C0E00200D00200D0600200E00200F100020100020140500202A0020" +
        "2E1000206000206F100020740020742F0020820020842F0020F00020F0250025CC0025CC010027E60027E6330027E70027E7340027E80027E8330027" +
        "E90027E934002D30002D6701002D6F002D6F01002D7F002D7F0C002E22002E2233002E23002E2334002E24002E2433002E25002E253400A80000A801" +
        "0100A80200A8022100A80300A8050100A80600A8060C00A80700A80A0100A80B00A80B2500A80C00A8220100A82300A8242300A82500A8252200A826" +
        "00A8262100A82700A8272300A82C00A82C2200A84000A8730100A88000A8812700A88200A8B30100A8B400A8B41D00A8B500A8C32300A8C400A8C40C" +
        "00A8C500A8C52500A8D000A8D90100A8E000A8F12500A8F200A8F30100A8FE00A8FE0100A8FF00A8FF2100A90000A9250100A92600A92A2100A92B00" +
        "A92D2600A93000A9460100A94700A9492200A94A00A94A2100A94B00A94E2200A94F00A9511800A95200A9521A00A95300A9532300A98000A9812500" +
        "A98200A9821800A98300A9832700A98400A9B20100A9B300A9B31F00A9B400A9B52300A9B600A9B72100A9B800A9B92200A9BA00A9BB1600A9BC00A9" +
        "BC2100A9BD00A9BD1C00A9BE00A9BE1D00A9BF00A9BF1C00A9C000A9C00C00A9D000A9D90100AA0000AA280100AA2900AA292500AA2A00AA2C2100AA" +
        "2D00AA2D2200AA2E00AA2E2100AA2F00AA301600AA3100AA312100AA3200AA322200AA3300AA331D00AA3400AA341E00AA3500AA351B00AA3600AA36" +
        "1C00AA4000AA420100AA4300AA431800AA4400AA4B0100AA4C00AA4C1800AA4D00AA4D1A00AA5000AA590100AA8000AAAF0100AAB000AAB02200AAB1" +
        "00AAB10100AAB200AAB32200AAB400AAB42100AAB500AAB60100AAB700AAB82200AAB900AABD0100AABE00AABE2200AABF00AABF2500AAC000AAC001" +
        "00AAC100AAC12500AAC200AAC20100AAE000AAEA0100AAEB00AAEB1600AAEC00AAEC2200AAED00AAED2100AAEE00AAEE1600AAEF00AAEF2300AAF500" +
        "AAF52700AAF600AAF62C00ABC000ABE20100ABE300ABE42300ABE500ABE52100ABE600ABE72300ABE800ABE82200ABE900ABEA2300ABEC00ABEC2700" +
        "ABED00ABED2200ABF000ABF90100FE0000FE0F0600FEFF00FEFF1000FFF000FFF81001057001057A0101057C01058A0101058C010592010105940105" +
        "95010105970105A1010105A30105B1010105B30105B9010105BB0105BC01010A00010A0001010A01010A0322010A05010A0521010A06010A0622010A" +
        "0C010A0C23010A0D010A0E26010A0F010A0F25010A10010A1301010A15010A1701010A19010A3501010A38010A3A20010A3F010A3F2C010A40010A48" +
        "01010AC0010AC701010AC9010AE401010AE5010AE620010AEB010AEF01010B80010B9101010BA9010BAE01010D00010D2301010D24010D2625010D27" +
        "010D271F010D30010D3901010D4A010D6501010D69010D6D21010D6F010D8501010E80010EA901010EAB010EAC21010EB0010EB101010F30010F4501" +
        "010F46010F5026010F51010F5401010F70010F8101010F82010F8520010FB0010FB001010FB2010FB601010FB8010FBF01010FC1010FC401010FC901" +
        "0FCB010110000110002701100101100125011002011002270110030110042B0110050110370101103801103B2101103C011041220110420110452101" +
        "10460110460C0110520110650401106601106F010110700110702101107101107201011073011074210110750110750101107F01107F0D0110800110" +
        "8125011082011082270110830110AF010110B00110B0230110B10110B1160110B20110B2230110B30110B4220110B50110B6210110B70110B8230110" +
        "B90110B90C0110BA0110BA200110C20110C22201110001110225011103011126010111270111292201112A01112B2101112C01112C1601112D01112D" +
        "2201112E01112F2101113001113022011131011132210111330111332C0111340111341F01113601113F010111440111440101114501114623011147" +
        "01114701011150011172010111730111732001118001118125011182011182270111830111B2010111B30111B3230111B40111B4160111B50111B523" +
        "0111B60111BB220111BC0111BF210111C00111C00C0111C10111C1010111C20111C3120111C90111C92E0111CA0111CA200111CB0111CB210111CC01" +
        "11CC220111CE0111CE160111CF0111CF250111D00111DA010111E10111F4010112000112110101121301122B0101122C01122E2301122F01122F2201" +
        "123001123321011234011234250112350112350C0112360112371F01123E01123E2501123F0112400101124101124122011280011286010112880112" +
        "880101128A01128D0101128F01129D0101129F0112A8010112B00112DE010112DF0112DF250112E00112E0230112E10112E1160112E20112E2230112" +
        "E30112E4220112E50112E8210112E90112E9200112EA0112EA220112F00112F9010113000113032501130501130C0101130F01131001011313011328" +
        "0101132A01133001011332011333010113350113390101133B01133C2001133D01133D0101133E01133F230113400113402101134101134423011347" +
        "0113481601134B01134C1601134D01134D0C0113570113572301135E011361010113620113632301136601136C250113700113742501138001138901" +
        "01138B01138B0101138E01138E010113900113B5010113B70113B7010113B80113B8230113B90113BA210113BB0113C0220113C20113C2160113C501" +
        "13C5160113C70113C8160113C90113C9230113CA0113CA270113CC0113CD270113CE0113CE250113CF0113CF200113D00113D02C0113D10113D11201" +
        "13D20113D2200113E10113E1250113E20113E2260114000114340101143501143523011436011436160114370114372301143801143D2201143E0114" +
        "3F21011440011441230114420114420C011443011444250114450114452701144601144620011447011447010114500114590101145E01145E2D0114" +
        "5F01145F010114600114612B0114810114AF010114B00114B0230114B10114B1160114B20114B2230114B30114B8220114B90114B9160114BA0114BA" +
        "210114BB0114BC160114BD0114BD230114BE0114BE160114BF0114C1250114C20114C20C0114C30114C3200114C40114C4010114D00114D901011580" +
        "0115AE010115AF0115AF230115B00115B0160115B10115B1230115B20115B5220115B80115BB160115BC0115BD250115BE0115BE270115BF0115BF0C" +
        "0115C00115C0200115D80115DB010115DC0115DD2201160001162F01011630011632230116330116382201163901163A2101163B01163C2301163D01" +
        "163D2501163E01163E2701163F01163F0C01164001164021011650011659010116800116AA010116AB0116AB250116AC0116AC270116AD0116AD2101" +
        "16AE0116AE160116AF0116AF230116B00116B1220116B20116B5210116B60116B60C0116B70116B7200116B80116B8010116C00116C9010116D00116" +
        "E30101170001171A0101171D01171D1C01171E01171E1E01171F01171F1B011720011721230117220117232101172401172522011726011726160117" +
        "27011727210117280117282201172901172B2101173001173B010117400117460101180001182B0101182C01182C2301182D01182D1601182E01182E" +
        "2301182F011832220118330118362101183701183725011838011838270118390118390C01183A01183A20011900011906010119090119090101190C" +
        "011913010119150119160101191801192F0101193001193423011935011935160119370119381601193B01193C2501193D01193D2301193E01193E2C" +
        "01193F01193F120119400119401D011941011941120119420119421D01194301194320011950011959010119A00119A7010119AA0119D0010119D101" +
        "19D1230119D20119D2160119D30119D3230119D40119D7220119DA0119DB210119DC0119DD230119DE0119DF270119E00119E00C0119E10119E10101" +
        "19E40119E416011A00011A0001011A01011A0121011A02011A0322011A04011A0921011A0A011A0A22011A0B011A3201011A33011A332E011A34011A" +
        "3422011A35011A3825011A39011A3927011A3A011A3A2B011A3B011A3E0B011A3F011A3F05011A45011A4505011A47011A472C011A50011A5001011A" +
        "51011A5121011A52011A5322011A54011A5621011A57011A5823011A59011A5B22011A5C011A8301011A84011A8912011A8A011A9519011A96011A96" +
        "25011A97011A9727011A98011A981F011A99011A992C011A9D011A9D01011B60011B6021011B61011B6123011B62011B6322011B64011B6421011B65" +
        "011B6523011B66011B6621011B67011B6723011C00011C0801011C0A011C2E01011C2F011C2F23011C30011C3121011C32011C3622011C38011C3B21" +
        "011C3C011C3D25011C3E011C3E27011C3F011C3F0C011C40011C4001011C50011C6C01011C72011C8F01011C92011CA70B011CA9011CAF0B011CB001" +
        "1CB022011CB1011CB116011CB2011CB222011CB3011CB321011CB4011CB423011CB5011CB625011D00011D0601011D08011D0901011D0B011D300101" +
        "1D31011D3521011D36011D3622011D3A011D3A21011D3C011D3D21011D3F011D3F21011D40011D4125011D42011D4220011D43011D4321011D44011D" +
        "4422011D45011D452C011D46011D4612011D47011D471C011D50011D5901011D60011D6501011D67011D6801011D6A011D8901011D8A011D8E23011D" +
        "90011D9121011D93011D9423011D95011D9525011D96011D9627011D97011D972C011DA0011DA901011EE0011EF101011EF2011EF205011EF3011EF3" +
        "21011EF4011EF422011EF5011EF516011EF6011EF623011F00011F0125011F02011F0212011F03011F0327011F04011F1001011F12011F3301011F34" +
        "011F3523011F36011F3721011F38011F3A22011F3E011F3F16011F40011F4021011F41011F4123011F42011F422C011F50011F5901011F5A011F5A1F" +
        "01300001342F3101343001343632013437013437330134380134383401343901343B3201343C01343F31013440013440370134410134463101344701" +
        "3455360134600143FA3101610001611D0101611E0161292101612A01612B1E01612C01612C1D01612D01612D2501612E01612E1C01612F01612F2201" +
        "613001613901016AC0016AC901016B00016B2F01016B30016B3625016D40016D4227016D43016D6A01016D6B016D6C23016D70016D7901016F00016F" +
        "4A01016F4F016F4F20016F51016F8722016F8F016F9226016FE4016FE401018B00018CD501018CFF018CFF0101BC0001BC6A0101BC7001BC7C0101BC" +
        "8001BC880101BC9001BC990101BC9D01BC9E2001D17301D17A1001E10001E12C0101E13001E1362501E13701E13D0101E14001E1490101E14E01E14F" +
        "0101E29001E2AD0101E2AE01E2AE2501E2C001E2EB0101E2EC01E2EF2501E2F001E2F90101E4D001E4EB0101E4EC01E4EF2101E4F001E4F90101E5D0" +
        "01E5ED0101E5EE01E5EF2201E5F001E5FA0101E90001E9430101E94401E94A1F01E94B01E94B0101E95001E959010E00000E00FF100E01000E01EF06" +
        "0E01F00E0FFF10"

    /* ─── Clusters (HarfBuzz's hb-ot-shaper-use-machine.rl) ─────────────── */

    private const val VIRAMA_TERMINATED_CLUSTER = 0
    private const val SAKOT_TERMINATED_CLUSTER = 1
    private const val STANDARD_CLUSTER = 2
    private const val NUMBER_JOINER_TERMINATED_CLUSTER = 3
    private const val NUMERAL_CLUSTER = 4
    private const val SYMBOL_CLUSTER = 5
    private const val HIEROGLYPH_CLUSTER = 6
    private const val BROKEN_CLUSTER = 7
    private const val NON_CLUSTER = 8

    private val CLUSTERS: List<Pair<Syllables.Pattern, Int>> = with(Syllables) {
        fun plus(p: Syllables.Pattern) = seq(p, star(p))
        val h = cat(H, HVM, IS, SK)
        val consonantModifiers = seq(star(cat(CMABV)), star(cat(CMBLW)), star(seq(alt(seq(h, cat(B)), cat(SUB)), star(cat(CMABV)), star(cat(CMBLW)))))
        val medialConsonants = seq(opt(cat(MPRE)), opt(cat(MABV)), opt(cat(MBLW)), opt(cat(MPST)))
        val dependentVowels = alt(seq(star(cat(VPRE)), star(cat(VABV)), star(cat(VBLW)), star(cat(VPST))), cat(H))
        val vowelModifiers = seq(opt(cat(HVM)), star(cat(VMPRE)), star(cat(VMABV)), star(cat(VMBLW)), star(cat(VMPST)))
        val finalConsonants = seq(star(cat(FABV)), star(cat(FBLW)), star(cat(FPST)))
        val finalModifiers = alt(seq(star(cat(FMABV)), star(cat(FMBLW))), opt(cat(FMPST)))
        val start = seq(opt(cat(R, CS)), cat(B, GB))
        val middle = seq(consonantModifiers, medialConsonants, dependentVowels, vowelModifiers, star(seq(cat(SK), cat(B))))
        val complexTail = seq(middle, finalConsonants, finalModifiers)
        val numberJoinerTail = seq(star(seq(cat(HN), cat(N))), cat(HN))
        val numeralTail = plus(seq(cat(HN), cat(N)))
        val symbolTail = alt(seq(plus(cat(SMABV)), star(cat(SMBLW))), plus(cat(SMBLW)))
        val viramaTail = seq(consonantModifiers, cat(IS, RK))
        val sakotTail = seq(middle, cat(SK))
        val tail = alt(complexTail, sakotTail, symbolTail, viramaTail)
        val glyph = seq(cat(G), opt(cat(HR)), opt(cat(HM)), star(cat(SE)))
        val zwnj = opt(cat(ZWNJ))
        listOf(
            seq(start, viramaTail, zwnj) to VIRAMA_TERMINATED_CLUSTER,
            seq(start, sakotTail, zwnj) to SAKOT_TERMINATED_CLUSTER,
            seq(start, complexTail, zwnj) to STANDARD_CLUSTER,
            seq(cat(N), numberJoinerTail, zwnj) to NUMBER_JOINER_TERMINATED_CLUSTER,
            seq(cat(N), opt(numeralTail), zwnj) to NUMERAL_CLUSTER,
            seq(cat(O, GB, SB), opt(tail), zwnj) to SYMBOL_CLUSTER,
            seq(star(cat(SB)), glyph, star(seq(cat(J), star(cat(SB)), opt(glyph))), zwnj) to HIEROGLYPH_CLUSTER,
            cat(FMPST) to NON_CLUSTER,
            seq(opt(cat(R)), alt(tail, numberJoinerTail, numeralTail), zwnj) to BROKEN_CLUSTER,
        )
    }

    /**
     * Splits [glyphs] into clusters as HarfBuzz's find_syllables_use does: the scanner skips a
     * character of category CGJ, such as ZWJ, and a ZWNJ before a mark, and a skipped character
     * joins the cluster it sits in.
     */
    private fun findClusters(glyphs: List<GsubGlyph>) {
        val kept = ArrayList<Int>(glyphs.size)
        for ((i, g) in glyphs.withIndex()) {
            if (category(g) == CGJ) continue
            if (category(g) == ZWNJ) {
                val next = (i + 1 until glyphs.size).firstOrNull { category(glyphs[it]) != CGJ }
                if (next != null && glyphs[next].shaperData and UNICODE_MARK != 0) continue
            }
            kept += i
        }
        val cats = IntArray(kept.size) { category(glyphs[kept[it]]) }
        for (g in glyphs) g.syllable = 0
        var i = 0
        var serial = 0
        while (i < cats.size) {
            var length = 0
            var type = NON_CLUSTER
            for ((pattern, t) in CLUSTERS) {
                val longest = 63 - pattern.ends(cats, i, 1L).countLeadingZeroBits()
                if (longest > length) { length = longest; type = t }
            }
            if (length == 0) { length = 1; type = NON_CLUSTER }
            serial++
            val from = kept[i]
            val to = if (i + length < kept.size) kept[i + length] else glyphs.size
            for (k in from until to) glyphs[k].syllable = (serial shl 4) or type
            i += length
        }
    }

    /* ─── Reordering (HarfBuzz's reorder_syllable_use) ───────────────────── */

    private val REORDERED = Syllables.flags(VIRAMA_TERMINATED_CLUSTER, SAKOT_TERMINATED_CLUSTER, STANDARD_CLUSTER, SYMBOL_CLUSTER, BROKEN_CLUSTER)

    /** The categories a repha stops before: the glyphs that go after the base. */
    private val POST_BASE = Syllables.flags(
        FABV, FBLW, FPST, FMABV, FMBLW, FMPST, MABV, MBLW, MPST, MPRE, VABV, VBLW, VPST, VPRE, VMABV, VMBLW, VMPST, VMPRE,
    )

    private fun isHalant(g: GsubGlyph): Boolean = (category(g) == H || category(g) == HVM || category(g) == IS) && !g.ligated

    private fun reorder(glyphs: MutableList<GsubGlyph>, start: Int, end: Int) {
        if ((REORDERED ushr (glyphs[start].syllable and 0xF)) and 1L == 0L) return
        // A repha moves after the base, before the first glyph that goes after it.
        if (category(glyphs[start]) == R && end - start > 1) {
            for (i in start + 1 until end) {
                val postBase = (POST_BASE ushr category(glyphs[i])) and 1L != 0L || isHalant(glyphs[i])
                if (postBase || i == end - 1) {
                    glyphs.add(if (postBase) i - 1 else i, glyphs.removeAt(start))
                    break
                }
            }
        }
        // A pre-base vowel or modifier moves back to the start, or to just after a halant.
        var j = start
        for (i in start until end) {
            val cat = category(glyphs[i])
            if (isHalant(glyphs[i])) {
                j = i + 1
            } else if ((cat == VPRE || cat == VMPRE) && glyphs[i].component == 0 && j < i) {
                glyphs.add(j, glyphs.removeAt(i))
            }
        }
    }
}
