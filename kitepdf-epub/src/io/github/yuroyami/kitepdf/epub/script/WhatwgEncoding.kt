package io.github.yuroyami.kitepdf.epub.script

/**
 * The encodings of the Encoding Standard (#532): every label of its table, the decoder of every
 * encoding, the encoder of UTF-8, and forgiving base64 for `atob` and `btoa`. The prelude's
 * `TextDecoder`, `TextEncoder`, `atob` and `btoa` stand on them.
 *
 * A script reaches no legacy encoder, so none is here: `TextEncoder` encodes only UTF-8, and the
 * forms and URLs that encode in a page's own encoding are not part of a book's scripts.
 */
internal object WhatwgEncoding {

    /** Each label, by itself, to the name of the encoding it names. */
    private val names: Map<String, String> by lazy {
        val out = HashMap<String, String>()
        for (line in ENCODING_LABELS.split('\n')) {
            val words = line.split(' ')
            for (label in words.subList(1, words.size)) out[label] = words[0]
        }
        out
    }

    /**
     * The name of the encoding that [label] names, by "get an encoding": ASCII white space
     * around it is dropped and case does not count. Null when it names none.
     */
    fun forLabel(label: String): String? {
        val trimmed = label.trim { isAsciiWhitespace(it) }
        // A label is ASCII, so a lower case that would change anything else cannot match one.
        if (trimmed.any { it.code > 0x7F }) return null
        return names[trimmed.lowercase()]
    }

    /**
     * The bytes of [text] as "decode" reads them for a resource: a byte order mark chooses the
     * encoding and goes, and else [fallback] decodes them, each error a U+FFFD.
     */
    fun decode(bytes: ByteArray, fallback: String): String {
        val b0 = if (bytes.isNotEmpty()) bytes[0].toInt() and 0xFF else -1
        val b1 = if (bytes.size > 1) bytes[1].toInt() and 0xFF else -1
        val b2 = if (bytes.size > 2) bytes[2].toInt() and 0xFF else -1
        val (encoding, skip) = when {
            b0 == 0xEF && b1 == 0xBB && b2 == 0xBF -> "UTF-8" to 3
            b0 == 0xFE && b1 == 0xFF -> "UTF-16BE" to 2
            b0 == 0xFF && b1 == 0xFE -> "UTF-16LE" to 2
            else -> fallback to 0
        }
        val decoder = WhatwgDecoder(encoding, fatal = false, ignoreBom = true)
        return checkNotNull(decoder.decode(bytes, skip, bytes.size, flush = true))
    }

    /** The UTF-8 bytes of [text], each lone surrogate as U+FFFD, as the UTF-8 encoder writes a USVString. */
    fun utf8Encode(text: String): ByteArray = utf8EncodeInto(text, Int.MAX_VALUE).second

