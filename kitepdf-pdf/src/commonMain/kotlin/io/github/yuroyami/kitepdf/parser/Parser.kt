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
internal class Parser(
    private val lexer: Lexer,
    private val resolver: IndirectResolver? = null,
) {

    public constructor(bytes: ByteArray) : this(Lexer(ByteReader(bytes)))
    public constructor(bytes: ByteArray, resolver: IndirectResolver?) : this(Lexer(ByteReader(bytes)), resolver)

    private val reader: ByteReader get() = lexer.reader

    /** Read exactly one PDF object from the current position. */
    public fun readObject(): PdfObject {
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

        // Determine the stream body length. The declared /Length is preferred, but
        // real-world PDFs frequently carry a wrong or missing /Length (or an indirect
        // /Length we can't resolve in this context), so we fall back to scanning for
        // the "endstream" keyword — mirroring MuPDF's pdf_load_raw_stream recovery.
        val declared = resolveStreamLengthOrNull(dict)
        var bodyEnd = -1
        if (declared != null && declared in 0..(reader.size - streamStart).toLong()) {
            val candidate = streamStart + declared.toInt()
            if (endstreamAppearsAt(candidate)) bodyEnd = candidate
        }
        if (bodyEnd < 0) {
            val es = reader.indexOf(ENDSTREAM, streamStart)
            if (es < 0) throw PdfFormatException("Stream from $streamStart has no 'endstream'")
            // The EOL immediately before "endstream" is part of the keyword delimiter,
            // not the stream data (§7.3.8.1). Strip one trailing CR, LF, or CRLF.
            var e = es
            if (e > streamStart && reader.bytes[e - 1] == '\n'.code.toByte()) {
                e--
                if (e > streamStart && reader.bytes[e - 1] == '\r'.code.toByte()) e--
            } else if (e > streamStart && reader.bytes[e - 1] == '\r'.code.toByte()) {
                e--
            }
            bodyEnd = e
        }

        val raw = reader.bytes.copyOfRange(streamStart, bodyEnd)
        reader.seek(bodyEnd)
        // Consume up to and including the "endstream" keyword, leniently.
        skipToAfterEndstream()
        // Optional trailing "endobj" is handled by readIndirectObject.
        return PdfStream(dict, raw)
    }

    /** True if (after optional whitespace) "endstream" starts at/just after [at]. */
    private fun endstreamAppearsAt(at: Int): Boolean {
        var p = at
        val b = reader.bytes
        // Allow a small run of whitespace between the body and the keyword.
        var slack = 0
        while (p < b.size && slack < 3 && Lexer.isWhitespace(b[p].toInt() and 0xFF)) { p++; slack++ }
        return reader.matches(ENDSTREAM, p)
    }

    /** Advance the reader past the next "endstream" keyword (best effort). */
    private fun skipToAfterEndstream() {
        val es = reader.indexOf(ENDSTREAM, reader.pos())
        if (es >= 0) reader.seek(es + ENDSTREAM.size)
    }

    /** Resolve /Length to a Long, or null if missing/indirect-unresolvable/non-integer. */
    private fun resolveStreamLengthOrNull(dict: PdfDictionary): Long? {
        return when (val raw = dict["Length"]) {
            is PdfInt -> raw.value
            is PdfReference -> {
                val r = resolver ?: return null
                val resolvedPosBefore = reader.pos()
                val resolved = runCatching { r.resolve(raw) }.getOrNull()
                // The resolver may have moved the reader; restore our position.
                reader.seek(resolvedPosBefore)
                (resolved as? PdfInt)?.value
            }
            else -> null
        }
    }

    private fun consumeStreamEol() {
        // ISO 32000-1 §7.3.8.1: "stream" should be followed by CRLF or LF. We are
        // lenient: eat an optional CR and/or LF, but never throw if a producer
        // botched it — the body offset is taken from wherever we land.
        if (reader.peek() == '\r'.code) reader.readByte()
        if (reader.peek() == '\n'.code) reader.readByte()
    }

    /**
     * Parse a full indirect object: "N G obj <object> endobj".
     * The reader must currently sit at the start of "N G obj".
     */
    public fun readIndirectObject(): IndirectObject {
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

    private companion object {
        val ENDSTREAM = "endstream".encodeToByteArray()
    }
}

/** An object as it appears in the body: number, generation, and the value. */
public data class IndirectObject(val number: Long, val generation: Int, val value: PdfObject)
