package io.github.yuroyami.kitepdf.core.font

/**
 * The OpenType language system tag of a BCP 47 language tag (#915), from the OpenType language
 * tag registry: `vi` is `VIT `, `tr` is `TRK `. It covers the languages whose fonts commonly
 * place marks or forms of their own; null for one it does not know, which then reads with the
 * default language system of its script, as HarfBuzz reads a language that a font lacks.
 */
public object OpenTypeLanguage {

    public fun tagOf(bcp47: String): String? {
        val parts = bcp47.trim().replace('_', '-').lowercase().split('-')
        val primary = parts.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        if (primary == "zh") {
            val rest = parts.drop(1)
            return when {
                "hant" in rest || "tw" in rest -> "ZHT "
                "hk" in rest || "mo" in rest -> "ZHH "
                else -> "ZHS "
            }
        }
        return TAGS[primary]?.padEnd(4, ' ')
    }

    private val TAGS: Map<String, String> = mapOf(
        "am" to "AMH", "ar" to "ARA", "az" to "AZE", "ba" to "BSH", "be" to "BEL", "bg" to "BGR", "bn" to "BEN", "bo" to "TIB",
        "bs" to "BOS", "ca" to "CAT", "crh" to "CRT", "cs" to "CSY", "cv" to "CHU", "cy" to "WEL", "da" to "DAN", "de" to "DEU",
        "dv" to "DIV", "el" to "ELL", "en" to "ENG", "eo" to "NTO", "es" to "ESP", "et" to "ETI", "eu" to "EUQ", "fa" to "FAR",
        "fi" to "FIN", "fil" to "PIL", "fo" to "FOS", "fr" to "FRA", "ga" to "IRI", "gd" to "GAE", "gl" to "GAL", "gu" to "GUJ",
        "ha" to "HAU", "he" to "IWR", "hi" to "HIN", "hr" to "HRV", "hu" to "HUN", "hy" to "HYE", "id" to "IND", "ig" to "IBO",
        "is" to "ISL", "it" to "ITA", "ja" to "JAN", "jv" to "JAV", "ka" to "KAT", "kk" to "KAZ", "km" to "KHM", "kn" to "KAN",
        "ko" to "KOR", "ks" to "KSH", "ku" to "KUR", "ky" to "KIR", "la" to "LAT", "lo" to "LAO", "lt" to "LTH", "lv" to "LVI",
        "mi" to "MRI", "mk" to "MKD", "ml" to "MAL", "mn" to "MNG", "mo" to "MOL", "mr" to "MAR", "ms" to "MLY", "mt" to "MTS",
        "my" to "BRM", "nb" to "NOR", "ne" to "NEP", "nl" to "NLD", "nn" to "NYN", "no" to "NOR", "or" to "ORI", "pa" to "PAN",
        "pl" to "PLK", "ps" to "PAS", "pt" to "PTG", "ro" to "ROM", "ru" to "RUS", "sa" to "SAN", "sd" to "SND", "si" to "SNH",
        "sk" to "SKY", "sl" to "SLV", "sq" to "SQI", "sr" to "SRB", "sv" to "SVE", "sw" to "SWK", "ta" to "TAM", "te" to "TEL",
        "tg" to "TAJ", "th" to "THA", "ti" to "TGY", "tk" to "TKM", "tr" to "TRK", "tt" to "TAT", "ug" to "UYG", "uk" to "UKR",
        "ur" to "URD", "uz" to "UZB", "vi" to "VIT", "yi" to "JII", "yo" to "YBA", "zu" to "ZUL",
    )
}
