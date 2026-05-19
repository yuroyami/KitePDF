package com.yuroyami.kitepdf.content

import com.yuroyami.kitepdf.core.ByteReader
import com.yuroyami.kitepdf.core.PdfFormatException
import com.yuroyami.kitepdf.parser.Lexer
import com.yuroyami.kitepdf.parser.Parser
import com.yuroyami.kitepdf.parser.PdfObject
import com.yuroyami.kitepdf.parser.Token

/**
 * Content stream parsing (ISO 32000-1 §7.8 + §8 + §9).
 *
 * A content stream is a postfix-operator program: operands come first, then
 * the operator name. We tokenize with the regular [Lexer] (syntax is the same
 * as the body), gather operands on a small stack, and emit one [Operation]
 * per operator we encounter.
 *
 * NOTE: this is just *parsing* — the actual graphics state machine lives in
 * a future renderer. Text extraction in this session only inspects which
 * operators were emitted with which operands.
 */
data class Operation(val operator: String, val operands: List<PdfObject>)

object ContentStreamParser {

    /** Parse the decoded content-stream bytes into a flat list of operations. */
    fun parse(bytes: ByteArray): List<Operation> {
        val reader = ByteReader(bytes)
        val lexer = Lexer(reader)
        val parser = Parser(lexer)
        val ops = mutableListOf<Operation>()
        val operandStack = mutableListOf<PdfObject>()

        while (true) {
            val tok = peekNonOperatorToken(lexer) ?: break
            when (tok) {
                is Token.Keyword -> {
                    // Inline images need special handling — "BI ... ID ... EI" is a mini stream.
                    if (tok.value == "BI") {
                        consumeInlineImage(reader)
                        operandStack.clear()
                    } else {
                        ops.add(Operation(tok.value, operandStack.toList()))
                        operandStack.clear()
                    }
                }
                Token.EndOfFile -> break
                else -> {
                    // Push back and let the Parser read a full object (handles arrays/dicts).
                    rewindToken(reader, tok)
                    operandStack.add(parser.readObject())
                }
            }
        }
        return ops
    }

    /**
     * peek one token AND consume it; we only special-case keywords for
     * downstream branching. For non-keyword tokens we rewind so [Parser] can
     * re-read them as a full PdfObject (handles arrays "[ ... ]" properly).
     */
    private fun peekNonOperatorToken(lexer: Lexer): Token? {
        val checkpoint = lexer.reader.pos()
        val tok = lexer.nextToken()
        if (tok == Token.EndOfFile) return null
        return when (tok) {
            is Token.Keyword -> tok
            else -> {
                // Rewind so the parser sees this token again.
                lexer.reader.seek(checkpoint)
                tok
            }
        }
    }

    private fun rewindToken(reader: ByteReader, tok: Token) {
        // Not used in current path because peekNonOperatorToken rewinds itself.
        // Kept as a no-op for the API shape.
        if (tok == Token.EndOfFile) return
    }

    /**
     * Inline image: `BI <dict-entries> ID <raw-data> EI`. The raw bytes
     * between ID and EI are NOT tokenizable (binary). We scan for the EI
     * sentinel preceded by whitespace.
     */
    private fun consumeInlineImage(reader: ByteReader) {
        // Skip to "ID" marker first.
        val idMarker = "ID".encodeToByteArray()
        while (true) {
            val pos = reader.pos()
            if (reader.matches(idMarker, pos)) {
                reader.advance(2)
                // Single whitespace byte expected after ID per spec.
                val nb = reader.peek()
                if (nb == ' '.code || nb == '\r'.code || nb == '\n'.code || nb == '\t'.code) {
                    reader.readByte()
                }
                break
            }
            if (reader.readByte() == -1) return
        }
        // Now scan forward for "EI" preceded by whitespace.
        while (!reader.isAtEnd()) {
            val b = reader.readByte()
            if ((b == ' '.code || b == '\r'.code || b == '\n'.code || b == '\t'.code) &&
                reader.peek() == 'E'.code && reader.peek(1) == 'I'.code
            ) {
                reader.advance(2)
                return
            }
        }
    }
}
