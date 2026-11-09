package io.github.yuroyami.kitepdf.parser

import io.github.yuroyami.kitepdf.core.parser.Lexer
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.parser.Token

import io.github.yuroyami.kitepdf.core.ByteReader
import io.github.yuroyami.kitepdf.core.PdfFormatException

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
 * Classic xref tables are fully supported. Xref streams are parsed as a
 * regular stream object and walked, but only Type-1 (uncompressed in-file)
 * and Type-2 (compressed inside an object stream) entries are decoded.
 * Type-0 (free) entries are recognized but skipped.
 */
internal class XrefParser(private val reader: ByteReader) {

    public fun parse(): XrefAndTrailer {
        val startXref = findStartXref(reader)
        return parseFromOffset(startXref)
    }

    /** The section at [offset], which in a source may lie past 2 GiB (ISO 32000-1, 7.5.4, #906). */
    public fun parseFromOffset(offset: Long): XrefAndTrailer {
        reader.seek(offset)
        // Peek: classic "xref" keyword OR an indirect object (xref stream).
        val savedPos = reader.posLong()
        val firstToken = Lexer(reader).nextToken()
        return when {
            firstToken is Token.Keyword && firstToken.value == "xref" -> {
                parseClassicXrefAt(reader.posLong())
            }
            firstToken is Token.Integer -> {
                reader.seek(savedPos)
                parseXrefStreamAt(savedPos)
            }
            else -> throw PdfFormatException("Unexpected token at xref start: $firstToken")
        }
    }

    /* ─── Classic textual xref table ─────────────────────────────────────── */

    private fun parseClassicXrefAt(bodyStart: Long): XrefAndTrailer {
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
                    // The offset has ten digits (7.5.4), so it can pass 2 GiB and stays a Long (#906).
                    entries[objNum] = XrefEntry.InUse(
                        objectNumber = objNum,
                        generation = genTok.value.toInt(),
                        offset = offsetTok.value,
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

    private fun parseXrefStreamAt(offset: Long): XrefAndTrailer {
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
        // ISO 32000-1, 7.5.8.2, Table 17: each width is a count of bytes, and no field wider than
        // a Long holds a value. A negative width made the row size negative, the bounds check
        // never fired, and /Index [0 N] made N entries from an empty stream (#969).
        if (fieldWidths.any { it !in 0..8 }) throw PdfFormatException("Xref stream /W $fieldWidths")
        val size = dict.getInt("Size")
            ?: throw PdfFormatException("Xref stream missing /Size")
        val index: List<Long> = dict.getArray("Index")?.map { (it as PdfInt).value }
            ?: listOf(0L, size)

        // Decode the stream body using our filter chain.
        val decoded = io.github.yuroyami.kitepdf.core.filters.FilterChain.decode(stream)
        val rowSize = fieldWidths.sum()
        if (rowSize == 0) throw PdfFormatException("Xref stream row size is zero")

        val entries = HashMap<Long, XrefEntry>()
        var cursor = 0
        var sub = 0
        while (sub + 1 < index.size) {
            val first = index[sub]
            val declared = index[sub + 1]
            sub += 2
            if (first < 0 || declared < 0) throw PdfFormatException("Xref stream /Index pair $first $declared")
            // Each entry is one row of the stream, so a count past the rows left is damage.
            val rowsLeft = (decoded.size - cursor) / rowSize
            if (declared > rowsLeft) throw PdfFormatException("Xref stream truncated at row ${first + rowsLeft}")
            val count = declared.toInt()
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
                    // Field 2 is the byte offset, /W bytes wide (7.5.8.3, Table 18), so it stays a Long (#906).
                    1 -> entries[objNum] = XrefEntry.InUse(objNum, f3.toInt(), f2)
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

    public companion object {
        /** Walks back from EOF to find "startxref OFFSET". Returns the OFFSET. */
        public fun findStartXref(reader: ByteReader): Long {
            val tail = (reader.sizeLong - 2048).coerceAtLeast(0)
            val startXrefBytes = "startxref".encodeToByteArray()
            val idx = reader.lastIndexOf(startXrefBytes, reader.sizeLong - 1)
            if (idx < tail) {
                throw PdfFormatException("startxref not found in last 2048 bytes")
            }
            reader.seek(idx + startXrefBytes.size)
            val lexer = Lexer(reader)
            val tok = lexer.nextToken()
            if (tok !is Token.Integer) throw PdfFormatException("Expected integer after startxref, got $tok")
            return tok.value
        }
    }
}

/** A cross-reference entry. Distinct subtypes for the three xref-entry kinds. */
public sealed class XrefEntry {
    public abstract val objectNumber: Long

    /**
     * Live object stored at [offset] in the file with [generation] N.
     *
     * A cross-reference table gives the offset in ten digits (ISO 32000-1, 7.5.4) and a
     * cross-reference stream in up to eight bytes (7.5.8.3), so a file read from a source can
     * put an object past 2 GiB, and [offset] is a [Long] (#906). This was a data class whose
     * [byteOffset] was an [Int]. Its members stay, so code built against it still runs.
     */
    public class InUse(
        override val objectNumber: Long,
        public val generation: Int,
        /** The byte offset of the object's `N G obj` header. */
        public val offset: Long,
    ) : XrefEntry() {

        /** An entry whose offset fits an [Int], as the data class made it. */
        public constructor(objectNumber: Long, generation: Int, byteOffset: Int) : this(objectNumber, generation, byteOffset.toLong())

        /** [offset] as an [Int]. Throws [IllegalStateException] for an object past 2 GiB, which only [offset] names. */
        @Deprecated("An object of a large file lies past 2 GiB; read offset", ReplaceWith("offset"))
        public val byteOffset: Int
            get() = if (offset <= Int.MAX_VALUE) offset.toInt() else throw IllegalStateException("Object $objectNumber lies at $offset, past 2 GiB; read offset")

        public operator fun component1(): Long = objectNumber

        public operator fun component2(): Int = generation

        @Deprecated("An object of a large file lies past 2 GiB; read offset", ReplaceWith("offset"))
        public operator fun component3(): Int = @Suppress("DEPRECATION") byteOffset

        @Deprecated("An object of a large file lies past 2 GiB; call the constructor with a Long offset")
        public fun copy(
            objectNumber: Long = this.objectNumber,
            generation: Int = this.generation,
            byteOffset: Int = @Suppress("DEPRECATION") this.byteOffset,
        ): InUse = InUse(objectNumber, generation, byteOffset.toLong())

        override fun equals(other: Any?): Boolean =
            other is InUse && objectNumber == other.objectNumber && generation == other.generation && offset == other.offset

        override fun hashCode(): Int = (objectNumber.hashCode() * 31 + generation) * 31 + offset.hashCode()

        override fun toString(): String = "InUse(objectNumber=$objectNumber, generation=$generation, offset=$offset)"
    }

    /** Object number is unused / freed. */
    public data class Free(override val objectNumber: Long) : XrefEntry()

    /**
     * Object stored inside an object-stream (PDF 1.5+ §7.5.7). Caller must
     * fetch the containing object stream and look up [indexInObjectStream].
     */
    public data class Compressed(
        override val objectNumber: Long,
        val containingObjectStream: Long,
        val indexInObjectStream: Int,
    ) : XrefEntry()
}

/** What [XrefParser.parse] returns: the merged entry table and the trailer dict. */
public data class XrefAndTrailer(
    val entries: Map<Long, XrefEntry>,
    val trailer: PdfDictionary,
)