    /**
     * What `encodeInto` writes of [text] into [capacity] bytes: how many code units of [text] it
     * reads, and the bytes, which end before the first code point that does not fit whole.
     */
    fun utf8EncodeInto(text: String, capacity: Int): Pair<Int, ByteArray> {
        val out = ByteBuffer(minOf(capacity.toLong(), text.length * 3L).toInt())
        var i = 0
        while (i < text.length) {
            var cp = text[i].code
            var units = 1
            if (cp in 0xD800..0xDFFF) {
                if (cp <= 0xDBFF && i + 1 < text.length && text[i + 1].code in 0xDC00..0xDFFF) {
                    cp = 0x10000 + ((cp - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
                    units = 2
                } else {
                    cp = 0xFFFD
                }
            }
            val length = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (capacity - out.size < length) break
            when (length) {
                1 -> out.add(cp)
                2 -> { out.add(0xC0 or (cp shr 6)); out.add(0x80 or (cp and 0x3F)) }
                3 -> { out.add(0xE0 or (cp shr 12)); out.add(0x80 or ((cp shr 6) and 0x3F)); out.add(0x80 or (cp and 0x3F)) }
                else -> {
                    out.add(0xF0 or (cp shr 18)); out.add(0x80 or ((cp shr 12) and 0x3F))
                    out.add(0x80 or ((cp shr 6) and 0x3F)); out.add(0x80 or (cp and 0x3F))
                }
            }
            i += units
        }
        return i to out.toArray()
    }

    /**
     * The bytes of [data] by forgiving-base64 decode (Infra Standard, 4.6), each a code unit of
     * the result as `atob` gives it, or null where it fails: white space goes, one or two `=`
     * may close a whole number of quads or be left off, and nothing else outside the alphabet may
     * appear.
     */
    fun atob(data: String): String? {
        val chars = StringBuilder(data.length)
        for (c in data) if (!isAsciiWhitespace(c)) chars.append(c)
        var n = chars.length
        if (n % 4 == 0 && n > 0 && chars[n - 1] == '=') {
            n--
            if (chars[n - 1] == '=') n--
        }
        if (n % 4 == 1) return null
        val out = StringBuilder(n * 3 / 4)
        var buffer = 0
        var bits = 0
        for (i in 0 until n) {
            val v = BASE64.indexOf(chars[i])
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.append(((buffer shr bits) and 0xFF).toChar())
            }
        }
        return out.toString()
    }

    /** [data] in base64 as `btoa` writes it, each code unit a byte, or null when one is over U+00FF. */
    fun btoa(data: String): String? {
        if (data.any { it.code > 0xFF }) return null
        val out = StringBuilder((data.length + 2) / 3 * 4)
        var i = 0
        while (i < data.length) {
            val b0 = data[i].code
            val b1 = if (i + 1 < data.length) data[i + 1].code else 0
            val b2 = if (i + 2 < data.length) data[i + 2].code else 0
            out.append(BASE64[b0 shr 2])
            out.append(BASE64[((b0 and 3) shl 4) or (b1 shr 4)])
            out.append(if (i + 1 < data.length) BASE64[((b1 and 15) shl 2) or (b2 shr 6)] else '=')
            out.append(if (i + 2 < data.length) BASE64[b2 and 63] else '=')
            i += 3
        }
        return out.toString()
    }

    private const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun isAsciiWhitespace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\u000C' || c == '\r'

    /** The index of each single-byte encoding, by name: the code point of each byte from 0x80, -1 for none. */
    private val singleByte: Map<String, IntArray> by lazy {
        val out = HashMap<String, IntArray>()
        for (line in SINGLE_BYTE_INDEXES.split('\n')) {
            val space = line.indexOf(' ')
            val v = Vlq(line.substring(space + 1))
            var prev = 0
            out[line.substring(0, space)] = IntArray(128) {
                val cp = prev + Vlq.unzigzag(v.next())
                if (cp >= 0) prev = cp
                cp
            }
        }
        out
    }

    fun singleByteIndex(encoding: String): IntArray? = singleByte[encoding]

    /** The code point of each pointer of a CJK index, -1 for none, from the runs the generator wrote. */
    private fun multiByte(data: String): IntArray {
        val v = Vlq(data)
        val out = IntArray(v.next()) { -1 }
        var pointer = -1
        var cp = 0
        while (!v.done) {
            pointer += v.next() + 1
            val run = v.next()
            for (k in 0 until run) {
                cp += Vlq.unzigzag(v.next())
                out[pointer + k] = cp
            }
            pointer += run - 1
        }
        return out
    }

    val big5: IntArray by lazy { multiByte(INDEX_BIG5) }
    val eucKr: IntArray by lazy { multiByte(INDEX_EUC_KR) }
    val gb18030: IntArray by lazy { multiByte(INDEX_GB18030) }
    val jis0208: IntArray by lazy { multiByte(INDEX_JIS0208) }
    val jis0212: IntArray by lazy { multiByte(INDEX_JIS0212) }

    /** The pointers of the gb18030 ranges, and their code points, in order. */
    private val gbRanges: Pair<IntArray, IntArray> by lazy {
        val v = Vlq(GB18030_RANGES)
        val pointers = IntBuffer(256)
        val cps = IntBuffer(256)
        var p = 0
        var c = 0
        while (!v.done) {
            p += v.next()
            c += v.next()
            pointers.add(p)
            cps.add(c)
        }
        pointers.toArray() to cps.toArray()
    }

    /** The index gb18030 ranges code point for [pointer], or -1. */
    fun gbRangesCodePoint(pointer: Int): Int {
        if ((pointer in 39420..188999) || pointer > 1237575) return -1
        if (pointer == 7457) return 0xE7C7
        val (pointers, cps) = gbRanges
        var lo = 0
        var hi = pointers.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (pointers[mid] <= pointer) lo = mid else hi = mid - 1
        }
        return cps[lo] + pointer - pointers[lo]
    }
}

