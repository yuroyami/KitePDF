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
            listOf("rvrn"), listOf("rtla", "rtlm"), listOf("ccmp", "locl"),
            listOf("isol"), listOf("fina"), listOf("fin2"), listOf("fin3"), listOf("medi"), listOf("med2"), listOf("init"),
            listOf("rlig"), listOf("rclt", "calt"), listOf("mset") + ligatures,
        )
        val direction = if (isRightToLeft(script)) listOf("rtla", "rtlm") else listOf("ltra", "ltrm")
        return listOf(listOf("rvrn"), direction + listOf("ccmp", "locl", "rlig", "rclt", "calt") + ligatures)
    }

    /**
     * Shapes the characters [codePoints] of one run in [script], whose glyphs before shaping are
     * [gids]: through [IndicShaper] for the scripts of India, [KhmerShaper] for Khmer,
     * [MyanmarShaper] for Myanmar, [UseShaper] for Sinhala, Tibetan and the other scripts of the
     * Universal Shaping Engine, and [Normalizer] and GSUB in the stages of [stages] for the rest.
     * [forms] are the Arabic joining forms of the run, when it has Arabic. The cluster of each
     * glyph is the index of its first character in [codePoints].
     *
     * In right-to-left text, a character that has a mirror, such as `(`, first becomes that mirror
     * when the font has a glyph for it, and `rtlm` reaches only the characters that did not, as
     * HarfBuzz's hb_ot_mirror_chars does (#321).
     */
    fun shape(
        face: EmbeddedFace, gsub: OpenTypeGsub, script: String, codePoints: IntArray, gids: IntArray,
        forms: Array<ArabicJoining.Form?>?, optionalLigatures: Boolean,
    ): MutableList<GsubGlyph> {
        val glyphs = ArrayList<GsubGlyph>(codePoints.size)
        val ignorables = ArrayList<GsubGlyph>()
        var cps = codePoints
        var gs = gids
        val rtlm = if (isRightToLeft(script)) BooleanArray(codePoints.size) { true } else null
        if (rtlm != null) for ((i, cp) in codePoints.withIndex()) {
            val mirror = BidiMirroring.of(cp)
            val gid = if (mirror != cp) face.gidFor(mirror) else 0
            if (gid == 0) continue
            if (cps === codePoints) { cps = codePoints.copyOf(); gs = gids.copyOf() }
            cps[i] = mirror
            gs[i] = gid
            rtlm[i] = false
        }
        // A glyph of right-to-left text that mirroring left alone takes `rtlm`.
        fun add(glyph: GsubGlyph, cp: Int) {
            if (rtlm?.get(glyph.cluster) == true) glyph.features += "rtlm"
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
            // Thai and Lao split sara am before normalization, as HarfBuzz's Thai shaper does.
            val split = if (script == "thai" || script == "lao ") decomposeSaraAm(cps) else null
            // A word of whole letters that the font has needs no normalization.
            val normal = if (split == null && gs.none { it == 0 } && cps.none { Normalizer.isMark(it) }) null else {
                Normalizer.normalize(
                    split?.codePoints ?: cps, split?.sources ?: IntArray(cps.size) { it },
                    { face.gidFor(it) != 0 }, arabicMarks = arabic,
                )
            }
            for ((j, cp) in (normal?.codePoints ?: cps).withIndex()) {
                val source = normal?.sources?.get(j) ?: j
                val gid = if (cp == cps[source]) gs[source] else face.gidFor(cp)
                // A letter takes the joining form of the character it came from; a mark takes none.
                val form = forms?.get(source)?.takeIf { ArabicJoining.type(cp) != ArabicJoining.Jt.T }
                add(GsubGlyph(gid, source, form?.let { setOf(ArabicJoining.feature(it)) } ?: emptySet(), isMark(cp), ignorable(cp)), cp)
            }
            val manualZwj = if (arabic) ARABIC_MANUAL_ZWJ else emptySet()
            gsub.substitute(glyphs, script, null, stages(script, optionalLigatures), POSITIONAL + "rtlm", manualZwj = manualZwj)
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
