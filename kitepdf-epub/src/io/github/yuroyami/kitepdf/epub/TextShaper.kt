package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.font.GsubGlyph
import io.github.yuroyami.kitepdf.core.font.OpenTypeGsub

/**
 * What the layout asks of GSUB for a word (#211): the OpenType script of its text, and the
 * features HarfBuzz applies by default for that script, stage by stage. A stage applies all
 * of its lookups, in LookupList order, before the next stage starts.
 */
internal object TextShaper {

    /** The joining forms of Arabic, which reach only the letters that take them. */
    val POSITIONAL: Set<String> = setOf("isol", "fina", "fin2", "fin3", "medi", "med2", "init")

    /**
     * The OpenType script of the first character in [cps] that belongs to one. A script with
     * two tags, such as Devanagari, takes the tag [gsub] has; text of no script is `DFLT`.
     */
    fun script(cps: IntArray, gsub: OpenTypeGsub): String {
        for (cp in cps) {
            val tags = scriptTags(cp) ?: continue
            return tags.firstOrNull { gsub.hasScript(it) } ?: tags.first()
        }
        return "DFLT"
    }

    /** True for a script that runs from right to left. */
    fun isRightToLeft(script: String): Boolean = script in RTL

    /**
     * The GSUB stages of [script], after HarfBuzz's plan: the Arabic shaper puts each joining
     * form in a stage of its own, and every other script applies its features in one stage.
     * Without [optionalLigatures], as CSS asks of letter-spaced text, `liga` and `clig` stay off.
     */
    fun stages(script: String, optionalLigatures: Boolean): List<List<String>> {
        val ligatures = if (optionalLigatures) listOf("liga", "clig") else emptyList()
        if (script == "arab" || script == "syrc") return listOf(
            listOf("rvrn"), listOf("rtla") + MIRRORED, listOf("ccmp", "locl"),
            listOf("isol"), listOf("fina"), listOf("fin2"), listOf("fin3"), listOf("medi"), listOf("med2"), listOf("init"),
            listOf("rlig"), listOf("rclt", "calt"), listOf("mset") + ligatures,
        )
        val direction = if (isRightToLeft(script)) "rtla" else "ltra"
        val jamo = if (script == "hang") JAMO else emptyList()
        return listOf(listOf("rvrn"), listOf(direction) + MIRRORED + listOf("ccmp", "locl") + jamo + listOf("rlig", "rclt", "calt") + ligatures)
    }

    /** The features of the leading, vowel and trailing jamo, each of which reaches only the glyphs that list it (#635). */
    val JAMO: List<String> = listOf("ljmo", "vjmo", "tjmo")

    /** The feature of a conjoining jamo that stays apart: `ljmo`, `vjmo` or `tjmo`, or null for any other character. */
    fun jamoFeature(cp: Int): String? = when (cp) {
        in 0x1100..0x115F, in 0xA960..0xA97C -> "ljmo"
        in 0x1160..0x11A7, in 0xD7B0..0xD7C6 -> "vjmo"
        in 0x11A8..0x11FF, in 0xD7CB..0xD7FB -> "tjmo"
        else -> null
    }

