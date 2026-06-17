package io.github.yuroyami.kitepdf.parser

import io.github.yuroyami.kitepdf.core.ByteReader
import io.github.yuroyami.kitepdf.core.PdfFormatException

/**
 * Recursive-descent parser for PDF objects (ISO 32000-1 §7.3).
 *
 * Drives a [Lexer] one token at a time. Handles the tricky one-token lookahead
 * needed to distinguish "N G obj" headers, "N G R" references, and bare numbers
 * inside arrays — see [readArray] for the gory details.
 *
 * Pass a [resolver] (typically the owning [io.github.yuroyami.kitepdf.PdfDocument]) when
 * parsing in a context where indirect references in stream dictionaries must be
 * resolved on the fly — most importantly when `/Length` is an indirect reference,
 * which MuPDF's pdf_stream_length() handles via pdf_dict_get_int64().
 */
class Parser(
    private val lexer: Lexer,
    private val resolver: IndirectResolver? = null,
) {

    constructor(bytes: ByteArray) : this(Lexer(ByteReader(bytes)))
    constructor(bytes: ByteArray, resolver: IndirectResolver?) : this(Lexer(ByteReader(bytes)), resolver)

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
            reader.seek(checkpoint)
            return PdfInt(first.value)
        }
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
     * keyword that turns it into a [PdfStream]. The byte length of the stream
     * is given by /Length in the dict — which may itself be an indirect
     * reference (MuPDF: pdf_stream_length()). If a [resolver] was provided
     * we follow the reference; otherwise we throw with a clear hint.
     */
    private fun readDictionaryThenMaybeStream(): PdfObject {
        val dict = readDictionary()
        val checkpoint = reader.pos()
        val next = lexer.nextToken()
        if (next !is Token.Keyword || next.value != "stream") {
            reader.seek(checkpoint)
            return dict
        }
        consumeStreamEol()
        val streamStart = reader.pos()
        val length = resolveStreamLength(dict)
        if (length < 0) throw PdfFormatException("Negative stream /Length: $length")
        if (streamStart + length > reader.size) {
            throw PdfFormatException("Stream length $length runs past EOF from $streamStart")
        }
        val raw = reader.readBytes(length.toInt())
        val endTok = lexer.nextToken()
        if (endTok !is Token.Keyword || endTok.value != "endstream") {
            throw PdfFormatException("Expected 'endstream' after stream body, got $endTok")
        }
        return PdfStream(dict, raw)
    }

    private fun resolveStreamLength(dict: PdfDictionary): Long {
        return when (val raw = dict["Length"]) {
            is PdfInt -> raw.value
            is PdfReference -> {
                val r = resolver
                    ?: throw PdfFormatException(
                        "/Length is indirect (${raw.objectNumber} ${raw.generation} R) but " +
                            "this Parser was created without an IndirectResolver",
                    )
                val resolvedPosBefore = reader.pos()
                val resolved = r.resolve(raw)
                    ?: throw PdfFormatException("Indirect /Length $raw could not be resolved")
                // The resolver may have moved the reader (it parses other objects).
                // Restore so we keep reading the stream body from where we were.
                reader.seek(resolvedPosBefore)
                (resolved as? PdfInt)?.value
                    ?: throw PdfFormatException("Indirect /Length resolved to non-integer: $resolved")
            }
            null -> throw PdfFormatException("/Length missing in stream dict: $dict")
            else -> throw PdfFormatException("/Length must be integer or reference, got $raw")
        }
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
        // Some producers omit 'endobj' or put trailing whitespace. We're lenient.
        val checkpoint = reader.pos()
        val maybeEnd = runCatching { lexer.nextToken() }.getOrNull()
        if (maybeEnd !is Token.Keyword || maybeEnd.value != "endobj") {
            reader.seek(checkpoint)
        }
        return IndirectObject(numTok.value, genTok.value.toInt(), payload)
    }
}

/** An object as it appears in the body: number, generation, and the value. */
data class IndirectObject(val number: Long, val generation: Int, val value: PdfObject)
