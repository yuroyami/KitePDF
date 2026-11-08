package io.github.yuroyami.kitepdf.javascript

import kotlin.test.Test
import kotlin.test.assertEquals

/** Page words split at ASCII white space on every target (#883). */
class PageWordsTest {

    @Test
    fun aNoBreakSpaceStaysInsideAWord() {
        val nbsp = Char(0xA0)
        assertEquals(listOf("10${nbsp}000${nbsp}€", "net"), pageWordsOf(" 10${nbsp}000${nbsp}€\t net\n"))
        assertEquals(listOf("a${Char(0x3000)}b"), pageWordsOf("a${Char(0x3000)}b"))
    }
}