    /**
     * Joins conjoining jamo into precomposed Hangul syllables by the arithmetic of the Unicode
     * Standard, 3.12, where [hasGlyph] says the font has the syllable, as HarfBuzz's Hangul
     * shaper does: a leading consonant and a vowel make an LV syllable, a trailing consonant
     * after it makes an LVT one, and so does a trailing consonant after a precomposed LV
     * syllable. The characters that join share the source of the first. Null when nothing joins.
     */
    fun composeHangul(cps: IntArray, hasGlyph: (Int) -> Boolean): Normalizer.Result? {
        fun isL(c: Int) = c in L_BASE until L_BASE + L_COUNT
        fun isV(c: Int) = c in V_BASE until V_BASE + V_COUNT
        fun isT(c: Int) = c in T_BASE + 1 until T_BASE + T_COUNT
        fun isLV(c: Int) = c in S_BASE until S_BASE + S_COUNT && (c - S_BASE) % T_COUNT == 0
        val codes = ArrayList<Int>(cps.size)
        val srcs = ArrayList<Int>(cps.size)
        var changed = false
        var i = 0
        while (i < cps.size) {
            val c = cps[i]
            if (isL(c) && i + 1 < cps.size && isV(cps[i + 1])) {
                val lv = S_BASE + ((c - L_BASE) * V_COUNT + (cps[i + 1] - V_BASE)) * T_COUNT
                val t = cps.getOrNull(i + 2)
                if (t != null && isT(t) && hasGlyph(lv + (t - T_BASE))) {
                    codes += lv + (t - T_BASE); srcs += i; i += 3; changed = true; continue
                }
                if (hasGlyph(lv)) {
                    codes += lv; srcs += i; i += 2; changed = true; continue
                }
            } else if (isLV(c) && i + 1 < cps.size && isT(cps[i + 1]) && hasGlyph(c + (cps[i + 1] - T_BASE))) {
                codes += c + (cps[i + 1] - T_BASE); srcs += i; i += 2; changed = true; continue
            }
            codes += c; srcs += i; i++
        }
        return if (changed) Normalizer.Result(codes.toIntArray(), srcs.toIntArray()) else null
    }

    private const val S_BASE = 0xAC00
    private const val L_BASE = 0x1100
    private const val V_BASE = 0x1161
    private const val T_BASE = 0x11A7
    private const val L_COUNT = 19
    private const val V_COUNT = 21
    private const val T_COUNT = 28
    private const val S_COUNT = L_COUNT * V_COUNT * T_COUNT

    /** The mirrored forms, each of which reaches only the glyphs that list it. */
    val MIRRORED: List<String> = listOf("ltrm", "rtlm")