/**
 * A decoder of the Encoding Standard for [encoding], by its name, with the TextDecoder's own
 * [ignoreBom] and BOM seen. Each call of [decode] goes on from where the one before it stopped,
 * until one flushes, as `TextDecoder.decode` with `{ stream: true }` does; [state] is all it
 * keeps, so a decoder made again from it goes on the same way.
 */
internal class WhatwgDecoder(
    val encoding: String,
    private val fatal: Boolean,
    private val ignoreBom: Boolean,
    state: List<Int>? = null,
) {
    private val kind: Kind = when (encoding) {
        "UTF-8" -> Kind.UTF8
        "UTF-16BE" -> Kind.UTF16BE
        "UTF-16LE" -> Kind.UTF16LE
        "gb18030", "GBK" -> Kind.GB18030
        "Big5" -> Kind.BIG5
        "EUC-JP" -> Kind.EUC_JP
        "ISO-2022-JP" -> Kind.ISO_2022_JP
        "Shift_JIS" -> Kind.SHIFT_JIS
        "EUC-KR" -> Kind.EUC_KR
        "replacement" -> Kind.REPLACEMENT
        "x-user-defined" -> Kind.X_USER_DEFINED
        else -> Kind.SINGLE_BYTE
    }

    private val singleByte: IntArray? = if (kind == Kind.SINGLE_BYTE) {
        requireNotNull(WhatwgEncoding.singleByteIndex(encoding)) { "no encoding $encoding" }
    } else {
        null
    }

    /** The variables of the decoder, which each kind names below. */
    private val v = IntArray(VARIABLES)

    /** Whether the TextDecoder has seen the first code point, which a BOM may be. */
    private var bomSeen = false

    /** Bytes restored to the input, read before any more of it. */
    private val pending = ArrayDeque<Int>()

    init {
        if (state == null) reset() else {
            for (i in 0 until VARIABLES) v[i] = state[i]
            bomSeen = state[VARIABLES] != 0
            for (i in VARIABLES + 1 until state.size) pending.addLast(state[i])
        }
    }

    /** All the decoder keeps between two calls: its variables, BOM seen, then the bytes restored to its input. */
    val state: List<Int> get() = v.toList() + (if (bomSeen) 1 else 0) + pending

    private fun reset() {
        v.fill(0)
        when (kind) {
            Kind.UTF8 -> { v[3] = 0x80; v[4] = 0xBF }
            Kind.UTF16BE, Kind.UTF16LE -> { v[0] = -1; v[1] = -1 }
            else -> {}
        }
    }

    /**
     * The text of [bytes] from [from] to [to], after what earlier calls left, and the end of the
     * queue too when [flush]. Null when the decoder is fatal and meets an error: the bytes it
     * has not read then wait in [state], before the bytes of the next call.
     */
    fun decode(bytes: ByteArray, from: Int = 0, to: Int = bytes.size, flush: Boolean): String? {
        val out = StringBuilder(to - from)
        var i = from
        while (true) {
            // The end of the queue is never taken out of it, so each read past the last byte sees it again.
            val byte = when {
                pending.isNotEmpty() -> pending.removeFirst()
                i < to -> bytes[i++].toInt() and 0xFF
                flush -> EOQ
                else -> return out.toString()
            }
            when (val result = handle(byte)) {
                FINISHED -> { pending.clear(); return out.toString() }
                CONTINUE -> {}
                ERROR -> {
                    if (fatal) {
                        while (i < to) pending.addLast(bytes[i++].toInt() and 0xFF)
                        return null
                    }
                    emit(out, 0xFFFD)
                }
                in BIG5_PAIRS -> {
                    emit(out, if (result - BIG5_PAIRS.first < 2) 0xCA else 0xEA)
                    emit(out, if ((result - BIG5_PAIRS.first) % 2 == 0) 0x304 else 0x30C)
                }
                else -> emit(out, result)
            }
        }
    }

    /** Appends [cp], but for a BOM that the TextDecoder drops, as "serialize I/O queue" does. */
    private fun emit(out: StringBuilder, cp: Int) {
        if (!ignoreBom && !bomSeen && (kind == Kind.UTF8 || kind == Kind.UTF16BE || kind == Kind.UTF16LE)) {
            bomSeen = true
            if (cp == 0xFEFF) return
        }
        appendCodePoint(out, cp)
    }

    private fun restore(vararg items: Int) {
        for (k in items.indices.reversed()) pending.addFirst(items[k])
    }

    /** The handler of the decoder for one byte, or [EOQ]: a code point, or [CONTINUE], [ERROR] or [FINISHED]. */
    private fun handle(byte: Int): Int = when (kind) {
        Kind.UTF8 -> utf8(byte)
        Kind.UTF16BE -> utf16(byte, bigEndian = true)
        Kind.UTF16LE -> utf16(byte, bigEndian = false)
        Kind.SINGLE_BYTE -> when {
            byte == EOQ -> FINISHED
            byte < 0x80 -> byte
            else -> singleByte!![byte - 0x80].let { if (it < 0) ERROR else it }
        }
        Kind.GB18030 -> gb18030(byte)
        Kind.BIG5 -> big5(byte)
        Kind.EUC_JP -> eucJp(byte)
        Kind.ISO_2022_JP -> iso2022Jp(byte)
        Kind.SHIFT_JIS -> shiftJis(byte)
        Kind.EUC_KR -> eucKr(byte)
        Kind.REPLACEMENT -> when {
            byte == EOQ -> FINISHED
            v[0] == 0 -> { v[0] = 1; ERROR }
            else -> FINISHED
        }
        Kind.X_USER_DEFINED -> when {
            byte == EOQ -> FINISHED
            byte < 0x80 -> byte
            else -> 0xF780 + byte - 0x80
        }
    }

    // UTF-8: v0 code point, v1 bytes seen, v2 bytes needed, v3 lower boundary, v4 upper boundary.
    private fun utf8(byte: Int): Int {
        if (byte == EOQ) {
            if (v[2] != 0) { v[2] = 0; return ERROR }
            return FINISHED
        }
        if (v[2] == 0) {
            when (byte) {
                in 0x00..0x7F -> return byte
                in 0xC2..0xDF -> { v[2] = 1; v[0] = byte and 0x1F }
                in 0xE0..0xEF -> {
                    if (byte == 0xE0) v[3] = 0xA0
                    if (byte == 0xED) v[4] = 0x9F
                    v[2] = 2
                    v[0] = byte and 0xF
                }
                in 0xF0..0xF4 -> {
                    if (byte == 0xF0) v[3] = 0x90
                    if (byte == 0xF4) v[4] = 0x8F
                    v[2] = 3
                    v[0] = byte and 0x7
                }
                else -> return ERROR
            }
            return CONTINUE
        }
        if (byte < v[3] || byte > v[4]) {
            v[0] = 0; v[1] = 0; v[2] = 0; v[3] = 0x80; v[4] = 0xBF
            restore(byte)
            return ERROR
        }
        v[3] = 0x80
        v[4] = 0xBF
        v[0] = (v[0] shl 6) or (byte and 0x3F)
        v[1]++
        if (v[1] != v[2]) return CONTINUE
        val cp = v[0]
        v[0] = 0; v[1] = 0; v[2] = 0
        return cp
    }

    // UTF-16: v0 leading byte, v1 leading surrogate, each -1 for null.
    private fun utf16(byte: Int, bigEndian: Boolean): Int {
        if (byte == EOQ) {
            if (v[0] >= 0 || v[1] >= 0) { v[0] = -1; v[1] = -1; return ERROR }
            return FINISHED
        }
        if (v[0] < 0) { v[0] = byte; return CONTINUE }
        val unit = if (bigEndian) (v[0] shl 8) + byte else (byte shl 8) + v[0]
        v[0] = -1
        if (v[1] >= 0) {
            val lead = v[1]
            v[1] = -1
            if (unit in 0xDC00..0xDFFF) return 0x10000 + ((lead - 0xD800) shl 10) + (unit - 0xDC00)
            val b1 = unit shr 8
            val b2 = unit and 0xFF
            if (bigEndian) restore(b1, b2) else restore(b2, b1)
            return ERROR
        }
        if (unit in 0xD800..0xDBFF) { v[1] = unit; return CONTINUE }
        if (unit in 0xDC00..0xDFFF) return ERROR
        return unit
    }

    // gb18030: v0 first, v1 second, v2 third.
    private fun gb18030(byte: Int): Int {
        if (byte == EOQ) {
            if (v[0] == 0 && v[1] == 0 && v[2] == 0) return FINISHED
            v[0] = 0; v[1] = 0; v[2] = 0
            return ERROR
        }
        if (v[2] != 0) {
            if (byte !in 0x30..0x39) {
                restore(v[1], v[2], byte)
                v[0] = 0; v[1] = 0; v[2] = 0
                return ERROR
            }
            val cp = WhatwgEncoding.gbRangesCodePoint(
                (v[0] - 0x81) * (10 * 126 * 10) + (v[1] - 0x30) * (10 * 126) + (v[2] - 0x81) * 10 + byte - 0x30,
            )
            v[0] = 0; v[1] = 0; v[2] = 0
            return if (cp < 0) ERROR else cp
        }
        if (v[1] != 0) {
            if (byte in 0x81..0xFE) { v[2] = byte; return CONTINUE }
            restore(v[1], byte)
            v[0] = 0; v[1] = 0
            return ERROR
        }
        if (v[0] != 0) {
            if (byte in 0x30..0x39) { v[1] = byte; return CONTINUE }
            val lead = v[0]
            v[0] = 0
            val offset = if (byte < 0x7F) 0x40 else 0x41
            val pointer = if (byte in 0x40..0x7E || byte in 0x80..0xFE) (lead - 0x81) * 190 + (byte - offset) else -1
            val cp = index(WhatwgEncoding.gb18030, pointer)
            if (cp >= 0) return cp
            if (byte < 0x80) restore(byte)
            return ERROR
        }
        return when (byte) {
            in 0x00..0x7F -> byte
            0x80 -> 0x20AC
            in 0x81..0xFE -> { v[0] = byte; CONTINUE }
            else -> ERROR
        }
    }

    // Big5: v0 leading.
    private fun big5(byte: Int): Int {
        if (byte == EOQ) {
            if (v[0] != 0) { v[0] = 0; return ERROR }
            return FINISHED
        }
        if (v[0] != 0) {
            val lead = v[0]
            v[0] = 0
            val offset = if (byte < 0x7F) 0x40 else 0x62
            val pointer = if (byte in 0x40..0x7E || byte in 0xA1..0xFE) (lead - 0x81) * 157 + (byte - offset) else -1
            // The four pointers whose characters are two code points each.
            when (pointer) {
                1133 -> return BIG5_PAIRS.first
                1135 -> return BIG5_PAIRS.first + 1
                1164 -> return BIG5_PAIRS.first + 2
                1166 -> return BIG5_PAIRS.first + 3
            }
            val cp = index(WhatwgEncoding.big5, pointer)
            if (cp >= 0) return cp
            if (byte < 0x80) restore(byte)
            return ERROR
        }
        return when (byte) {
            in 0x00..0x7F -> byte
            in 0x81..0xFE -> { v[0] = byte; CONTINUE }
            else -> ERROR
        }
    }

    // EUC-JP: v0 leading, v1 jis0212.
    private fun eucJp(byte: Int): Int {
        if (byte == EOQ) {
            if (v[0] != 0) { v[0] = 0; return ERROR }
            return FINISHED
        }
        if (v[0] == 0x8E && byte in 0xA1..0xDF) {
            v[0] = 0
            return 0xFF61 - 0xA1 + byte
        }
        if (v[0] == 0x8F && byte in 0xA1..0xFE) {
            v[1] = 1
            v[0] = byte
            return CONTINUE
        }
        if (v[0] != 0) {
            val lead = v[0]
            v[0] = 0
            var cp = -1
            if (lead in 0xA1..0xFE && byte in 0xA1..0xFE) {
                cp = index(if (v[1] == 0) WhatwgEncoding.jis0208 else WhatwgEncoding.jis0212, (lead - 0xA1) * 94 + byte - 0xA1)
            }
            v[1] = 0
            if (cp >= 0) return cp
            if (byte < 0x80) restore(byte)
            return ERROR
        }
        return when (byte) {
            in 0x00..0x7F -> byte
            0x8E, 0x8F, in 0xA1..0xFE -> { v[0] = byte; CONTINUE }
            else -> ERROR
        }
    }

    // ISO-2022-JP: v0 decoder state, v1 output state, v2 leading, v3 output.
    private fun iso2022Jp(byte: Int): Int {
        when (v[0]) {
            ASCII -> return when {
                byte == 0x1B -> { v[0] = ESCAPE_START; CONTINUE }
                byte == EOQ -> FINISHED
                byte in 0x00..0x7F && byte != 0x0E && byte != 0x0F -> { v[3] = 0; byte }
                else -> { v[3] = 0; ERROR }
            }
            ROMAN -> return when {
                byte == 0x1B -> { v[0] = ESCAPE_START; CONTINUE }
                byte == 0x5C -> { v[3] = 0; 0xA5 }
                byte == 0x7E -> { v[3] = 0; 0x203E }
                byte == EOQ -> FINISHED
                byte in 0x00..0x7F && byte != 0x0E && byte != 0x0F -> { v[3] = 0; byte }
                else -> { v[3] = 0; ERROR }
            }
            KATAKANA -> return when {
                byte == 0x1B -> { v[0] = ESCAPE_START; CONTINUE }
                byte in 0x21..0x5F -> { v[3] = 0; 0xFF61 - 0x21 + byte }
                byte == EOQ -> FINISHED
                else -> { v[3] = 0; ERROR }
            }
            LEADING_BYTE -> return when {
                byte == 0x1B -> { v[0] = ESCAPE_START; CONTINUE }
                byte in 0x21..0x7E -> { v[3] = 0; v[2] = byte; v[0] = TRAILING_BYTE; CONTINUE }
                byte == EOQ -> FINISHED
                else -> { v[3] = 0; ERROR }
            }
            TRAILING_BYTE -> return when {
                byte == 0x1B -> { v[0] = ESCAPE_START; ERROR }
                byte in 0x21..0x7E -> {
                    v[0] = LEADING_BYTE
                    val cp = index(WhatwgEncoding.jis0208, (v[2] - 0x21) * 94 + byte - 0x21)
                    if (cp < 0) ERROR else cp
                }
                else -> { v[0] = LEADING_BYTE; ERROR }
            }
            ESCAPE_START -> {
                if (byte == 0x24 || byte == 0x28) {
                    v[2] = byte
                    v[0] = ESCAPE
                    return CONTINUE
                }
                if (byte != EOQ) restore(byte)
                v[3] = 0
                v[0] = v[1]
                return ERROR
            }
            else -> {
                val lead = v[2]
                v[2] = 0
                val state = when {
                    lead == 0x28 && byte == 0x42 -> ASCII
                    lead == 0x28 && byte == 0x4A -> ROMAN
                    lead == 0x28 && byte == 0x49 -> KATAKANA
                    lead == 0x24 && (byte == 0x40 || byte == 0x42) -> LEADING_BYTE
                    else -> -1
                }
                if (state >= 0) {
                    v[0] = state
                    v[1] = state
                    val output = v[3]
                    v[3] = 1
                    return if (output == 0) CONTINUE else ERROR
                }
                if (byte == EOQ) restore(lead) else restore(lead, byte)
                v[3] = 0
                v[0] = v[1]
                return ERROR
            }
        }
    }

    // Shift_JIS: v0 leading.
    private fun shiftJis(byte: Int): Int {
        if (byte == EOQ) {
            if (v[0] != 0) { v[0] = 0; return ERROR }
            return FINISHED
        }
        if (v[0] != 0) {
            val lead = v[0]
            v[0] = 0
            val offset = if (byte < 0x7F) 0x40 else 0x41
            val leadOffset = if (lead < 0xA0) 0x81 else 0xC1
            val pointer = if (byte in 0x40..0x7E || byte in 0x80..0xFC) (lead - leadOffset) * 188 + byte - offset else -1
            if (pointer in 8836..10715) return 0xE000 - 8836 + pointer
            val cp = index(WhatwgEncoding.jis0208, pointer)
            if (cp >= 0) return cp
            if (byte < 0x80) restore(byte)
            return ERROR
        }
        return when (byte) {
            in 0x00..0x80 -> byte
            in 0xA1..0xDF -> 0xFF61 - 0xA1 + byte
            in 0x81..0x9F, in 0xE0..0xFC -> { v[0] = byte; CONTINUE }
            else -> ERROR
        }
    }

    // EUC-KR: v0 leading.
    private fun eucKr(byte: Int): Int {
        if (byte == EOQ) {
            if (v[0] != 0) { v[0] = 0; return ERROR }
            return FINISHED
        }
        if (v[0] != 0) {
            val lead = v[0]
            v[0] = 0
            val pointer = if (byte in 0x41..0xFE) (lead - 0x81) * 190 + (byte - 0x41) else -1
            val cp = index(WhatwgEncoding.eucKr, pointer)
            if (cp >= 0) return cp
            if (byte < 0x80) restore(byte)
            return ERROR
        }
        return when (byte) {
            in 0x00..0x7F -> byte
            in 0x81..0xFE -> { v[0] = byte; CONTINUE }
            else -> ERROR
        }
    }

    private fun index(table: IntArray, pointer: Int): Int = if (pointer in table.indices) table[pointer] else -1

    private enum class Kind { UTF8, UTF16BE, UTF16LE, SINGLE_BYTE, GB18030, BIG5, EUC_JP, ISO_2022_JP, SHIFT_JIS, EUC_KR, REPLACEMENT, X_USER_DEFINED }

    private companion object {
        const val VARIABLES = 5

        /** The end of the queue, as a byte. */
        const val EOQ = -1

        // What a handler answers that is not a code point, which is never negative.
        const val CONTINUE = -2
        const val ERROR = -3
        const val FINISHED = -4

        /**
         * What the Big5 handler answers for the four characters that are each a letter and a mark,
         * E or e with a circumflex and then a macron or a caron: numbers past every code point.
         */
        val BIG5_PAIRS = 0x110000..0x110003

        // The states of the ISO-2022-JP decoder.
        const val ASCII = 0
        const val ROMAN = 1
        const val KATAKANA = 2
        const val LEADING_BYTE = 3
        const val TRAILING_BYTE = 4
        const val ESCAPE_START = 5
        const val ESCAPE = 6
    }
}

/** A growable list of bytes, from ints, without boxing. */
private class ByteBuffer(capacity: Int) {
    private var items = ByteArray(maxOf(capacity, 16))
    var size: Int = 0
        private set

    fun add(value: Int) {
        if (size == items.size) items = items.copyOf(size * 2)
        items[size++] = value.toByte()
    }

    fun toArray(): ByteArray = items.copyOf(size)
}
