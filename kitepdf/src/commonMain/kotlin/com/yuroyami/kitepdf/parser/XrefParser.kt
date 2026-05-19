package com.yuroyami.kitepdf.parser

import com.yuroyami.kitepdf.core.ByteReader
import com.yuroyami.kitepdf.core.PdfFormatException

/**
 * Cross-reference table + trailer parser (ISO 32000-1 §7.5).
 *
 * A PDF's catalog is found by:
 *   1. Reading the last ~1KB of the file for "startxref OFFSET".
 *   2. Seeking to OFFSET, which is either:
 *        - a classic "xref" table (textual, PDF 1.0-1.4 style), or
 *        - a "cross-reference stream" object (PDF 1.5+, more compact).
 *   3. Reading the /Trailer dict (or the stream's own dict) for /Root and
 *      /Prev pointers.
 *   4. Following /Prev to chain through any update sections.
 *
 * Session-1 scope: classic xref tables work. Xref streams are parsed as a
 * regular stream object and walked, but only Type-1 (uncompressed in-file)
 * and Type-2 (compressed inside an object stream) entries are decoded.
 * Type-0 (free) entries are recognized but skipped.
 */
class XrefParser(private val reader: ByteReader) {

    fun parse(): XrefAndTrailer {
        val startXref = findStartXref(reader)
        return parseFromOffset(startXref)
    }

    fun parseFromOffset(offset: Int): XrefAndTrailer {
        reader.seek(offset)
        // Peek: classic "xref" keyword OR an indirect object (xref stream).
        val savedPos = reader.pos()
        val firstToken = Lexer(reader).nextToken()
        return when {
            firstToken is Token.Keyword && firstToken.value == "xref" -> {
                parseClassicXrefAt(reader.pos())
            }
            firstToken is Token.Integer -> {
                reader.seek(savedPos)
                parseXrefStreamAt(savedPos)
            }
            else -> throw PdfFormatException("Unexpected token at xref start: $firstToken")
        }
    }

    /* ─── Classic textual xref table ─────────────────────────────────────── */

    private fun parseClassicXrefAt(bodyStart: Int): XrefAndTrailer {
        reader.seek(bodyStart)
        val entries = HashMap<Long, XrefEntry>()
        val lexer = Lexer(reader)

        // Read subsections: "first count\n N entries\n" until "trailer" keyword.
        while (true) {
            val tok = lexer.nextToken()
            if (tok is Token.Keyword && tok.value == "trailer") break
            if (tok !is Token.Integer) throw PdfFormatException("Expected subsection start, got $tok")
            val countTok = lexer.nextToken() as? Token.Integer
                ?: throw PdfFormatException("Expected entry count")
            val first = tok.value
            val count = countTok.value.toInt()

            // The spec says each entry is exactly 20 bytes "nnnnnnnnnn ggggg n \n",
            // but we tolerate variation by parsing tokens with the lexer.
            for (i in 0 until count) {
                val offsetTok = lexer.nextToken() as? Token.Integer
                    ?: throw PdfFormatException("Bad xref offset")
                val genTok = lexer.nextToken() as? Token.Integer
                    ?: throw PdfFormatException("Bad xref generation")
                val flagTok = lexer.nextToken() as? Token.Keyword
                    ?: throw PdfFormatException("Bad xref flag")
                val objNum = first + i
                if (flagTok.value == "n") {
                    entries[objNum] = XrefEntry.InUse(
                        objectNumber = objNum,
                        generation = genTok.value.toInt(),
                        byteOffset = offsetTok.value.toInt(),
                    )
                } else if (flagTok.value == "f") {
                    entries[objNum] = XrefEntry.Free(objNum)
                } else {
                    throw PdfFormatException("Unknown xref flag '${flagTok.value}'")
                }
            }
        }

        // Trailer dict follows the "trailer" keyword.
        val trailerTok = lexer.nextToken()
        if (trailerTok != Token.DictOpen) {
            throw PdfFormatException("Expected '<<' after 'trailer', got $trailerTok")
        }
        // Recreate a Parser to read the dictionary body (DictOpen already consumed).
        val parser = Parser(lexer)
        val trailerDict = readDictBodyHack(parser, lexer)
        return XrefAndTrailer(entries, trailerDict)
    }

    /**
     * Slight hack: Parser.readObject() expects the lexer to deliver DictOpen
     * itself, but here we've already consumed it. We rebuild the dict by
     * reading name/value pairs until DictClose.
     */
    private fun readDictBodyHack(parser: Parser, lexer: Lexer): PdfDictionary {
        val entries = LinkedHashMap<String, PdfObject>()
        while (true) {
            val tok = lexer.nextToken()
            if (tok == Token.DictClose) return PdfDictionary(entries)
            if (tok !is Token.Name) throw PdfFormatException("Dictionary key must be a Name, got $tok")
            entries[tok.value] = parser.readObject()
        }
    }

