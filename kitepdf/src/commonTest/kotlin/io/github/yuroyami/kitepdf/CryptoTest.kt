package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.crypto.Aes
import io.github.yuroyami.kitepdf.crypto.Md5
import io.github.yuroyami.kitepdf.crypto.Rc4
import io.github.yuroyami.kitepdf.crypto.Sha256
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Cryptographic primitive tests using published test vectors:
 *   - MD5 from RFC 1321 §A.5
 *   - SHA-256 from NIST FIPS 180-4 / RFC 6234
 *   - RC4 from RFC 6229
 *   - AES from FIPS 197 / NIST SP 800-38A
 */
class CryptoTest {

    @Test fun md5_empty() = assertHex("d41d8cd98f00b204e9800998ecf8427e", Md5.hash(byteArrayOf()))
    @Test fun md5_a() = assertHex("0cc175b9c0f1b6a831c399e269772661", Md5.hash("a".encodeToByteArray()))
    @Test fun md5_abc() = assertHex("900150983cd24fb0d6963f7d28e17f72", Md5.hash("abc".encodeToByteArray()))
    @Test
    fun md5_long_message() {
        // RFC 1321 §A.5: uppercase first, then lowercase, then digits — 62 bytes,
        // forces the two-block padding path.
        assertHex(
            "d174ab98d277d9f5a5611c2c9f419d9f",
            Md5.hash("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".encodeToByteArray()),
        )
    }

    @Test fun sha256_empty() =
        assertHex("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hash(byteArrayOf()))

    @Test fun sha256_abc() =
        assertHex("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hash("abc".encodeToByteArray()))

    @Test
    fun sha256_long() {
        assertHex(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hash("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
    }

    @Test
    fun rc4_rfc6229_key_8bit() {
        // RFC 6229 §3.1: key = 01020304050607080910111213141516, all-zero plaintext.
        val key = hexToBytes("0102030405")
        val output = Rc4.process(key, ByteArray(16))
        assertHex("b2396305f03dc027ccc3524a0a1118a8", output)
    }

    @Test
    fun rc4_rfc6229_key_128bit() {
        // RFC 6229 §3.3: key = 0102030405060708090a0b0c0d0e0f10, all-zero plaintext.
        val key = hexToBytes("0102030405060708090a0b0c0d0e0f10")
        val output = Rc4.process(key, ByteArray(16))
        assertHex("9ac7cc9a609d1ef7b2932899cde41b97", output)
    }

    @Test
    fun aes128_decrypt_appendix_c() {
        // FIPS 197 Appendix C.1 — 128-bit cipher key and ciphertext.
        val key = hexToBytes("000102030405060708090a0b0c0d0e0f")
        val ciphertext = hexToBytes("69c4e0d86a7b0430d8cdb78070b4c55a")
        val plaintext = Aes.decryptEcb(key, ciphertext)
        assertHex("00112233445566778899aabbccddeeff", plaintext)
    }

    @Test
    fun aes256_decrypt_appendix_c() {
        // FIPS 197 Appendix C.3 — 256-bit cipher key.
        val key = hexToBytes("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val ciphertext = hexToBytes("8ea2b7ca516745bfeafc49904b496089")
        val plaintext = Aes.decryptEcb(key, ciphertext)
        assertHex("00112233445566778899aabbccddeeff", plaintext)
    }

    @Test
    fun aes128_cbc_roundtrip() {
        val key = hexToBytes("2b7e151628aed2a6abf7158809cf4f3c")
        val iv = hexToBytes("000102030405060708090a0b0c0d0e0f")
        val plaintext = "Hello, PDF encryption!".encodeToByteArray()
        val ciphertext = Aes.encryptCbc(key, iv, plaintext)
        val decrypted = Aes.decryptCbc(key, ciphertext)
        assertContentEquals(plaintext, decrypted)
    }

    /* ─── Helpers ─────────────────────────────────────────────────────────── */

    private fun assertHex(expectedHex: String, actual: ByteArray) {
        assertEquals(expectedHex, actual.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') })
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { i ->
            ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
        }
    }
}
