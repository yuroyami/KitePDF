package io.github.yuroyami.kitepdf.epub

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

    /** The joining feature of [cp] in [form], or none for a character that does not join. */
    fun joining(cp: Int, form: ArabicJoining.Form): Set<String> = when (ArabicJoining.type(cp)) {
        ArabicJoining.Jt.D, ArabicJoining.Jt.R, ArabicJoining.Jt.C -> setOf(ArabicJoining.feature(form))
        else -> emptySet()
    }

    /** True for a combining mark, the class a lookup flag skips when the font has no GDEF classes. */
    fun isMark(ch: Char): Boolean = ch.category == CharCategory.NON_SPACING_MARK || ch.category == CharCategory.ENCLOSING_MARK

    private val RTL = setOf("arab", "hebr", "syrc", "thaa", "nko ")

    /** The OpenType tags of the script of [cp], the newer tag first, or null for common characters. */
    private fun scriptTags(cp: Int): List<String>? = when {
        cp in 0x41..0x5A || cp in 0x61..0x7A || cp in 0xC0..0x24F || cp in 0x1E00..0x1EFF -> LATN
        cp in 0x370..0x3FF || cp in 0x1F00..0x1FFF -> listOf("grek")
        cp in 0x400..0x52F -> listOf("cyrl")
        cp in 0x530..0x58F -> listOf("armn")
        cp in 0x590..0x5FF || cp in 0xFB1D..0xFB4F -> listOf("hebr")
        cp in 0x600..0x6FF || cp in 0x750..0x77F || cp in 0x8A0..0x8FF || cp in 0xFB50..0xFDFF || cp in 0xFE70..0xFEFF -> listOf("arab")
        cp in 0x700..0x74F -> listOf("syrc")
        cp in 0x780..0x7BF -> listOf("thaa")
        cp in 0x900..0x97F -> listOf("dev2", "deva")
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
}
