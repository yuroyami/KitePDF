package io.github.yuroyami.kitepdf.epub

/**
 * De-obfuscates EPUB embedded fonts. Publishers mangle font files (to satisfy
 * foundry licensing) by XOR-ing a prefix with a key derived from the publication
 * identifier; without reversing it the font parses as tofu. Two algorithms exist,
 * signalled per-file in `META-INF/encryption.xml`:
 *
 *  - **IDPF** (`http://www.idpf.org/2008/embedding`): key = SHA-1 of the
 *    unique-identifier with all whitespace removed; XOR the first 1040 bytes.
 *  - **Adobe** (`http://ns.adobe.com/pdf/enc#RC`): key = the 16 bytes of the
 *    identifier's UUID hex; XOR the first 1024 bytes.
 *
 * XOR is its own inverse, so the same routine obfuscates and deobfuscates.
 */
internal object Deobfuscate {

    const val IDPF_ALGORITHM = "http://www.idpf.org/2008/embedding"
    const val ADOBE_ALGORITHM = "http://ns.adobe.com/pdf/enc#RC"

    /** Reverse [algorithm] obfuscation on [font] using [uniqueId], or return it unchanged. */
    fun deobfuscate(font: ByteArray, algorithm: String, uniqueId: String): ByteArray = when (algorithm) {
        IDPF_ALGORITHM -> idpf(font, uniqueId)
        ADOBE_ALGORITHM -> adobe(font, uniqueId)
        else -> font
    }

    fun idpf(font: ByteArray, uniqueId: String): ByteArray {
        val stripped = uniqueId.filterNot { it == ' ' || it == '\t' || it == '\r' || it == '\n' }
        return xorPrefix(font, Sha1.digest(stripped.encodeToByteArray()), 1040)
    }

    fun adobe(font: ByteArray, uniqueId: String): ByteArray {
        val key = adobeKey(uniqueId) ?: return font
        return xorPrefix(font, key, 1024)
    }

    private fun xorPrefix(font: ByteArray, key: ByteArray, count: Int): ByteArray {
        if (key.isEmpty()) return font
        val out = font.copyOf()
        val n = minOf(count, out.size)
        for (i in 0 until n) out[i] = (out[i].toInt() xor key[i % key.size].toInt()).toByte()
        return out
    }

    /** The 16 key bytes are the UUID's hex digits (dashes ignored; `urn:uuid:` prefix dropped). */
    private fun adobeKey(uniqueId: String): ByteArray? {
        // Drop any scheme prefix (e.g. `urn:uuid:`) so its letters can't leak into the hex.
        val hex = uniqueId.substringAfterLast(':').filter { hexVal(it) >= 0 }
        if (hex.length < 32) return null
        return ByteArray(16) { i -> ((hexVal(hex[i * 2]) shl 4) or hexVal(hex[i * 2 + 1])).toByte() }
    }

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