    /* ─── PDF 1.5+ cross-reference stream ────────────────────────────────── */

    private fun parseXrefStreamAt(offset: Int): XrefAndTrailer {
        // It's an indirect object whose payload is a stream with /Type /XRef.
        reader.seek(offset)
        val parser = Parser(Lexer(reader))
        val indirect = parser.readIndirectObject()
        val stream = indirect.value as? PdfStream
            ?: throw PdfFormatException("Expected xref stream object at $offset, got ${indirect.value}")
        val dict = stream.dict
        if (dict.getName("Type") != "XRef") {
            throw PdfFormatException("Object at xref offset is not /Type /XRef")
        }

        // Required: /W [a b c] field widths; /Size; optional /Index pairs (default [0 Size]).
        val w = dict.getArray("W")
            ?: throw PdfFormatException("Xref stream missing /W")
        val fieldWidths = w.map { (it as PdfInt).value.toInt() }
        if (fieldWidths.size != 3) throw PdfFormatException("/W must have 3 entries")
        val size = dict.getInt("Size")
            ?: throw PdfFormatException("Xref stream missing /Size")
        val index: List<Long> = dict.getArray("Index")?.map { (it as PdfInt).value }
            ?: listOf(0L, size)

        // Decode the stream body using our filter chain.
        val decoded = com.yuroyami.kitepdf.filters.FilterChain.decode(stream)
        val rowSize = fieldWidths.sum()
        if (rowSize == 0) throw PdfFormatException("Xref stream row size is zero")

        val entries = HashMap<Long, XrefEntry>()
        var cursor = 0
        var sub = 0
        while (sub < index.size) {
            val first = index[sub]
            val count = index[sub + 1].toInt()
            sub += 2
            for (i in 0 until count) {
                if (cursor + rowSize > decoded.size) {
                    throw PdfFormatException("Xref stream truncated at row ${first + i}")
                }
                val type = if (fieldWidths[0] == 0) 1 else readField(decoded, cursor, fieldWidths[0]).toInt()
                val f2 = readField(decoded, cursor + fieldWidths[0], fieldWidths[1])
                val f3 = readField(decoded, cursor + fieldWidths[0] + fieldWidths[1], fieldWidths[2])
                val objNum = first + i
                when (type) {
                    0 -> entries[objNum] = XrefEntry.Free(objNum)
                    1 -> entries[objNum] = XrefEntry.InUse(objNum, f3.toInt(), f2.toInt())
                    2 -> entries[objNum] = XrefEntry.Compressed(
                        objectNumber = objNum,
                        containingObjectStream = f2,
                        indexInObjectStream = f3.toInt(),
                    )
                    // Other types are reserved; ignore so we keep parsing.
                }
                cursor += rowSize
            }
        }
        return XrefAndTrailer(entries, dict)
    }

    private fun readField(data: ByteArray, offset: Int, width: Int): Long {
        var v = 0L
        for (i in 0 until width) {
            v = (v shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        return v
    }

    companion object {
        /** Walks back from EOF to find "startxref OFFSET". Returns the OFFSET. */
        fun findStartXref(reader: ByteReader): Int {
            val tail = (reader.size - 2048).coerceAtLeast(0)
            val startXrefBytes = "startxref".encodeToByteArray()
            val idx = reader.lastIndexOf(startXrefBytes, reader.size - 1)
            if (idx < tail) {
                throw PdfFormatException("startxref not found in last 2048 bytes")
            }
            reader.seek(idx + startXrefBytes.size)
            val lexer = Lexer(reader)
            val tok = lexer.nextToken()
            if (tok !is Token.Integer) throw PdfFormatException("Expected integer after startxref, got $tok")
            return tok.value.toInt()
        }
    }
}

/** A cross-reference entry. Distinct subtypes for the three xref-entry kinds. */
sealed class XrefEntry {
    abstract val objectNumber: Long

    /** Live object stored at [byteOffset] in the file with [generation] N. */
    data class InUse(
        override val objectNumber: Long,
        val generation: Int,
        val byteOffset: Int,
    ) : XrefEntry()

    /** Object number is unused / freed. */
    data class Free(override val objectNumber: Long) : XrefEntry()

    /**
     * Object stored inside an object-stream (PDF 1.5+ §7.5.7). Caller must
     * fetch the containing object stream and look up [indexInObjectStream].
     */
    data class Compressed(
        override val objectNumber: Long,
        val containingObjectStream: Long,
        val indexInObjectStream: Int,
    ) : XrefEntry()
}

/** What [XrefParser.parse] returns: the merged entry table and the trailer dict. */
data class XrefAndTrailer(
    val entries: Map<Long, XrefEntry>,
    val trailer: PdfDictionary,
)
