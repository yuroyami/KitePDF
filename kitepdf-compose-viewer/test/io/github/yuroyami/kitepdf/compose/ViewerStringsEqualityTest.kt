package io.github.yuroyami.kitepdf.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Two sets of the same words are equal, so a set built inline under a static composition local
 * does not recompose the whole viewer each time its composable recomposes (#675).
 */
class ViewerStringsEqualityTest {

    @Test
    fun the_same_words_are_the_same_strings() {
        assertEquals(KiteViewerStrings(), KiteViewerStrings())
        assertEquals(KiteViewerStrings().hashCode(), KiteViewerStrings().hashCode())
        assertEquals(KiteViewerStrings(previousPage = "Prev"), KiteViewerStrings(previousPage = "Prev"))
        assertNotEquals(KiteViewerStrings(previousPage = "Prev"), KiteViewerStrings(previousPage = "Back"))
        val page: (Int, Int?) -> String = { number, _ -> "Seite $number" }
        assertEquals(KiteViewerStrings(page = page), KiteViewerStrings(page = page))
        assertNotEquals(KiteViewerStrings(page = page), KiteViewerStrings())

        assertEquals(KiteChoiceStrings(), KiteChoiceStrings())
        assertEquals(KiteChoiceStrings(noOptions = "Leer").hashCode(), KiteChoiceStrings(noOptions = "Leer").hashCode())
        assertNotEquals(KiteChoiceStrings(noOptions = "Leer"), KiteChoiceStrings())
    }
}