    /**
     * Shapes the characters [codePoints] of one run in [script], whose glyphs before shaping are
     * [gids]: through [IndicShaper] for the scripts of India, [KhmerShaper] for Khmer,
     * [MyanmarShaper] for Myanmar, [UseShaper] for Sinhala, Tibetan and the other scripts of the
     * Universal Shaping Engine, and [Normalizer] and GSUB in the stages of [stages] for the rest.
     * [forms] are the Arabic joining forms of the run, when it has Arabic. The cluster of each
     * glyph is the index of its first character in [codePoints].
     *
     * [rtl] tells which characters sit at an odd bidi level, as the layout resolves them. Without
     * it, every character runs in the direction of [script], as a word does in `hb-shape`. A
     * right-to-left character that has a mirror, such as `(`, first becomes that mirror when the
     * font has a glyph for it, and the other right-to-left characters take `rtlm`, as HarfBuzz's
     * hb_ot_mirror_chars does in a right-to-left run. The left-to-right characters take `ltrm`
     * (#321).
     */
    fun shape(
        face: EmbeddedFace, gsub: OpenTypeGsub, script: String, codePoints: IntArray, gids: IntArray,
        forms: Array<ArabicJoining.Form?>?, optionalLigatures: Boolean, rtl: BooleanArray? = null,
    ): MutableList<GsubGlyph> {
        val glyphs = ArrayList<GsubGlyph>(codePoints.size)
        val ignorables = ArrayList<GsubGlyph>()
        var cps = codePoints
        var gs = gids
        // The mirrored form of each character: its mirror character, or `rtlm` or `ltrm`.
        val mirrored = arrayOfNulls<String>(codePoints.size)
        for ((i, cp) in codePoints.withIndex()) {
            if (!(rtl?.get(i) ?: isRightToLeft(script))) { mirrored[i] = "ltrm"; continue }
            val mirror = BidiMirroring.of(cp)
            val gid = if (mirror != cp) face.gidFor(mirror) else 0
            if (gid == 0) { mirrored[i] = "rtlm"; continue }
            if (cps === codePoints) { cps = codePoints.copyOf(); gs = gids.copyOf() }
            cps[i] = mirror
            gs[i] = gid
        }
        // A character followed by a variation selector takes the glyph the font maps the pair to,
        // before any substitution, as HarfBuzz's hb_font_get_variation_glyph runs (cmap format 14;
        // #914). A pair the font does not list keeps the base glyph, and the selector stays invisible.
        for (i in 0 until cps.size - 1) {
            if (!isVariationSelector(cps[i + 1])) continue
            val variant = face.variationGidFor(cps[i], cps[i + 1])
            if (variant == 0 || variant == gs[i]) continue
            if (gs === gids) gs = gids.copyOf()
            gs[i] = variant
        }
        fun add(glyph: GsubGlyph, cp: Int) {
            mirrored[glyph.cluster]?.let { glyph.features += it }
            glyphs += glyph
            if (isDefaultIgnorable(cp)) ignorables += glyph
        }
        // The glyphs of characters that a shaper of its own prepared, each with its source.
        fun addPrepared(prepared: Normalizer.Result) {
            for ((j, cp) in prepared.codePoints.withIndex()) {
                val source = prepared.sources[j]
                val gid = if (cp == cps[source]) gs[source] else face.gidFor(cp)
                add(GsubGlyph(gid, source, isMark = isMark(cp), ignorable = ignorable(cp)), cp)
            }
        }
        if (IndicShaper.handles(script, gsub)) {
            val prepared = IndicShaper.prepare(script, cps) { face.gidFor(it) != 0 }
            addPrepared(prepared)
            IndicShaper.shape(gsub, script, glyphs, prepared.codePoints, face::gidFor, optionalLigatures)
        } else if (KhmerShaper.handles(script)) {
            val prepared = KhmerShaper.prepare(cps) { face.gidFor(it) != 0 }
            addPrepared(prepared)
            KhmerShaper.shape(gsub, script, glyphs, prepared.codePoints, face::gidFor, optionalLigatures)
        } else if (MyanmarShaper.handles(script, gsub)) {
            val prepared = MyanmarShaper.prepare(cps) { face.gidFor(it) != 0 }
            addPrepared(prepared)
            MyanmarShaper.shape(gsub, script, glyphs, prepared.codePoints, face::gidFor, optionalLigatures)
        } else if (UseShaper.handles(script, gsub)) {
            val prepared = UseShaper.prepare(script, cps) { face.gidFor(it) != 0 }
            addPrepared(prepared)
            UseShaper.shape(gsub, script, glyphs, prepared.codePoints, face::gidFor, optionalLigatures)
        } else {
            val arabic = script == "arab" || script == "syrc"
            val hangul = script == "hang"
            // Thai and Lao split sara am before normalization, as HarfBuzz's Thai shaper does, and
            // Korean jamo join into the syllable the font has a glyph for, as its Hangul shaper does (#635).
            val split = when {
                script == "thai" || script == "lao " -> decomposeSaraAm(cps)
                hangul -> composeHangul(cps) { face.gidFor(it) != 0 }
                else -> null
            }
            // A word of whole letters that the font has needs no normalization.
            val normal = if (split == null && gs.none { it == 0 } && cps.none { Normalizer.isMark(it) }) null else {
                Normalizer.normalize(
                    split?.codePoints ?: cps, split?.sources ?: IntArray(cps.size) { it },
                    { face.gidFor(it) != 0 }, arabicMarks = arabic,
                )
            }
            // The characters and joining forms of the glyphs, for the fallback of a font with no joining features.
            val glyphCodes = ArrayList<Int>()
            val glyphForms = ArrayList<ArabicJoining.Form?>()
            for ((j, cp) in (normal?.codePoints ?: cps).withIndex()) {
                val source = normal?.sources?.get(j) ?: j
                val gid = if (cp == cps[source]) gs[source] else face.gidFor(cp)
                // A letter takes the joining form of the character it came from; a mark takes none.
                val form = forms?.get(source)?.takeIf { ArabicJoining.type(cp) != ArabicJoining.Jt.T }
                // A jamo that stays apart takes its own feature, for a font that joins them itself.
                val jamo = if (hangul) jamoFeature(cp) else null
                val features = form?.let { setOf(ArabicJoining.feature(it)) } ?: jamo?.let { setOf(it) } ?: emptySet()
                add(GsubGlyph(gid, source, features, isMark(cp), ignorable(cp)), cp)
                glyphCodes += cp
                glyphForms += form
            }
            // A font with no init, medi or fina takes the presentation forms its cmap maps, as HarfBuzz falls back (#636).
            if (script == "arab" && forms != null && !gsub.hasArabicJoining) ArabicFallback.apply(glyphs, glyphCodes, glyphForms, face::gidFor)
            val manualZwj = if (arabic) ARABIC_MANUAL_ZWJ else emptySet()
            gsub.substitute(glyphs, script, null, stages(script, optionalLigatures), POSITIONAL + MIRRORED + JAMO, manualZwj = manualZwj)
        }
        hideIgnorables(face, glyphs, ignorables)
        return glyphs
    }

