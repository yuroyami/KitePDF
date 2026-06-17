package io.github.yuroyami.kitepdf.font

/**
 * Built-in CMaps named in `/Encoding` of Type 0 composite fonts.
 *
 * The PDF spec defines a long list of predefined CMaps (Identity-H/V plus
 * 35 or so CJK ones like GBK-EUC-H, UniJIS-UTF16-H). We ship Identity-H
 * and Identity-V (universal — used by virtually every modern Asian PDF) and
 * fall back to Identity-H for the named CJK variants.
 *
 * Identity-H / Identity-V interpret the byte stream as a sequence of
 * 2-byte big-endian unsigned integers; each integer IS the CID. There's
 * no codespace narrowing — every 2-byte pair maps directly.
 *
 * The CJK predefined CMaps would each map specific byte-sequence patterns
 * (e.g. EUC-JP, ShiftJIS, Big5) to CIDs in a font-specific registry.
 * Real CJK PDFs almost always embed a `/ToUnicode` CMap alongside, so the
 * `Identity-H` fallback at least keeps glyph indices correct (the bytes
 * round-trip cleanly because CID == big-endian-pair, just with a "wrong"
 * registry interpretation that doesn't affect glyph lookup in /CIDToGIDMap).
 */
internal interface CodeUnitReader {
    /** Read one code unit at [offset] from [bytes]; returns (code, bytesConsumed) or null on EOF. */
    fun next(bytes: ByteArray, offset: Int): Pair<Int, Int>?
}

internal object IdentityCodeUnitReader : CodeUnitReader {
    override fun next(bytes: ByteArray, offset: Int): Pair<Int, Int>? {
        if (offset >= bytes.size) return null
        // Strictly, Identity-H requires 2 bytes per code unit; a stray odd
        // trailing byte gets treated as a 1-byte CID so we don't drop data.
        if (offset + 1 >= bytes.size) {
            return (bytes[offset].toInt() and 0xFF) to 1
        }
        val cid = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
        return cid to 2
    }
}

internal object SingleByteCodeUnitReader : CodeUnitReader {
    override fun next(bytes: ByteArray, offset: Int): Pair<Int, Int>? {
        if (offset >= bytes.size) return null
        return (bytes[offset].toInt() and 0xFF) to 1
    }
}

internal object PredefinedCMaps {
    /**
     * Resolve a named `/Encoding` to a [CodeUnitReader]. Unknown names fall
     * back to Identity-H since most CJK CMaps are functionally Identity-H
     * for glyph-id resolution purposes (the CID registry differs but the
     * byte→CID mapping is "pair the bytes BE").
     */
    fun reader(name: String?): CodeUnitReader = when (name) {
        null -> SingleByteCodeUnitReader
        "Identity-H", "Identity-V" -> IdentityCodeUnitReader
        // Most other predefined CMaps are also 2-byte. Falling back to Identity-H
        // keeps glyphs resolvable; ToUnicode handles text extraction correctness.
        else -> if (name.endsWith("-H") || name.endsWith("-V")) IdentityCodeUnitReader
                else SingleByteCodeUnitReader
    }
}
