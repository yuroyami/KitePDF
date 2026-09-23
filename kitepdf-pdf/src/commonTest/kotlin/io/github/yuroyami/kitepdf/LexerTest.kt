package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteReader
import io.github.yuroyami.kitepdf.core.parser.Lexer
import io.github.yuroyami.kitepdf.core.parser.Token
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LexerTest {

    private fun lex(src: String): List<Token> {
        val lexer = Lexer(ByteReader(src.encodeToByteArray()))
        val out = mutableListOf<Token>()
        while (true) {
            val t = lexer.nextToken()
            if (t == Token.EndOfFile) break
            out.add(t)
        }
        return out
    }

    @Test
    fun integers_reals_signs() {
        val toks = lex("42 -7 +5 3.14 -.5")
        assertEquals(5, toks.size)
        assertEquals(42L, (toks[0] as Token.Integer).value)
        assertEquals(-7L, (toks[1] as Token.Integer).value)
        assertEquals(5L, (toks[2] as Token.Integer).value)
        assertEquals(3.14, (toks[3] as Token.Real).value)
        assertEquals(-0.5, (toks[4] as Token.Real).value)
    }

    @Test
    fun name_with_hex_escape() {
        val toks = lex("/Hello#20World")
        assertIs<Token.Name>(toks[0])
        assertEquals("Hello World", (toks[0] as Token.Name).value)
    }

    @Test
    fun literal_string_with_escapes() {
        val toks = lex("""(Hello \(world\) \n \101)""")
        val s = toks[0] as Token.StringLiteral
        // \101 = octal 101 = 'A'
        assertContentEquals("Hello (world) \n A".encodeToByteArray(), s.bytes)
    }

    @Test
    fun hex_string() {
        val toks = lex("<48656C6C6F>")
        val s = toks[0] as Token.StringLiteral
        assertContentEquals("Hello".encodeToByteArray(), s.bytes)
    }

    @Test
    fun dict_and_array_tokens() {
        val toks = lex("<< /Length 5 /Filter /FlateDecode >> [1 2 3]")
        assertEquals(Token.DictOpen, toks[0])
        assertEquals("Length", (toks[1] as Token.Name).value)
        assertEquals(5L, (toks[2] as Token.Integer).value)
        assertEquals("Filter", (toks[3] as Token.Name).value)
        assertEquals("FlateDecode", (toks[4] as Token.Name).value)
        assertEquals(Token.DictClose, toks[5])
        assertEquals(Token.ArrayOpen, toks[6])
        assertEquals(Token.ArrayClose, toks[10])
    }

    @Test
    fun comment_is_skipped() {
        val toks = lex("%comment\n42")
        assertTrue(toks.size == 1)
        assertEquals(42L, (toks[0] as Token.Integer).value)
    }

    @Test
    fun keywords_obj_R() {
        val toks = lex("12 0 obj 7 0 R endobj")
        assertEquals("obj", (toks[2] as Token.Keyword).value)
        assertEquals("R", (toks[5] as Token.Keyword).value)
        assertEquals("endobj", (toks[6] as Token.Keyword).value)
    }

    @Test
    fun a_real_is_the_nearest_double() {
        // Each expected value is a literal, which the compiler rounds to the nearest double on every target (#119).
        val exact = listOf(
            "0.1" to 0.1, "-.5" to -0.5, "5." to 5.0, "-0.0" to -0.0, "+1.5" to 1.5, ".000123" to 0.000123,
            "3.14159265358979" to 3.14159265358979, "1234567.1234567" to 1234567.1234567,
            "-99999999999999.9" to -99999999999999.9, "00000000000000000000000000001.25" to 1.25,
            // The String parse of Kotlin/Wasm gives the next double up for this one.
            "-758.41019" to -758.41019,
        )
        for ((text, expected) in exact) assertEquals(expected.toRawBits(), real(text).toRawBits(), text)
        // More than 15 significant digits, or more than 22 after the dot, take the String parse of the platform.
        val parsed = listOf(
            "123456789012345.6" to 123456789012345.6, "0.30000000000000004" to 0.30000000000000004,
            "0.0000000000000000000000001" to 1e-25, "99999999999999999999.5" to 99999999999999999999.5,
        )
        for ((text, expected) in parsed) assertEquals(expected, real(text), abs(expected) * 1e-15, text)
    }

    private fun real(text: String): Double = assertIs<Token.Real>(lex(text).single(), text).value

    @Test
    fun an_operator_is_one_shared_string() {
        val first = lex("0 0 m 10 10 l S")
        val second = lex("5 5 m")
        assertSame((first[2] as Token.Keyword).value, (second[2] as Token.Keyword).value)
        assertEquals("S", (first[6] as Token.Keyword).value)
        assertEquals("foo", (lex("foo").single() as Token.Keyword).value, "an unknown keyword still reads")
    }
}
