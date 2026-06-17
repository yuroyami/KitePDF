package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.compression.Zlib
import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfName
import io.github.yuroyami.kitepdf.parser.PdfObject
import io.github.yuroyami.kitepdf.parser.PdfStream

/**
 * Builders for stream objects to hand to the writer ([PdfEditor]).
 *
 * `/Length` is informational here — [PdfObjectWriter] always rewrites it to the
 * actual serialized payload size — but it's set for a self-consistent dict.
 */
object PdfStreams {

    /** A stream with no encoding filter, holding [data] verbatim. */
    fun raw(data: ByteArray, extra: Map<String, PdfObject> = emptyMap()): PdfStream {
        val dict = LinkedHashMap<String, PdfObject>()
        dict.putAll(extra)
        dict["Length"] = PdfInt(data.size.toLong())
        return PdfStream(PdfDictionary(dict), data)
    }

    /**
     * A `/FlateDecode` stream compressing uncompressed [data]. [extra] supplies
     * additional dictionary entries (e.g. `/Type`, `/Subtype`); do not pass
     * `/Filter` in [extra] — this always sets it to `/FlateDecode`.
     *
     * Note: very small or high-entropy inputs may compress to *more* bytes than
     * the original (zlib + block overhead); use [raw] when that matters.
     */
    fun flate(data: ByteArray, extra: Map<String, PdfObject> = emptyMap()): PdfStream {
        val compressed = Zlib.encode(data)
        val dict = LinkedHashMap<String, PdfObject>()
        dict.putAll(extra)
        dict["Filter"] = PdfName("FlateDecode")
        dict["Length"] = PdfInt(compressed.size.toLong())
        return PdfStream(PdfDictionary(dict), compressed)
    }
}
