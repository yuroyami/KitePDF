package io.github.yuroyami.kitepdf.content

import io.github.yuroyami.kitepdf.core.ByteReader
import io.github.yuroyami.kitepdf.core.PdfFormatException
import io.github.yuroyami.kitepdf.parser.Lexer
import io.github.yuroyami.kitepdf.parser.Parser
import io.github.yuroyami.kitepdf.parser.PdfObject
import io.github.yuroyami.kitepdf.parser.Token

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
/**
 * One content-stream operation: an [operator] and its [operands].
 *
 * Inline images (`BI … ID … EI`) are NOT operand-based — their data is binary
 * and not tokenizable — so they are represented as a single operation with
 * operator `"BI"` and their entire `BI…EI` source captured in [inlineImage].
 * Re-serialization writes [inlineImage] back verbatim. Regular operations leave
 * it null.
 */
data class Operation(
    val operator: String,
    val operands: List<PdfObject>,
    val inlineImage: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Operation) return false
        if (operator != other.operator || operands != other.operands) return false
        return if (inlineImage == null) other.inlineImage == null
        else other.inlineImage != null && inlineImage.contentEquals(other.inlineImage)
    }

    override fun hashCode(): Int {
        var r = operator.hashCode()
        r = 31 * r + operands.hashCode()
        r = 31 * r + (inlineImage?.contentHashCode() ?: 0)
        return r
    }
}

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
                        // Capture the whole "BI…EI" run verbatim so it survives a
                        // parse → edit → re-serialize round-trip.
                        ops.add(Operation("BI", emptyList(), bytes.copyOfRange(tok.offset, reader.pos())))
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
    private val idMarker = byteArrayOf('I'.code.toByte(), 'D'.code.toByte())

    private fun consumeInlineImage(reader: ByteReader) {
        // Skip to "ID" marker first.
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
