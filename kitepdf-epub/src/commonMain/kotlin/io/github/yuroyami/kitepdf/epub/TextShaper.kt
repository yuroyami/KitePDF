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
     * [gids]: through [IndicShaper] for the scripts of India, and through GSUB in the stages of
     * [stages] for the rest. [forms] are the Arabic joining forms of the run, when it has
     * Arabic. The cluster of each glyph is the index of its first character in [codePoints].
     */
    fun shape(
        face: EmbeddedFace, gsub: OpenTypeGsub, script: String, codePoints: IntArray, gids: IntArray,
        forms: Array<ArabicJoining.Form?>?, optionalLigatures: Boolean,
    ): MutableList<GsubGlyph> {
        val glyphs = ArrayList<GsubGlyph>(codePoints.size)
        val ignorables = ArrayList<GsubGlyph>()
        if (IndicShaper.handles(script, gsub)) {
            val prepared = IndicShaper.prepare(script, codePoints) { face.gidFor(it) != 0 }
            for ((j, cp) in prepared.codePoints.withIndex()) {
                val source = prepared.sources[j]
                val gid = if (cp == codePoints[source]) gids[source] else face.gidFor(cp)
                glyphs += GsubGlyph(gid, source, isMark = isMark(cp), ignorable = ignorable(cp))
                if (isDefaultIgnorable(cp)) ignorables += glyphs.last()
            }
            IndicShaper.shape(gsub, script, glyphs, prepared.codePoints, face::gidFor, optionalLigatures)
        } else {
            for ((k, cp) in codePoints.withIndex()) {
                val joining = forms?.get(k)?.let { setOf(ArabicJoining.feature(it)) } ?: emptySet()
                glyphs += GsubGlyph(gids[k], k, joining, isMark(cp), ignorable(cp))
                if (isDefaultIgnorable(cp)) ignorables += glyphs.last()
            }
            val manualZwj = if (script == "arab" || script == "syrc") ARABIC_MANUAL_ZWJ else emptySet()
            gsub.substitute(glyphs, script, null, stages(script, optionalLigatures), POSITIONAL, manualZwj = manualZwj)
        }
        hideIgnorables(face, glyphs, ignorables)
        return glyphs
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

    private val RTL = setOf("arab", "hebr", "syrc", "thaa", "nko ")

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
