package io.github.yuroyami.kitepdf.content

import io.github.yuroyami.kitepdf.core.ByteReader
import io.github.yuroyami.kitepdf.parser.Lexer
import io.github.yuroyami.kitepdf.parser.Parser
import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfBoolean
import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfName
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
public data class Operation(
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

public object ContentStreamParser {

    /**
     * Hard cap on operations parsed from one content stream. An adversarial
     * (or repair-path-mangled) stream with hundreds of millions of operators
     * would otherwise allocate an unbounded operation list; past the cap we
     * stop parsing and render what was collected — lenient, like every other
     * salvage path here.
     */
    private const val MAX_OPS_PER_STREAM = 5_000_000

    /**
     * Parse the decoded content-stream bytes into a flat list of operations.
     *
     * Recovery (MuPDF parity, north-star gate #1): a single malformed operand or
     * operator must NOT abort the whole page. Any exception thrown while reading a
     * token/object is caught, the offending byte(s) are skipped, and parsing
     * resumes at the next operator. Forward progress is guaranteed: every catch
     * path advances the reader past the position where the failure was observed,
     * so a stubbornly-bad byte can never spin the loop.
     */
    public fun parse(bytes: ByteArray): List<Operation> {
        val reader = ByteReader(bytes)
        val lexer = Lexer(reader)
        val parser = Parser(lexer)
        val ops = mutableListOf<Operation>()
        val operandStack = mutableListOf<PdfObject>()

        while (true) {
            // Record where this iteration starts so a failure can guarantee progress.
            val loopStart = reader.pos()
            val tok = try {
                peekNonOperatorToken(lexer)
            } catch (_: Throwable) {
                // Lexer choked on garbage (bad hex digit, stray '>', unterminated
                // string, …). Skip forward and retry. peekNonOperatorToken may have
                // already advanced; if it did not, force one byte of progress.
                skipToProgress(reader, loopStart)
                operandStack.clear()
                if (reader.isAtEnd()) break else continue
            } ?: break

            when (tok) {
                is Token.Keyword -> {
                    // Inline images need special handling — "BI ... ID ... EI" is a mini stream.
                    if (tok.value == "BI") {
                        try {
                            consumeInlineImage(reader)
                            // Capture the whole "BI…EI" run verbatim so it survives a
                            // parse → edit → re-serialize round-trip.
                            ops.add(Operation("BI", emptyList(), bytes.copyOfRange(tok.offset, reader.pos())))
                        } catch (_: Throwable) {
                            // Malformed inline image: skip past it best-effort but keep
                            // the rest of the page.
                            skipToProgress(reader, loopStart)
                        }
                        operandStack.clear()
                    } else {
                        ops.add(Operation(tok.value, operandStack.toList()))
                        operandStack.clear()
                    }
                    if (ops.size >= MAX_OPS_PER_STREAM) return ops
                }
                Token.EndOfFile -> break
                else -> {
                    // Push back and let the Parser read a full object (handles arrays/dicts).
                    rewindToken(reader, tok)
                    try {
                        operandStack.add(parser.readObject())
                    } catch (_: Throwable) {
                        // A malformed operand (e.g. "[ ...unterminated", bad number).
                        // Drop the operands accumulated for this operator and skip
                        // the bad token, then continue with the next operator.
                        operandStack.clear()
                        skipToProgress(reader, loopStart)
                    }
                }
            }

            // Absolute backstop against an infinite loop: if a whole iteration made
            // no forward progress for any reason, force one byte forward.
            if (reader.pos() == loopStart && !reader.isAtEnd()) {
                reader.readByte()
            }
        }
        return ops
    }

    /**
     * Ensure the reader has advanced past [floor]. If it is still at or before
     * [floor], consume one byte so the outer loop cannot stall on a byte the
     * lexer/parser refuses to consume.
     */
    private fun skipToProgress(reader: ByteReader, floor: Int) {
        if (reader.pos() <= floor && !reader.isAtEnd()) {
            reader.seek(floor)
            reader.readByte()
        }
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

    /* ─── Inline images (ISO 32000-1 §8.9.7) ──────────────────────────────── */

    private val eiMarker = byteArrayOf('E'.code.toByte(), 'I'.code.toByte())

    /**
     * Inline image: `BI <dict-entries> ID <raw-data> EI`.
     *
     * The reader currently sits just past "BI". We:
     *  1. Lex the dict entries with the real [Lexer] up to the `ID` operator, so
     *     an "ID" byte pair that appears *inside* a dict value (e.g. a hex string
     *     or a name) is never mistaken for the data marker (bug #3).
     *  2. Consume exactly one whitespace byte after `ID`.
     *  3. Terminate the data:
     *       - UNFILTERED images: compute the exact byte length from the geometry
     *         (/W, /H, /BPC, color-space component count) and consume that many
     *         bytes, then expect `EI` (bug #2 — no scanning through binary data).
     *       - FILTERED images: scan for an `EI` that sits at a token boundary
     *         (preceded by whitespace OR by the start of data, and followed by
     *         whitespace / EOF / a delimiter). Whitespace before `EI` is NOT
     *         strictly required, so data ending flush against `EI` is handled.
     */
    private fun consumeInlineImage(reader: ByteReader) {
        val dict = lexInlineImageDictUpToId(reader)

        // One whitespace byte separates ID from the binary payload (§8.9.7).
        val nb = reader.peek()
        if (nb == ' '.code || nb == '\r'.code || nb == '\n'.code || nb == '\t'.code ||
            nb == 0 || nb == 12
        ) {
            reader.readByte()
        }

        val dataStart = reader.pos()
        val filtered = isFiltered(dict)
        if (!filtered) {
            val len = unfilteredDataLength(dict)
            if (len != null && dataStart + len <= reader.size) {
                reader.advance(len)
                expectEi(reader)
                return
            }
        }
        // Filtered, or geometry unknown/out of range: fall back to a boundary-aware
        // EI scan.
        scanForEi(reader, dataStart)
    }

    /**
     * Lex the inline-image dictionary entries (which follow "BI") until the `ID`
     * operator keyword. Returns the parsed key→value map with keys kept exactly
     * as written (short forms like "W", "BPC", "F", "CS"). The reader is left
     * positioned immediately after the "ID" keyword.
     *
     * Lexing (rather than raw-scanning for the bytes "ID") means an "ID" that
     * occurs inside a value is not a false terminator.
     */
    private fun lexInlineImageDictUpToId(reader: ByteReader): PdfDictionary {
        val lexer = Lexer(reader)
        val parser = Parser(lexer)
        val entries = LinkedHashMap<String, PdfObject>()
        while (true) {
            val keyTok = lexer.nextToken()
            when (keyTok) {
                Token.EndOfFile -> return PdfDictionary(entries)
                is Token.Keyword -> {
                    // The only keyword expected here is the "ID" data marker. Anything
                    // else is malformed; stop and let data handling take over.
                    return PdfDictionary(entries)
                }
                is Token.Name -> {
                    val value = parser.readObject()
                    entries[keyTok.value] = value
                }
                else -> {
                    // Unexpected token where a key was expected — malformed dict.
                    // Stop lexing; the reader sits just past this token.
                    return PdfDictionary(entries)
                }
            }
        }
    }

    /** Filter present (either /F or /Filter, direct name or non-empty array). */
    private fun isFiltered(dict: PdfDictionary): Boolean {
        val f = dict.map["F"] ?: dict.map["Filter"] ?: return false
        return when (f) {
            is PdfName -> f.value.isNotEmpty()
            is PdfArray -> f.items.isNotEmpty()
            else -> false
        }
    }

    /**
     * Exact byte length of an UNFILTERED inline-image sample stream:
     * ceil(W * bpc * components / 8) * H. Returns null if geometry is missing.
     */
    private fun unfilteredDataLength(dict: PdfDictionary): Int? {
        val w = intOf(dict, "W", "Width") ?: return null
        val h = intOf(dict, "H", "Height") ?: return null
        if (w <= 0 || h <= 0) return null

        val imageMask = boolOf(dict, "IM", "ImageMask") == true
        val bpc = if (imageMask) 1L else (intOf(dict, "BPC", "BitsPerComponent") ?: return null)
        val components = if (imageMask) 1 else colorComponents(dict) ?: return null

        val bitsPerRow = w * bpc * components
        val bytesPerRow = (bitsPerRow + 7) / 8
        val total = bytesPerRow * h
        if (total < 0 || total > Int.MAX_VALUE) return null
        return total.toInt()
    }

    /** Number of color components implied by the inline-image color space. */
    private fun colorComponents(dict: PdfDictionary): Int? {
        val cs = (dict.map["CS"] ?: dict.map["ColorSpace"])
        val name = (cs as? PdfName)?.value ?: return null
        return when (name) {
            "G", "DeviceGray", "CalGray", "I", "Indexed" -> 1
            "RGB", "DeviceRGB", "CalRGB", "Lab" -> 3
            "CMYK", "DeviceCMYK" -> 4
            else -> null // named/resource color space — component count unknown here
        }
    }

    private fun intOf(dict: PdfDictionary, vararg keys: String): Long? {
        for (k in keys) {
            (dict.map[k] as? PdfInt)?.let { return it.value }
        }
        return null
    }

    private fun boolOf(dict: PdfDictionary, vararg keys: String): Boolean? {
        for (k in keys) {
            val v = dict.map[k]
            if (v is PdfBoolean) return v.value
        }
        return null
    }

    /** After the exact data length, expect (and consume) the `EI` marker. */
    private fun expectEi(reader: ByteReader) {
        // Skip a single optional whitespace byte between data and EI.
        val nb = reader.peek()
        if (nb == ' '.code || nb == '\r'.code || nb == '\n'.code || nb == '\t'.code ||
            nb == 0 || nb == 12
        ) {
            reader.readByte()
        }
        if (reader.matches(eiMarker, reader.pos())) {
            reader.advance(2)
        } else {
            // Length was right but EI is not where expected — recover by scanning.
            scanForEi(reader, reader.pos())
        }
    }

    /**
     * Boundary-aware `EI` scan for filtered (or geometry-unknown) inline images.
     *
     * An `EI` is accepted only when it looks like the operator and not a stray
     * byte pair inside the payload:
     *   - it is at the start of the data OR preceded by a whitespace byte, AND
     *   - it is followed by whitespace, EOF, or a delimiter.
     * The whitespace-before requirement is relaxed to "start of data" so a
     * payload ending flush against `EI` (…dataEI) is matched (bug #2).
     *
     * If no boundary-valid `EI` exists, fall back to the first raw `EI` so the
     * scan cannot run to EOF and swallow the remainder of the page. If there is
     * none at all, position at EOF.
     */
    private fun scanForEi(reader: ByteReader, dataStart: Int) {
        val bytes = reader.bytes
        var i = dataStart
        var firstRaw = -1
        while (i <= bytes.size - 2) {
            if (bytes[i] == 'E'.code.toByte() && bytes[i + 1] == 'I'.code.toByte()) {
                if (firstRaw < 0) firstRaw = i
                val prevOk = i == dataStart ||
                    Lexer.isWhitespace(bytes[i - 1].toInt() and 0xFF)
                val after = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
                val nextOk = after == -1 ||
                    Lexer.isWhitespace(after) ||
                    Lexer.isDelimiter(after)
                if (prevOk && nextOk) {
                    reader.seek(i + 2)
                    return
                }
            }
            i++
        }
        if (firstRaw >= 0) {
            reader.seek(firstRaw + 2)
        } else {
            reader.seek(bytes.size)
        }
    }
}
