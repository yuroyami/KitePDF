package io.github.yuroyami.kitepdf.core

import io.github.yuroyami.kitepdf.core.text.hyphen.HyphRu
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the COMPLETE Russian pattern set against its upstream source
 * (hyph-utf8 commit 89b22656cceac41721d86f26e32551cbb1cd0e06). Two golden
 * words cannot notice corruption elsewhere in 7021 patterns; a count plus a
 * hash can. A legitimate upstream refresh updates these constants and the
 * header pin together.
 */
class HyphRuDataIntegrityTest {

    private fun normalized(): List<String> =
        HyphRu.patterns.lines().map { it.trim() }.filter { it.isNotEmpty() }

    @Test
    fun the_pattern_set_is_complete_and_untouched() {
        val pats = normalized()
        assertEquals(7021, pats.size)
        assertEquals(".аб1р", pats.first())
        assertEquals("8я8я-", pats.last())
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(pats.joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> ((b.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
        assertEquals("2eb525e7d423355616fabf2929150ba5dd9b47dcb1923188af4172ac96e456e2", digest)
    }
}