    /**
     * HarfBuzz's preprocessing of Thai and Lao (#317): sara am decomposes into nikhahit and
     * sara aa, and the nikhahit moves back over the above-base marks before it, as Uniscribe
     * orders them. The characters that move share the source of the first of them. Null when
     * the run has no sara am.
     */
    fun decomposeSaraAm(cps: IntArray): Normalizer.Result? {
        if (cps.none { it == 0x0E33 || it == 0x0EB3 }) return null
        val codes = ArrayList<Int>(cps.size + 2)
        val srcs = ArrayList<Int>(cps.size + 2)
        for ((k, u) in cps.withIndex()) {
            if (u != 0x0E33 && u != 0x0EB3) { codes += u; srcs += k; continue }
            codes += u - 0x0E33 + 0x0E4D
            codes += u - 1
            srcs += k
            srcs += k
            val end = codes.size
            var start = end - 2
            while (start > 0 && isAboveBaseMark(codes[start - 1])) start--
            if (start + 2 < end) {
                codes.add(start, codes.removeAt(end - 2))
                srcs.add(start, srcs.removeAt(end - 2))
            }
            // The letter before the marks joins their cluster.
            val from = maxOf(start - 1, 0)
            val first = srcs.subList(from, end).min()
            for (j in from until end) srcs[j] = first
        }
        return Normalizer.Result(codes.toIntArray(), srcs.toIntArray())
    }

    /** The Thai and Lao marks above the base that a nikhahit from sara am moves past. */
    private fun isAboveBaseMark(cp: Int): Boolean {
        val u = cp and 0x0080.inv()
        return u == 0x0E31 || u in 0x0E34..0x0E37 || u == 0x0E3B || u in 0x0E47..0x0E4E
    }

    /** The ligating features of Arabic, for which HarfBuzz lets a ZWJ break a ligature as a ZWNJ does. */
    private val ARABIC_MANUAL_ZWJ = setOf("rlig", "rclt", "calt")

    /** Marks a glyph that draws nothing and takes no advance, as a hidden default ignorable. */
    const val INVISIBLE: Int = 1 shl 30

    /**
     * A default ignorable that no lookup substituted, such as a ZWJ, becomes the space glyph
     * with no advance, as HarfBuzz hides it. A font may draw a placeholder for its own glyph.
     */
    private fun hideIgnorables(face: EmbeddedFace, glyphs: List<GsubGlyph>, ignorables: List<GsubGlyph>) {
        if (ignorables.isEmpty()) return
        val space = face.gidFor(' '.code)
        for (g in ignorables) {
            if (g.substituted || glyphs.none { it === g }) continue
            if (space != 0) g.gid = space
            g.shaperData = g.shaperData or INVISIBLE
        }
    }

