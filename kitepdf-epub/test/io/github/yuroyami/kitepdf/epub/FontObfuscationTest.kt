package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** SHA-1 + IDPF/Adobe font de-obfuscation. */
class FontObfuscationTest {

    private fun ByteArray.hex() = joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

    @Test
    fun sha1_known_vectors() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", Sha1.digest("".encodeToByteArray()).hex())
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Sha1.digest("abc".encodeToByteArray()).hex())
        assertEquals(
            "84983e441c3bd26ebaae4aa1f95129e5e54670f1",
            Sha1.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).hex(),
        )
    }

    private fun font(size: Int) = ByteArray(size) { (it * 37 + 11 and 0xFF).toByte() }

    @Test
    fun idpf_xors_prefix_and_round_trips() {
        val id = "urn:uuid:9a0ca9dd-6ea1-49b1-a76c-7c5d3d5f3f4f"
        val original = font(2000)
        val obf = Deobfuscate.idpf(original, id)
        assertTrue(!obf.take(1040).toByteArray().contentEquals(original.take(1040).toByteArray()), "prefix changed")
        assertContentEquals(original.copyOfRange(1040, 2000), obf.copyOfRange(1040, 2000), "bytes past 1040 untouched")
        assertContentEquals(original, Deobfuscate.idpf(obf, id), "XOR is its own inverse")
        assertEquals((original[0].toInt() xor Sha1.digest(id.encodeToByteArray())[0].toInt()) and 0xFF, obf[0].toInt() and 0xFF)
    }

    @Test
    fun idpf_strips_whitespace_from_id() {
        val a = Deobfuscate.idpf(font(64), "urn:uuid:1234")
        val b = Deobfuscate.idpf(font(64), "  urn:uuid:1234\n")
        assertContentEquals(a, b, "whitespace in the identifier is ignored")
    }

    @Test
    fun adobe_key_from_uuid_hex() {
        val id = "urn:uuid:00112233-4455-6677-8899-aabbccddeeff"
        val original = font(1200)
        val obf = Deobfuscate.adobe(original, id)
        // key = 00 11 22 ... ff, repeating every 16 bytes over the first 1024.
        assertEquals(original[0].toInt() and 0xFF, obf[0].toInt() and 0xFF, "byte 0 XOR 0x00 unchanged")
        assertEquals((original[1].toInt() xor 0x11) and 0xFF, obf[1].toInt() and 0xFF)
        assertEquals((original[15].toInt() xor 0xFF) and 0xFF, obf[15].toInt() and 0xFF)
        assertContentEquals(original.copyOfRange(1024, 1200), obf.copyOfRange(1024, 1200), "past 1024 untouched")
        assertContentEquals(original, Deobfuscate.adobe(obf, id), "round trip")
    }

    @Test
    fun dispatch_by_algorithm() {
        val f = font(64)
        assertContentEquals(Deobfuscate.idpf(f, "x"), Deobfuscate.deobfuscate(f, Deobfuscate.IDPF_ALGORITHM, "x"))
        assertContentEquals(f, Deobfuscate.deobfuscate(f, "unknown-algo", "x"), "unknown algorithm passes through")
    }
}
