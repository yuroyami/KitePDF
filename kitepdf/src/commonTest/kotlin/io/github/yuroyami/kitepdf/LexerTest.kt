package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteReader
import io.github.yuroyami.kitepdf.parser.Lexer
import io.github.yuroyami.kitepdf.parser.Token
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
}
