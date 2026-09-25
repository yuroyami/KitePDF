package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.font.GsubGlyph
import io.github.yuroyami.kitepdf.core.font.OpenTypeGsub

/**
 * The Universal Shaping Engine (#317), after the USE shaper of HarfBuzz 14.4, for Sinhala,
 * Tibetan and the other Brahmic and joining scripts of the Basic Multilingual Plane that
 * HarfBuzz shapes with it:
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

    /** The OpenType tags of the scripts HarfBuzz sends to this engine, in the Basic Multilingual Plane. */
    private val SCRIPTS = setOf(
        "sinh", "tibt", "mong", "tglg", "hano", "buhd", "tagb", "limb", "tale", "bugi", "lana", "bali", "sund",
        "batk", "lepc", "sylo", "phag", "saur", "kali", "rjng", "java", "cham", "tavt", "mtei", "tfng", "nko ", "mand",
    )

    /** The scripts among [SCRIPTS] that join, as HarfBuzz's has_arabic_joining lists them. */
    private val JOINING = setOf("mong", "phag", "nko ", "mand")

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
     * Sinhala vowel sequence that would draw like another vowel gets a dotted circle before its
     * last character, when the font has one, and then every character decomposes as far as the
     * font has glyphs for the parts, the marks go into canonical order, and a mark composes with
     * a letter before it when the font has the result.
     */
    fun prepare(script: String, cps: IntArray, hasGlyph: (Int) -> Boolean): Normalizer.Result {
        val codes = ArrayList<Int>(cps.size + 2)
        val sources = ArrayList<Int>(cps.size + 2)
        val circle = script == "sinh" && hasGlyph(0x25CC)
        var i = 0
        while (i < cps.size) {
            val lasts = if (circle && i + 1 < cps.size) CONFUSABLE_SINHALA[cps[i]] else null
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
     * Sinhala vowel sequences that would draw like another vowel, from the USE script development
     * spec as HarfBuzz reads it: each vowel letter with the vowel signs that may not follow it.
     */
    private val CONFUSABLE_SINHALA: Map<Int, Set<Int>> = mapOf(
        0x0D85 to setOf(0x0DCF, 0x0DD0, 0x0DD1),
        0x0D8B to setOf(0x0DDF),
        0x0D8D to setOf(0x0DD8),
        0x0D8F to setOf(0x0DDF),
        0x0D91 to setOf(0x0DCA, 0x0DD9, 0x0DDA, 0x0DDC, 0x0DDD, 0x0DDE),
        0x0D94 to setOf(0x0DDF),
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
        gsub.substitute(glyphs, script, null, listOf(listOf("rvrn"), listOf(direction, if (direction == "rtla") "rtlm" else "ltrm")))
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
    private fun category(cp: Int): Int {
        val table = ranges
        var lo = 0
        var hi = table.size / 3 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                cp < table[mid * 3] -> hi = mid - 1
                cp > table[mid * 3 + 1] -> lo = mid + 1
                else -> return table[mid * 3 + 2]
            }
        }
        return O
    }

    private val ranges: IntArray by lazy {
        IntArray(CATEGORIES.length / 10 * 3) { k ->
            val record = k / 3 * 10
            when (k % 3) {
                0 -> CATEGORIES.substring(record, record + 4).toInt(16)
                1 -> CATEGORIES.substring(record + 4, record + 8).toInt(16)
                else -> CATEGORIES.substring(record + 8, record + 10).toInt(16)
            }
        }
    }

    /**
     * The USE category of every character of the Basic Multilingual Plane that has one, less the
     * blocks the Indic, Khmer and Myanmar shapers own, in runs of one category: the first and last
     * character in four hex digits each, and the category in two. Dumped from HarfBuzz 14.4's
     * hb_use_get_category, which its gen-use-table.py builds from Unicode 17.
     */
    private const val CATEGORIES =
        "002D002D050030003901005B005B33005D005D34007B007B33007D007D3400A000A00500AD00AD1000B200B32F00D700D705034F034F060640064001" +
        "07CA07EA0107EB07F32507FA07FA0107FD07FD2508400858010859085B200D810D81250D820D83270D850D96010D9A0DB1010DB30DBB010DBD0DBD01" +
        "0DC00DC6010DCA0DCA350DCF0DD1230DD20DD3210DD40DD4220DD60DD6220DD80DD8230DD90DDE160DDF0DDF230DE60DEF010DF20DF3230F000F0101" +
        "0F040F06010F180F19220F200F33010F350F352E0F370F372E0F390F391F0F3E0F3E230F3F0F3F160F400F47010F490F6C010F710F71200F720F7222" +
        "0F730F74210F750F75220F760F79210F7A0F7D220F7E0F7E250F800F80220F810F81210F820F83250F840F84220F860F87250F880F8C010F8D0F970B" +
        "0F990FBC0B0FC60FC62E1700171101171217122117131714221715171523171F17310117321732211733173322173417342317401751011752175221" +
        "17531753221760176C01176E1770011772177221177317732218001800011807180701180A180A01180B180D06180E180E10180F180F061820187801" +
        "1880188405188518861F188718A80118A918A92018AA18AA011900191E0119201921211922192222192319242319251928211929192B0B193019311A" +
        "1932193226193319381A1939193919193A193A25193B193B2E1946196D011970197401198019AB0119B019C70119C819C92719D019DA011A001A1601" +
        "1A171A18211A191A19161A1A1A1A231A1B1A1B211A201A54011A551A551E1A561A561C1A571A570B1A581A59181A5A1A5A1B1A5B1A5E0B1A601A6030" +
        "1A611A61231A621A62211A631A64231A651A68211A691A6A221A6B1A6B211A6C1A6C221A6D1A6D231A6E1A72161A731A73211A741A79251A7A1A7A21" +
        "1A7B1A7C251A7F1A7F261A801A89011A901A99011B001B02251B031B03181B041B04271B051B33011B341B341F1B351B35231B361B37211B381B3B22" +
        "1B3C1B3D211B3E1B41161B421B43211B441B440C1B451B4C011B501B59011B6B1B73291B801B80251B811B81181B821B82271B831BA0011BA11BA30B" +
        "1BA41BA4211BA51BA5221BA61BA6161BA71BA7231BA81BA9211BAA1BAA231BAB1BAB2C1BAC1BAD0B1BAE1BE5011BE61BE61F1BE71BE7231BE81BE921" +
        "1BEA1BEC231BED1BED211BEE1BEE231BEF1BEF211BF01BF1181BF21BF3381C001C23011C241C250B1C261C26231C271C29161C2A1C2B231C2C1C2C22" +
        "1C2D1C33181C341C35171C361C362D1C371C37201C401C49011C4D1C4F011CD01CD2251CD41CD9261CDA1CDB251CDC1CDF261CE01CE0251CE11CE127" +
        "1CE21CE8261CED1CED261CF41CF4251CF51CF62B1CF71CF7271CF81CF9251CFA1CFA051DFB1DFB2D200B200B10200C200C0E200D200D06200E200F10" +
        "2010201405202A202E102060206F10207420742F208220842F20F020F02525CC25CC0127E627E63327E727E73427E827E83327E927E9342D302D6701" +
        "2D6F2D6F012D7F2D7F0C2E222E22332E232E23342E242E24332E252E2534A800A80101A802A80221A803A80501A806A8060CA807A80A01A80BA80B25" +
        "A80CA82201A823A82423A825A82522A826A82621A827A82723A82CA82C22A840A87301A880A88127A882A8B301A8B4A8B41DA8B5A8C323A8C4A8C40C" +
        "A8C5A8C525A8D0A8D901A8E0A8F125A8F2A8F301A8FEA8FE01A8FFA8FF21A900A92501A926A92A21A92BA92D26A930A94601A947A94922A94AA94A21" +
        "A94BA94E22A94FA95118A952A9521AA953A95323A980A98125A982A98218A983A98327A984A9B201A9B3A9B31FA9B4A9B523A9B6A9B721A9B8A9B922" +
        "A9BAA9BB16A9BCA9BC21A9BDA9BD1CA9BEA9BE1DA9BFA9BF1CA9C0A9C00CA9D0A9D901AA00AA2801AA29AA2925AA2AAA2C21AA2DAA2D22AA2EAA2E21" +
        "AA2FAA3016AA31AA3121AA32AA3222AA33AA331DAA34AA341EAA35AA351BAA36AA361CAA40AA4201AA43AA4318AA44AA4B01AA4CAA4C18AA4DAA4D1A" +
        "AA50AA5901AA80AAAF01AAB0AAB022AAB1AAB101AAB2AAB322AAB4AAB421AAB5AAB601AAB7AAB822AAB9AABD01AABEAABE22AABFAABF25AAC0AAC001" +
        "AAC1AAC125AAC2AAC201AAE0AAEA01AAEBAAEB16AAECAAEC22AAEDAAED21AAEEAAEE16AAEFAAEF23AAF5AAF527AAF6AAF62CABC0ABE201ABE3ABE423" +
        "ABE5ABE521ABE6ABE723ABE8ABE822ABE9ABEA23ABECABEC27ABEDABED22ABF0ABF901FE00FE0F06FEFFFEFF10FFF0FFF810"

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
