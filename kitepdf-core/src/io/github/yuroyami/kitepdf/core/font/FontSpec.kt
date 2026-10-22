package io.github.yuroyami.kitepdf.core.font

/** Broad typeface family a non-embedded font substitutes into. */
public enum class KiteFontFamily { Serif, SansSerif, Monospace }

/**
 * The four written forms of Han characters, plus kana and Hangul, that a CJK substitute face is
 * picked by. Unicode unifies one Han character across them (Unicode 17, 18.1), so the face, not
 * the character, decides which form a reader sees.
 */
public enum class KiteCjkScript { Japanese, SimplifiedChinese, TraditionalChinese, Korean }

/**
 * Platform-neutral descriptor for picking a substitute system font when a
 * document font ships no embedded outlines (e.g. the PDF Standard-14, or a CSS
 * font with no `@font-face` file). Render backends map [family] + [bold] +
 * [italic] to a host typeface, and [language] to a face that draws that
 * language's own glyph forms. No PDF (or other format) types cross the canvas
 * seam, so every document handler (PDF, EPUB, ...) feeds the same [FontSpec].
 */
public data class FontSpec(
    val family: KiteFontFamily,
    val bold: Boolean,
    val italic: Boolean,
    /** Original font name (e.g. "Helvetica-Bold"), for diagnostics only. */
    val name: String = "",
    /**
     * The BCP 47 tag of the language the text is written in, such as `ja`, or null when
     * the document does not say. One Han character has a Japanese, a Simplified Chinese,
     * a Traditional Chinese and a Korean form, and a generic serif face draws only one of
     * them, so a substitute for a CJK font needs a face of its own language. A PDF CIDFont
     * names it through the character collection of its CIDSystemInfo (ISO 32000-1, 9.7.3,
     * #472): Adobe-Japan1 is `ja`, Adobe-GB1 `zh-Hans`, Adobe-CNS1 `zh-Hant` and
     * Adobe-Korea1 `ko`.
     */
    val language: String? = null,
) {

    /** The CJK script [language] is written in, or null for a language outside CJK or none. */
    public val cjkScript: KiteCjkScript? get() = cjkScriptOf(language)

    /**
     * Family names of host faces that draw [language] in [family], most wanted first, for a
     * canvas that picks its face by name. The list spans macOS, iOS, Windows and Linux, so a
     * host has a few of them at most. Empty when [cjkScript] is null, since a generic face of
     * the host already draws such a language.
     */
    public val hostFaces: List<String>
        get() = when (cjkScript) {
            KiteCjkScript.Japanese -> if (family == KiteFontFamily.Serif) JA_SERIF else JA_SANS
            KiteCjkScript.SimplifiedChinese -> if (family == KiteFontFamily.Serif) ZH_HANS_SERIF else ZH_HANS_SANS
            KiteCjkScript.TraditionalChinese -> if (family == KiteFontFamily.Serif) ZH_HANT_SERIF else ZH_HANT_SANS
            KiteCjkScript.Korean -> if (family == KiteFontFamily.Serif) KO_SERIF else KO_SANS
            null -> emptyList()
        }

    /**
     * A character that only a face of [language] draws, for a canvas that asks the host for
     * any face with that character, or null when [cjkScript] is null. Kana for Japanese,
     * Hangul for Korean, and a Han character that Simplified and Traditional Chinese write
     * differently.
     */
    public val languageSample: Int?
        get() = when (cjkScript) {
            KiteCjkScript.Japanese -> 0x3042
            KiteCjkScript.SimplifiedChinese -> 0x8FD9
            KiteCjkScript.TraditionalChinese -> 0x9019
            KiteCjkScript.Korean -> 0xD55C
            null -> null
        }

    public companion object {
        /** Neutral default: upright sans-serif. */
        public val SansSerif: FontSpec = FontSpec(KiteFontFamily.SansSerif, bold = false, italic = false)

        /** The BCP 47 tag of an Adobe CJK character collection (ISO 32000-1, 9.7.3), or null for another. */
        internal fun languageOfOrdering(ordering: String?): String? = when (ordering) {
            "Japan1" -> "ja"
            "GB1" -> "zh-Hans"
            "CNS1" -> "zh-Hant"
            "Korea1" -> "ko"
            else -> null
        }

        /**
         * Mincho faces. A monospaced CJK font draws in the Gothic list, since CJK faces are
         * already fixed width and a monospaced one is nearly always Gothic.
         */
        private val JA_SERIF = listOf(
            "Hiragino Mincho ProN", "Hiragino Mincho Pro", "Yu Mincho", "YuMincho", "MS PMincho", "MS Mincho",
            "Noto Serif CJK JP", "Noto Serif JP", "Source Han Serif JP", "IPAexMincho", "IPAPMincho", "IPAMincho",
            "TakaoPMincho", "TakaoMincho",
        )
        private val JA_SANS = listOf(
            "Hiragino Sans", "Hiragino Kaku Gothic ProN", "Hiragino Kaku Gothic Pro", "Yu Gothic", "YuGothic",
            "Meiryo", "MS PGothic", "MS Gothic", "Noto Sans CJK JP", "Noto Sans JP", "Source Han Sans JP",
            "IPAexGothic", "IPAPGothic", "IPAGothic", "TakaoPGothic", "TakaoGothic",
        )
        private val ZH_HANS_SERIF = listOf(
            "Songti SC", "STSong", "SimSun", "NSimSun", "Noto Serif CJK SC", "Noto Serif SC", "Source Han Serif SC",
            "AR PL SungtiL GB", "AR PL UMing CN",
        )
        private val ZH_HANS_SANS = listOf(
            "PingFang SC", "Heiti SC", "STHeiti", "Microsoft YaHei", "SimHei", "Noto Sans CJK SC", "Noto Sans SC",
            "Source Han Sans SC", "WenQuanYi Zen Hei", "WenQuanYi Micro Hei",
        )
        private val ZH_HANT_SERIF = listOf(
            "Songti TC", "PMingLiU", "MingLiU", "Noto Serif CJK TC", "Noto Serif TC", "Source Han Serif TC",
            "AR PL UMing TW",
        )
        private val ZH_HANT_SANS = listOf(
            "PingFang TC", "Heiti TC", "Microsoft JhengHei", "Noto Sans CJK TC", "Noto Sans TC",
            "Source Han Sans TC", "WenQuanYi Zen Hei",
        )
        private val KO_SERIF = listOf(
            "AppleMyungjo", "Batang", "Noto Serif CJK KR", "Noto Serif KR", "Source Han Serif K",
            "NanumMyeongjo", "UnBatang",
        )
        private val KO_SANS = listOf(
            "Apple SD Gothic Neo", "Malgun Gothic", "Gulim", "Noto Sans CJK KR", "Noto Sans KR",
            "Source Han Sans K", "NanumGothic", "UnDotum",
        )
    }
}

/**
 * The CJK script a BCP 47 [tag] is written in, from its language subtag and, for Chinese, its
 * script or region subtag (RFC 5646, 2.2). Chinese without either reads as Simplified.
 */
private fun cjkScriptOf(tag: String?): KiteCjkScript? {
    if (tag == null) return null
    val parts = tag.lowercase().split('-', '_')
    return when (parts[0]) {
        "ja" -> KiteCjkScript.Japanese
        "ko" -> KiteCjkScript.Korean
        "zh" -> if (parts.drop(1).any { it == "hant" || it == "tw" || it == "hk" || it == "mo" }) {
            KiteCjkScript.TraditionalChinese
        } else {
            KiteCjkScript.SimplifiedChinese
        }
        else -> null
    }
}