    /**
     * True for a character HarfBuzz treats as default ignorable, which draws nothing. HarfBuzz
     * leaves out the Hangul fillers, which fonts draw as spacing glyphs.
     */
    /** The variation selectors, U+FE00 to U+FE0F and U+E0100 to U+E01EF, and the Mongolian ones. */
    fun isVariationSelector(cp: Int): Boolean = cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF || cp in 0x180B..0x180D || cp == 0x180F

    fun isDefaultIgnorable(cp: Int): Boolean =
        cp == 0x00AD || cp == 0x034F || cp == 0x061C || cp in 0x17B4..0x17B5 || cp in 0x180B..0x180E ||
            cp in 0x200B..0x200F || cp in 0x202A..0x202E || cp in 0x2060..0x206F ||
            cp in 0xFE00..0xFE0F || cp == 0xFEFF || cp in 0xFFF0..0xFFF8 ||
            cp in 0x1D173..0x1D17A || cp in 0xE0000..0xE0FFF

    /**
     * How a lookup passes over [cp], or null for a character it does not pass over. CGJ, the
     * Mongolian free variation selectors and the tag characters stay visible to GSUB, as
     * HarfBuzz keeps them.
     */
    fun ignorable(cp: Int): GsubGlyph.Ignorable? = when {
        cp == 0x200C -> GsubGlyph.Ignorable.ZWNJ
        cp == 0x200D -> GsubGlyph.Ignorable.ZWJ
        cp == 0x034F || cp in 0x180B..0x180D || cp in 0xE0020..0xE007F -> null
        isDefaultIgnorable(cp) -> GsubGlyph.Ignorable.OTHER
        else -> null
    }


    /**
     * True for a non-spacing mark that is not default ignorable, the glyph class HarfBuzz gives
     * a mark when the font has no GDEF classes.
     */
    fun isMark(cp: Int): Boolean = GeneralCategory.of(cp) == CharCategory.NON_SPACING_MARK && !isDefaultIgnorable(cp)

    /** The scripts that HarfBuzz runs from right to left, by their OpenType tags. */
    private val RTL = setOf(
        "adlm", "arab", "armi", "avst", "chrs", "cprt", "elym", "gara", "hatr", "hebr", "khar", "lydi", "mand", "mani",
        "mend", "merc", "mero", "narb", "nbat", "nko ", "orkh", "ougr", "palm", "phli", "phlp", "phnx", "prti", "rohg",
        "samr", "sarb", "sidt", "sogd", "sogo", "syrc", "thaa", "yezi",
    )

    /**
     * The OpenType tags of the script of [cp], the newer tag first, or null for a Common or
     * Inherited character (#319). HarfBuzz's rule: the ISO 15924 code in lower case, with a
     * newer tag for the scripts of India and Myanmar, and a few older exceptions.
     */
    private fun scriptTags(cp: Int): List<String>? {
        val code = UnicodeScript.of(cp) ?: return null
        return NEW_TAGS[code] ?: listOf(OLD_TAGS[code] ?: code.lowercase())
    }

    /** The scripts that HarfBuzz gives a newer tag first, as OpenType's second Indic specification names them. */
    private val NEW_TAGS = mapOf(
        "Beng" to listOf("bng2", "beng"), "Deva" to listOf("dev2", "deva"), "Gujr" to listOf("gjr2", "gujr"),
        "Guru" to listOf("gur2", "guru"), "Knda" to listOf("knd2", "knda"), "Mlym" to listOf("mlm2", "mlym"),
        "Orya" to listOf("ory2", "orya"), "Taml" to listOf("tml2", "taml"), "Telu" to listOf("tel2", "telu"),
        "Mymr" to listOf("mym2", "mymr"),
    )

    /** The scripts whose tag is not their ISO 15924 code in lower case. */
    private val OLD_TAGS = mapOf("Hira" to "kana", "Laoo" to "lao ", "Nkoo" to "nko ", "Vaii" to "vai ", "Yiii" to "yi  ")
}
