package io.github.yuroyami.kitepdf.core.parser

import io.github.yuroyami.kitepdf.core.PdfFormatException

/**
 * Sealed hierarchy of PDF value types (ISO 32000-1 §7.3).
 *
 * Numbers split into Int/Real because both syntax and downstream usage care.
 * Strings keep raw bytes — PDF strings are NOT Kotlin Strings; they're byte
 * sequences interpreted via a font's encoding, so the user (or a text
 * extractor) decides how to decode them.
 */
public sealed class PdfObject {

    /** Resolve indirect refs against [refs]; non-refs return themselves. */
    public open fun resolve(refs: IndirectResolver): PdfObject = this
}

public object PdfNull : PdfObject() {
    override fun toString(): String = "null"
}

public data class PdfBoolean(val value: Boolean) : PdfObject() {
    override fun toString(): String = value.toString()
}

public data class PdfInt(val value: Long) : PdfObject() {
    override fun toString(): String = value.toString()
}

public data class PdfReal(val value: Double) : PdfObject() {
    override fun toString(): String = value.toString()
}

/**
 * A byte-level PDF string. [bytes] is the raw payload AFTER PDF escape
 * processing (so backslash escapes inside literal strings are already
 * resolved, and hex strings are already decoded to bytes).
 *
 * Whether these bytes are ASCII, UTF-16BE, PDFDocEncoding, or a font-specific
 * encoding is determined by context. asAsciiOrNull() is a convenience for the
 * common case of trailer strings, names of fonts, etc.
 */
public data class PdfString(val bytes: ByteArray) : PdfObject() {

    public fun asAsciiOrNull(): String? {
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val i = b.toInt() and 0xFF
            if (i > 127) return null
            sb.append(i.toChar())
        }
        return sb.toString()
    }

    /**
     * Decode as text. Auto-detects UTF-16BE (PDF text-string convention when
     * the first two bytes are 0xFE 0xFF), otherwise falls back to PDFDocEncoding
     * (close enough to latin-1 for the ASCII range; full table is in §D.2).
     */
    public fun asText(): String {
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            val sb = StringBuilder((bytes.size - 2) / 2)
            var i = 2
            while (i + 1 < bytes.size) {
                val hi = bytes[i].toInt() and 0xFF
                val lo = bytes[i + 1].toInt() and 0xFF
                sb.append(((hi shl 8) or lo).toChar())
                i += 2
            }
            return sb.toString()
        }
        val sb = StringBuilder(bytes.size)
        for (b in bytes) sb.append((b.toInt() and 0xFF).toChar())
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean =
        other is PdfString && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "(${asAsciiOrNull() ?: "<${bytes.size} bytes>"})"
}

/** PDF Name (always starts with "/"). Stored without the leading slash. */
public data class PdfName(val value: String) : PdfObject() {
    override fun toString(): String = "/$value"
}

public data class PdfArray(val items: List<PdfObject>) : PdfObject(), List<PdfObject> by items {
    override fun toString(): String = items.joinToString(prefix = "[", postfix = "]", separator = " ")
}

public data class PdfDictionary(val map: Map<String, PdfObject>) : PdfObject(), Map<String, PdfObject> by map {

    public fun getName(key: String): String? = (map[key] as? PdfName)?.value
    public fun getInt(key: String): Long? = (map[key] as? PdfInt)?.value
    public fun getReal(key: String): Double? = when (val v = map[key]) {
        is PdfReal -> v.value
        is PdfInt -> v.value.toDouble()
        else -> null
    }
    public fun getDict(key: String, refs: IndirectResolver? = null): PdfDictionary? {
        val raw = map[key] ?: return null
        val resolved = refs?.let { raw.resolve(it) } ?: raw
        return resolved as? PdfDictionary
    }
    public fun getArray(key: String, refs: IndirectResolver? = null): PdfArray? {
        val raw = map[key] ?: return null
        val resolved = refs?.let { raw.resolve(it) } ?: raw
        return resolved as? PdfArray
    }
    public fun getRef(key: String): PdfReference? = map[key] as? PdfReference

    override fun toString(): String = map.entries.joinToString(
        prefix = "<<", postfix = ">>", separator = " ",
    ) { (k, v) -> "/$k $v" }
}

/**
 * A stream object: a dictionary describing the stream + the raw (still-encoded)
 * bytes. Decoding is deferred until someone calls a filter on it.
 */
public data class PdfStream(
    val dict: PdfDictionary,
    val rawBytes: ByteArray,
) : PdfObject() {

    override fun equals(other: Any?): Boolean =
        other is PdfStream && dict == other.dict && rawBytes.contentEquals(other.rawBytes)

    override fun hashCode(): Int = 31 * dict.hashCode() + rawBytes.contentHashCode()

    override fun toString(): String = "stream(${rawBytes.size} bytes, dict=$dict)"
}

/** "N G R" — an indirect object reference. Use [resolve] with the document's resolver. */
public data class PdfReference(val objectNumber: Long, val generation: Int) : PdfObject() {

    override fun resolve(refs: IndirectResolver): PdfObject =
        refs.resolve(this) ?: throw PdfFormatException("Dangling reference $objectNumber $generation R")

    override fun toString(): String = "$objectNumber $generation R"
}

/** Functional interface implemented by the document's xref-driven object cache. */
public fun interface IndirectResolver {
    public fun resolve(ref: PdfReference): PdfObject?
}
