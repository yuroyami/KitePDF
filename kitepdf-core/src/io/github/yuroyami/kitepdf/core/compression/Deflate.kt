package io.github.yuroyami.kitepdf.core.compression

import io.github.yuroyami.archivekodec.codec.deflate.Deflate as ArchiveDeflate

/**
 * Raw DEFLATE (RFC 1951) encoding. The encoder is [ArchiveKodec](https://github.com/yuroyami/ArchiveKodec)'s.
 * [Zlib.encode] wraps it for PDF's FlateDecode.
 */
public object Deflate {

    /** Compress [data] into a bare DEFLATE stream. */
    @Deprecated(
        "Use ArchiveKodec's Deflate.encode",
        ReplaceWith("Deflate.encode(data)", "io.github.yuroyami.archivekodec.codec.deflate.Deflate"),
    )
    public fun encode(data: ByteArray): ByteArray = ArchiveDeflate.encode(data)
}
