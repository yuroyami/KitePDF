package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.text.Hyphenator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Language-aware hyphenation (T-70): the bundled TeX pattern sets must produce
 * the exact break-point sets of the reference Knuth-Liang algorithm. Expected
 * values were computed by an independent (non-trie) implementation of the
 * algorithm over the same hyph-utf8 pattern files, so a trie bug cannot
 * self-confirm.
 */
class MultilingualHyphenationTest {

    private fun check(hyph: Hyphenator, cases: List<Pair<String, List<Int>>>) {
        for ((word, expected) in cases) {
            assertEquals(expected, hyph.hyphenate(word), "breaks for \"$word\"")
        }
    }

    @Test fun german_de1996() = check(
        Hyphenator.forLanguage("de")!!,
        listOf(
            "Autobahn" to listOf(2, 4),            // Au-to-bahn
            "Universität" to listOf(3, 6, 8),      // Uni-ver-si-tät
            "Krankenhaus" to listOf(4, 7),         // Kran-ken-haus
            "Wissenschaft" to listOf(3, 6),        // Wis-sen-schaft
            "Bundesregierung" to listOf(3, 6, 8, 11), // Bun-des-re-gie-rung
        ),
    )

    @Test fun french() = check(
        Hyphenator.forLanguage("fr-FR")!!,
        listOf(
            "bonjour" to listOf(3),                   // bon-jour
            "université" to listOf(3, 6, 8),          // uni-ver-si-té
            "développement" to listOf(2, 4, 7, 9),    // dé-ve-lop-pe-ment
            "extraordinaire" to listOf(2, 5, 7, 9),   // ex-tra-or-di-naire
            "bibliothèque" to listOf(2, 6),           // bi-blio-thèque
        ),
    )

    @Test fun spanish() = check(
        Hyphenator.forLanguage("es")!!,
        listOf(
            "ferrocarril" to listOf(2, 5, 7),             // fe-rro-ca-rril
            "universidad" to listOf(3, 6, 8),             // uni-ver-si-dad
            "desarrollo" to listOf(2, 4, 7),              // de-sa-rro-llo
            "extraordinario" to listOf(2, 5, 7, 9, 11),   // ex-tra-or-di-na-rio
            "biblioteca" to listOf(2, 6, 8),              // bi-blio-te-ca
        ),
    )

    @Test fun italian() = check(
        Hyphenator.forLanguage("it")!!,
        listOf(
            "università" to listOf(3, 6, 8),      // uni-ver-si-tà
            "sviluppo" to listOf(3, 6),           // svi-lup-po
            "straordinario" to listOf(6, 8, 10),  // straor-di-na-rio
            "biblioteca" to listOf(2, 6, 8),      // bi-blio-te-ca
            "macchina" to listOf(3, 6),           // mac-chi-na
        ),
    )

    @Test fun portuguese() = check(
        Hyphenator.forLanguage("pt_BR")!!,
        listOf(
            "universidade" to listOf(3, 6, 8),           // uni-ver-si-dade
            "desenvolvimento" to listOf(2, 5, 8, 10),    // de-sen-vol-vi-mento
            "extraordinário" to listOf(2, 5, 7, 9, 11),  // ex-tra-or-di-ná-rio
            "biblioteca" to listOf(2, 5, 6),             // bi-bli-o-teca
            "computador" to listOf(3, 5, 7),             // com-pu-ta-dor
        ),
    )

    @Test fun dutch() = check(
        Hyphenator.forLanguage("nl")!!,
        listOf(
            "universiteit" to listOf(3, 6, 8),   // uni-ver-si-teit
            "ontwikkeling" to listOf(3, 6, 8),   // ont-wik-ke-ling
            "buitengewoon" to listOf(3, 6, 8),   // bui-ten-ge-woon
            "bibliotheek" to listOf(2, 5, 6),    // bi-bli-o-theek
            "ziekenhuis" to listOf(3, 6),        // zie-ken-huis
        ),
    )

    /** The trie rewrite must not change en-US output (same patterns, same mins). */
    @Test fun en_us_output_unchanged() = check(
        Hyphenator.enUs(),
        listOf(
            "hyphenation" to listOf(2),
            "computer" to listOf(3),
            "derivative" to listOf(3, 4),
            "information" to listOf(5),
        ),
    )

    @Test fun language_mapping_and_caching() {
        assertSame(Hyphenator.forLanguage("de"), Hyphenator.forLanguage("de-AT"), "shared instance per language")
        assertSame(Hyphenator.enUs(), Hyphenator.forLanguage("en-GB"))
        assertNull(Hyphenator.forLanguage("ja"), "no bundled set: caller decides the fallback")
        assertNull(Hyphenator.forLanguage(null))
        assertNull(Hyphenator.forLanguage("  "))
    }
}
