package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.font.TtfFormatException
import io.github.yuroyami.kitepdf.writer.EmbeddedFont
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Guards [EmbeddedFont.load]'s input validation. The full embed/round-trip path
 * (TrueType and OpenType/CFF) needs a real font file and runs in the JVM oracle
 * tests (`EmbeddedFontOracleTest`, `CffEmbedOracleTest`), where the filesystem
 * and `mutool` are available.
 */
class EmbeddedFontTest {

    @Test fun load_rejects_malformed_sfnt() {
        // An "OTTO" scaler with no table directory — OpenType/CFF is supported now,
        // but this truncated header has no parseable tables, so it's a format error.
        val otto = byteArrayOf(0x4F, 0x54, 0x54, 0x4F, 0, 0, 0, 0, 0, 0, 0, 0)
        assertFailsWith<TtfFormatException> { EmbeddedFont.load(otto) }
    }

    @Test fun load_rejects_truetype_collection() {
        // "ttcf" sfnt scaler — a TrueType Collection.
        val ttc = byteArrayOf(0x74, 0x74, 0x63, 0x66, 0, 0, 0, 0, 0, 0, 0, 0)
        assertFailsWith<UnsupportedOperationException> { EmbeddedFont.load(ttc) }
    }

    @Test fun load_rejects_tiny_input() {
        assertFailsWith<IllegalArgumentException> { EmbeddedFont.load(byteArrayOf(1, 2, 3)) }
    }
}
