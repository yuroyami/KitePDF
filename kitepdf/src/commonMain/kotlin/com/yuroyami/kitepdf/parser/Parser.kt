package com.yuroyami.kitepdf.parser

import com.yuroyami.kitepdf.core.ByteReader
import com.yuroyami.kitepdf.core.PdfFormatException

/**
 * Recursive-descent parser for PDF objects (ISO 32000-1 §7.3).
 *
 * Drives a [Lexer] one token at a time. Handles the tricky one-token lookahead
 * needed to distinguish "N G obj" headers, "N G R" references, and bare numbers
 * inside arrays — see [readArray] for the gory details.
 */
class Parser(private val lexer: Lexer) {

    constructor(bytes: ByteArray) : this(Lexer(ByteReader(bytes)))

    private val reader: ByteReader get() = lexer.reader

    /** Read exactly one PDF object from the current position. */
    fun readObject(): PdfObject {
        return readObject(lexer.nextToken())
    }

    /** Read one object with a token that's already been consumed. */
    private fun readObject(first: Token): PdfObject = when (first) {
        is Token.Integer -> readNumberOrReferenceOrInt(first)
        is Token.Real -> PdfReal(first.value)
        is Token.StringLiteral -> PdfString(first.bytes)
        is Token.Name -> PdfName(first.value)
        Token.ArrayOpen -> readArray()
        Token.DictOpen -> readDictionaryThenMaybeStream()
        is Token.Keyword -> when (first.value) {
            "true" -> PdfBoolean(true)
            "false" -> PdfBoolean(false)
            "null" -> PdfNull
            else -> throw PdfFormatException("Unexpected keyword '${first.value}' at ${first.offset}")
        }
        Token.DictClose, Token.ArrayClose, Token.EndOfFile ->
            throw PdfFormatException("Unexpected token $first")
    }

    /**
     * An integer might be the start of "N G R" (reference) or "N G obj" (object
     * header). Only valid in specific contexts — but a bare integer inside an
     * array also needs to peek ahead, because "10 0 R" is three tokens that
     * become one PdfReference. We snapshot the reader position so we can
     * back out if the lookahead doesn't match.
     */
    private fun readNumberOrReferenceOrInt(first: Token.Integer): PdfObject {
        val checkpoint = reader.pos()
        val second = lexer.nextToken()
        if (second is Token.Integer) {
            val third = lexer.nextToken()
            if (third is Token.Keyword && third.value == "R") {
                return PdfReference(first.value, second.value.toInt())
            }
            // Not a reference — rewind to before [second].
            reader.seek(checkpoint)
            return PdfInt(first.value)
        }
        // Not a reference — rewind to before [second].
        reader.seek(checkpoint)
        return PdfInt(first.value)
    }

    private fun readArray(): PdfArray {
        val items = mutableListOf<PdfObject>()
        while (true) {
            val tok = lexer.nextToken()
            if (tok == Token.ArrayClose) return PdfArray(items)
            items.add(readObject(tok))
        }
    }

    private fun readDictionary(): PdfDictionary {
        val entries = LinkedHashMap<String, PdfObject>()
        while (true) {
            val tok = lexer.nextToken()
            if (tok == Token.DictClose) return PdfDictionary(entries)
            if (tok !is Token.Name) {
                throw PdfFormatException("Dictionary key must be a Name, got $tok")
            }
            val value = readObject()
            entries[tok.value] = value
        }
    }

    /**
     * After reading a "<<...>>" we have to peek for the optional "stream"
     * keyword that turns it into a PdfStream. The byte length of the stream
     * is given by /Length in the dict (which itself may be an indirect ref).
     * Indirect /Length is resolved by the document, not here — so this
     * method only handles direct integers; indirect ones are deferred.
     */
    private fun readDictionaryThenMaybeStream(): PdfObject {
        val dict = readDictionary()
        val checkpoint = reader.pos()
        val next = lexer.nextToken()
        if (next is Token.Keyword && next.value == "stream") {
            // PDF says stream keyword is followed by EOL (CRLF or LF, NOT bare CR).
            consumeStreamEol()
            val streamStart = reader.pos()
            val length = dict.getInt("Length")
                ?: throw PdfFormatException("/Length missing or indirect — caller must resolve before stream parse")
            if (length < 0) throw PdfFormatException("Negative stream /Length: $length")
            if (streamStart + length > reader.size) {
                throw PdfFormatException("Stream length $length runs past EOF from $streamStart")
            }
            val raw = reader.readBytes(length.toInt())
            // Expect "endstream" keyword (allowing whitespace before).
            val endTok = lexer.nextToken()
            if (endTok !is Token.Keyword || endTok.value != "endstream") {
                throw PdfFormatException("Expected 'endstream' after stream body, got $endTok")
            }
            return PdfStream(dict, raw)
        }
        // Not a stream — rewind.
        reader.seek(checkpoint)
        return dict
    }

    private fun consumeStreamEol() {
        // ISO 32000-1 §7.3.8.1: stream keyword must be followed by CRLF or LF (not CR alone).
        val c = reader.readByte()
        when (c) {
            '\n'.code -> return
            '\r'.code -> {
                if (reader.peek() == '\n'.code) reader.readByte()
            }
            else -> throw PdfFormatException(
                "stream keyword must be followed by EOL, got byte 0x${c.toString(16)}",
            )
        }
    }

    /**
     * Parse a full indirect object: "N G obj <object> endobj".
     * The reader must currently sit at the start of "N G obj".
     */
    fun readIndirectObject(): IndirectObject {
        val numTok = lexer.nextToken() as? Token.Integer
            ?: throw PdfFormatException("Expected object number")
        val genTok = lexer.nextToken() as? Token.Integer
            ?: throw PdfFormatException("Expected generation number")
        val objTok = lexer.nextToken()
        if (objTok !is Token.Keyword || objTok.value != "obj") {
            throw PdfFormatException("Expected 'obj' keyword, got $objTok")
        }
        val payload = readObject()
        // Stream objects swallow their own 'endstream' — only check for 'endobj' if it wasn't a stream
        // whose endobj we already passed. Easiest: peek and accept either endobj or EOF.
        val maybeEnd = lexer.nextToken()
        if (maybeEnd !is Token.Keyword || maybeEnd.value != "endobj") {
            // Some PDFs are slack here. We don't fail.
        }
        return IndirectObject(numTok.value, genTok.value.toInt(), payload)
    }
}

/** An object as it appears in the body: number, generation, and the value. */
data class IndirectObject(val number: Long, val generation: Int, val value: PdfObject)
