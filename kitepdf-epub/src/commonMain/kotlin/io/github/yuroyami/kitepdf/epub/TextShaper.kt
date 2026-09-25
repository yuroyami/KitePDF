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
            listOf("rvrn"), listOf("rtla"), listOf("ccmp", "locl"),
            listOf("isol"), listOf("fina"), listOf("fin2"), listOf("fin3"), listOf("medi"), listOf("med2"), listOf("init"),
            listOf("rlig"), listOf("rclt", "calt"), listOf("mset") + ligatures,
        )
        val direction = if (isRightToLeft(script)) "rtla" else "ltra"
        return listOf(listOf("rvrn"), listOf(direction, "ccmp", "locl", "rlig", "rclt", "calt") + ligatures)
    }

    /**
     * Shapes the characters [codePoints] of one run in [script], whose glyphs before shaping are
     * [gids]: through [IndicShaper] for the scripts of India, [KhmerShaper] for Khmer,
     * [MyanmarShaper] for Myanmar, [UseShaper] for Sinhala, Tibetan and the other scripts of the
     * Universal Shaping Engine, and [Normalizer] and GSUB in the stages of [stages] for the rest. [forms] are the Arabic joining forms of the run,
     * when it has Arabic. The cluster of each glyph is the index of its first character in
     * [codePoints].
     */
    fun shape(
        face: EmbeddedFace, gsub: OpenTypeGsub, script: String, codePoints: IntArray, gids: IntArray,
        forms: Array<ArabicJoining.Form?>?, optionalLigatures: Boolean,
    ): MutableList<GsubGlyph> {
        val glyphs = ArrayList<GsubGlyph>(codePoints.size)
        val ignorables = ArrayList<GsubGlyph>()
        // The glyphs of characters that a shaper of its own prepared, each with its source.
        fun addPrepared(prepared: Normalizer.Result) {
            for ((j, cp) in prepared.codePoints.withIndex()) {
                val source = prepared.sources[j]
                val gid = if (cp == codePoints[source]) gids[source] else face.gidFor(cp)
                glyphs += GsubGlyph(gid, source, isMark = isMark(cp), ignorable = ignorable(cp))
                if (isDefaultIgnorable(cp)) ignorables += glyphs.last()
            }
        }
        if (IndicShaper.handles(script, gsub)) {
            val prepared = IndicShaper.prepare(script, codePoints) { face.gidFor(it) != 0 }
            addPrepared(prepared)
            IndicShaper.shape(gsub, script, glyphs, prepared.codePoints, face::gidFor, optionalLigatures)
        } else if (KhmerShaper.handles(script)) {
            val prepared = KhmerShaper.prepare(codePoints) { face.gidFor(it) != 0 }
            addPrepared(prepared)
            KhmerShaper.shape(gsub, script, glyphs, prepared.codePoints, face::gidFor, optionalLigatures)
        } else if (MyanmarShaper.handles(script, gsub)) {
            val prepared = MyanmarShaper.prepare(codePoints) { face.gidFor(it) != 0 }
            addPrepared(prepared)
            MyanmarShaper.shape(gsub, script, glyphs, prepared.codePoints, face::gidFor, optionalLigatures)
        } else if (UseShaper.handles(script, gsub)) {
            val prepared = UseShaper.prepare(script, codePoints) { face.gidFor(it) != 0 }
            addPrepared(prepared)
            UseShaper.shape(gsub, script, glyphs, prepared.codePoints, face::gidFor, optionalLigatures)
        } else {
            val arabic = script == "arab" || script == "syrc"
            // Thai and Lao split sara am before normalization, as HarfBuzz's Thai shaper does.
            val split = if (script == "thai" || script == "lao ") decomposeSaraAm(codePoints) else null
            // A word of whole letters that the font has needs no normalization.
            val normal = if (split == null && gids.none { it == 0 } && codePoints.none { Normalizer.isMark(it) }) null else {
                Normalizer.normalize(
                    split?.codePoints ?: codePoints, split?.sources ?: IntArray(codePoints.size) { it },
                    { face.gidFor(it) != 0 }, arabicMarks = arabic,
                )
            }
            val cps = normal?.codePoints ?: codePoints
            for ((j, cp) in cps.withIndex()) {
                val source = normal?.sources?.get(j) ?: j
                val gid = if (cp == codePoints[source]) gids[source] else face.gidFor(cp)
                // A letter takes the joining form of the character it came from; a mark takes none.
                val form = forms?.get(source)?.takeIf { ArabicJoining.type(cp) != ArabicJoining.Jt.T }
                glyphs += GsubGlyph(gid, source, form?.let { setOf(ArabicJoining.feature(it)) } ?: emptySet(), isMark(cp), ignorable(cp))
                if (isDefaultIgnorable(cp)) ignorables += glyphs.last()
            }
            val manualZwj = if (arabic) ARABIC_MANUAL_ZWJ else emptySet()
            gsub.substitute(glyphs, script, null, stages(script, optionalLigatures), POSITIONAL, manualZwj = manualZwj)
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
            cp in 0xFE00..0xFE0F || cp == 0xFEFF || cp in 0xFFF0..0xFFF8

    /**
     * How a lookup passes over [cp], or null for a character it does not pass over. CGJ and
     * the Mongolian free variation selectors stay visible to GSUB, as HarfBuzz keeps them.
     */
    fun ignorable(cp: Int): GsubGlyph.Ignorable? = when {
        cp == 0x200C -> GsubGlyph.Ignorable.ZWNJ
        cp == 0x200D -> GsubGlyph.Ignorable.ZWJ
        cp == 0x034F || cp in 0x180B..0x180D -> null
        isDefaultIgnorable(cp) -> GsubGlyph.Ignorable.OTHER
        else -> null
    }


    /**
     * True for a non-spacing mark that is not default ignorable, the glyph class HarfBuzz gives
     * a mark when the font has no GDEF classes.
     */
    fun isMark(cp: Int): Boolean = cp.toChar().category == CharCategory.NON_SPACING_MARK && !isDefaultIgnorable(cp)

    private val RTL = setOf("arab", "hebr", "syrc", "thaa", "nko ", "mand")

    /** The OpenType tags of the script of [cp], the newer tag first, or null for common characters. */
    private fun scriptTags(cp: Int): List<String>? = when {
        isShared(cp) -> null
        cp in 0x41..0x5A || cp in 0x61..0x7A || cp in 0xC0..0x24F || cp in 0x1E00..0x1EFF -> LATN
        cp in 0x370..0x3FF || cp in 0x1F00..0x1FFF -> listOf("grek")
        cp in 0x400..0x52F -> listOf("cyrl")
        cp in 0x530..0x58F -> listOf("armn")
        cp in 0x590..0x5FF || cp in 0xFB1D..0xFB4F -> listOf("hebr")
        cp in 0x600..0x6FF || cp in 0x750..0x77F || cp in 0x870..0x8FF || cp in 0xFB50..0xFDFF || cp in 0xFE70..0xFEFF -> listOf("arab")
        cp in 0x700..0x74F || cp in 0x860..0x86F -> listOf("syrc")
        cp in 0x780..0x7BF -> listOf("thaa")
        cp in 0x7C0..0x7FF -> listOf("nko ")
        cp in 0x840..0x85F -> listOf("mand")
        cp in 0x900..0x97F || cp in 0xA8E0..0xA8FF -> listOf("dev2", "deva")
        cp in 0x980..0x9FF -> listOf("bng2", "beng")
        cp in 0xA00..0xA7F -> listOf("gur2", "guru")
        cp in 0xA80..0xAFF -> listOf("gjr2", "gujr")
        cp in 0xB00..0xB7F -> listOf("ory2", "orya")
        cp in 0xB80..0xBFF -> listOf("tml2", "taml")
        cp in 0xC00..0xC7F -> listOf("tel2", "telu")
        cp in 0xC80..0xCFF -> listOf("knd2", "knda")
        cp in 0xD00..0xD7F -> listOf("mlm2", "mlym")
        cp in 0xD80..0xDFF -> listOf("sinh")
        cp in 0xE00..0xE7F -> listOf("thai")
        cp in 0xE80..0xEFF -> listOf("lao ")
        cp in 0xF00..0xFFF -> listOf("tibt")
        cp in 0x1700..0x171F -> listOf("tglg")
        cp in 0x1720..0x173F -> listOf("hano")
        cp in 0x1740..0x175F -> listOf("buhd")
        cp in 0x1760..0x177F -> listOf("tagb")
        cp in 0x1800..0x18AF -> listOf("mong")
        cp in 0x1900..0x194F -> listOf("limb")
        cp in 0x1950..0x197F -> listOf("tale")
        cp in 0x1A00..0x1A1F -> listOf("bugi")
        cp in 0x1A20..0x1AAF -> listOf("lana")
        cp in 0x1B00..0x1B7F -> listOf("bali")
        cp in 0x1B80..0x1BBF || cp in 0x1CC0..0x1CCF -> listOf("sund")
        cp in 0x1BC0..0x1BFF -> listOf("batk")
        cp in 0x1C00..0x1C4F -> listOf("lepc")
        cp in 0x2D30..0x2D7F -> listOf("tfng")
        cp in 0xA800..0xA82F -> listOf("sylo")
        cp in 0xA840..0xA87F -> listOf("phag")
        cp in 0xA880..0xA8DF -> listOf("saur")
        cp in 0xA900..0xA92F -> listOf("kali")
        cp in 0xA930..0xA95F -> listOf("rjng")
        cp in 0xA980..0xA9DF -> listOf("java")
        cp in 0xAA00..0xAA5F -> listOf("cham")
        cp in 0xAA80..0xAADF -> listOf("tavt")
        cp in 0xAAE0..0xAAFF || cp in 0xABC0..0xABFF -> listOf("mtei")
        cp in 0x1000..0x109F -> listOf("mym2", "mymr")
        cp in 0x10A0..0x10FF -> listOf("geor")
        cp in 0x1200..0x137F -> listOf("ethi")
        cp in 0x1780..0x17FF -> listOf("khmr")
        cp in 0x1100..0x11FF || cp in 0xAC00..0xD7AF || cp in 0x3130..0x318F -> listOf("hang")
        cp in 0x3040..0x30FF -> listOf("kana")
        cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xF900..0xFAFF -> listOf("hani")
        else -> null
    }

    private val LATN = listOf("latn")

    /**
     * The characters inside the blocks of [scriptTags] that Scripts.txt of Unicode 17 gives to
     * no one script (Common or Inherited), such as the danda. They do not choose the script.
     */
    private fun isShared(cp: Int): Boolean =
        cp == 0x00D7 || cp == 0x00F7 || cp == 0x0374 || cp == 0x037E || cp == 0x0385 || cp == 0x0387 || cp in 0x0485..0x0486 ||
            cp == 0x0605 || cp == 0x060C || cp == 0x061B || cp == 0x061F || cp == 0x0640 || cp in 0x064B..0x0655 || cp == 0x0670 ||
            cp == 0x06DD || cp == 0x08E2 || cp in 0xFD3E..0xFD3F || cp == 0xFEFF || cp in 0x0951..0x0954 || cp in 0x0964..0x0965 ||
            cp == 0x0E3F || cp in 0x0FD5..0x0FD8 || cp == 0x10FB || cp in 0x3099..0x309C || cp == 0x30A0 || cp in 0x30FB..0x30FC
}
