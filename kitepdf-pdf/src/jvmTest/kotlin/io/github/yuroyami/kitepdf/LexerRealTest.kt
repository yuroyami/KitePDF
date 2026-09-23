package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteReader
import io.github.yuroyami.kitepdf.core.parser.Lexer
import io.github.yuroyami.kitepdf.core.parser.Token
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The lexer reads a real as the nearest double, which the JVM's String parse gives by its specification (#119). */
class LexerRealTest {

    @Test
    fun random_decimals_equal_the_string_parse_to_the_bit() {
        val random = Random(119)
        repeat(20_000) {
            val digits = (1..random.nextInt(1, 18)).map { '0' + random.nextInt(10) }.joinToString("")
            val dot = random.nextInt(0, digits.length + 1)
            val text = (if (random.nextBoolean()) "-" else "") + digits.substring(0, dot) + "." + digits.substring(dot)
            val real = assertIs<Token.Real>(Lexer(ByteReader(text.encodeToByteArray())).nextToken(), text)
            assertEquals(text.toDouble().toRawBits(), real.value.toRawBits(), text)
        }
    }
}
