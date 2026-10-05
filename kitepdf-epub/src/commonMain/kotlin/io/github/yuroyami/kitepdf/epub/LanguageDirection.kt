package io.github.yuroyami.kitepdf.epub

/**
 * Whether a BCP 47 language tag names text that reads right to left (#512). A script subtag
 * decides when the tag has one, so `az-Arab` reads right to left and `ar-Latn` does not.
 * Otherwise the language decides, by the script CLDR's likely subtags give it.
 */
internal fun isRightToLeftLanguage(tag: String?): Boolean {
    val parts = tag?.trim()?.lowercase()?.replace('_', '-')?.split('-') ?: return false
    val language = parts.first()
    if (language.isEmpty()) return false
    val script = parts.drop(1).firstOrNull { it.length == 4 && it.all { c -> c in 'a'..'z' } }
    return if (script != null) script in RTL_SCRIPTS else language in RTL_LANGUAGES
}

/** ISO 15924 codes of the scripts written right to left, lower case. */
private val RTL_SCRIPTS = setOf(
    "adlm", "arab", "aran", "armi", "avst", "chrs", "cprt", "elym", "hatr", "hebr", "hung", "khar",
    "lydi", "mand", "mani", "mend", "narb", "nbat", "nkoo", "orkh", "ougr", "palm", "phli", "phlp",
    "phnx", "prti", "rohg", "samr", "sarb", "sogd", "sogo", "syrc", "thaa", "yezi",
)

/** Languages whose likely script is one of [RTL_SCRIPTS], with the old codes for Hebrew and Yiddish. */
private val RTL_LANGUAGES = setOf(
    // Arabic and its varieties.
    "ar", "arb", "arq", "ars", "ary", "arz", "acm", "acq", "aeb", "afb", "ajp", "apc", "apd", "ayl", "ayp", "shu", "ssh",
    // Other languages written in Arabic script.
    "azb", "bal", "bgn", "bqi", "ckb", "fa", "glk", "khw", "ks", "lrc", "luz", "mzn", "pnb", "prs", "ps", "sd", "sdh",
    "skr", "ug", "ur",
    // Hebrew script, Syriac, Thaana and N'Ko.
    "he", "iw", "yi", "ji", "lad", "jrb", "arc", "syr", "dv", "nqo",
)
