package io.github.yuroyami.kitepdf.core.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A CMap that ends inside a block returns with the mappings read so far. Each block reader used
 * to read past the end of the stream forever, because the lexer returns end of file again and
 * again, so one damaged font hung a render thread.
 */
class CMapTerminationTest {

    /** Parses [text] on a daemon thread and fails if the parse has not returned within five seconds. */
    private fun parseBounded(text: String): CMap {
        var result: Result<CMap>? = null
        val worker = Thread { result = runCatching { CMap.parse(text.encodeToByteArray()) } }
        worker.isDaemon = true
        worker.start()
        worker.join(5_000)
        if (worker.isAlive) fail("CMap.parse did not return for: $text")
        return result!!.getOrThrow()
    }

    @Test
    fun every_block_that_ends_with_the_stream_returns() {
        val truncated = listOf(
            "1 begincodespacerange <00>",
            "1 begincodespacerange <00> <FF>",
            "1 beginbfchar <41>",
            "1 beginbfchar <41> <0041>",
            "1 beginbfrange <41>",
            "1 beginbfrange <41> <42>",
            "1 beginbfrange <41> <42> <0041>",
            "1 beginbfrange <41> <42> /name",
            "1 beginbfrange <41> <42> [<0041>",
            "1 beginbfrange <41> <42> [",
            "1 begincidchar <41>",
            "1 begincidchar <41> 5",
            "1 begincidrange <00> <FF>",
            "1 begincidrange <00> <FF> 1",
        )
        for (text in truncated) parseBounded(text)
    }

    @Test
    fun the_mappings_before_the_end_are_kept() {
        val chars = parseBounded("1 begincodespacerange <00> <FF> endcodespacerange 2 beginbfchar <41> <0058> <42> <0059>")
        assertEquals("XY", chars.decodeAll(byteArrayOf(0x41, 0x42)))

        val range = parseBounded("1 begincodespacerange <00> <FF> endcodespacerange 1 beginbfrange <41> <42> <0061>")
        assertEquals("ab", range.decodeAll(byteArrayOf(0x41, 0x42)))

        val array = parseBounded("1 begincodespacerange <00> <FF> endcodespacerange 1 beginbfrange <41> <42> [<0070> <0071>")
        assertEquals("pq", array.decodeAll(byteArrayOf(0x41, 0x42)))

        val cids = parseBounded("1 begincodespacerange <00> <FF> endcodespacerange 1 begincidrange <00> <FF> 100")
        assertTrue(cids.hasCidMappings)
        assertEquals(165, cids.codeUnits(byteArrayOf(0x41)).single().cid)
    }
}
