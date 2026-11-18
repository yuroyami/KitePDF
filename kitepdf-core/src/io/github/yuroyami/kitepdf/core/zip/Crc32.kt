package io.github.yuroyami.kitepdf.core.zip

import io.github.yuroyami.archivekodec.checksum.Crc32 as ArchiveCrc32

/**
 * CRC-32 (IEEE 802.3, polynomial `0xEDB88320`), the checksum ZIP stores for every entry. The
 * code is [ArchiveKodec](https://github.com/yuroyami/ArchiveKodec)'s.
 *
 * ```kotlin
 * Crc32.of("123456789".encodeToByteArray())   // 0xCBF43926
 * ```
 */
public object Crc32 {

    /** Checksum of [bytes] between [from] (inclusive) and [to] (exclusive). */
    public fun of(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Long =
        ArchiveCrc32().apply { update(bytes, from, to - from) }.value()
}
