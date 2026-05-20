package com.yuroyami.kitepdf

import com.yuroyami.kitepdf.font.Type1Font
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Type 1 parser tests. We can't easily ship a real .pfb fixture, so we
 * construct minimal PostScript headers + encrypted CharStrings on the fly
 * and verify the decryption + charstring extraction round-trip.
 */
class Type1Test {

    /** eexec decryption inverts eexec encryption — closed-loop test. */
    @Test
    fun eexec_encrypt_then_decrypt_recovers_plaintext() {
        val plaintext = "Hello, Type 1!".encodeToByteArray()
        val withRandom = byteArrayOf(0, 0, 0, 0) + plaintext
        val encrypted = eexecEncrypt(withRandom)
        val decrypted = eexecDecrypt(encrypted).copyOfRange(4, withRandom.size)
        assertContentEquals(plaintext, decrypted)
    }

    /** Charstring decryption strips lenIV bytes (default 4). */
    @Test
    fun charstring_encrypt_then_decrypt_recovers_bytecode() {
        val bytecode = byteArrayOf(139.toByte(), 14)   // push 0, then endchar
        val withRandom = byteArrayOf(1, 2, 3, 4) + bytecode
        val encrypted = csEncrypt(withRandom)
        val decrypted = csDecrypt(encrypted).copyOfRange(4, withRandom.size)
        assertContentEquals(bytecode, decrypted)
    }

    /**
     * Build a minimal Type 1 font: header with /FontName /Encoding, then an
     * eexec-encrypted Private dict containing one charstring for /A that's a
     * tiny endchar program. Verify Type1Font.parse() finds the charstring.
     */
    @Test
    fun parse_minimal_type1_font_extracts_glyph_charstring() {
        // Charstring for "/A": just "endchar" (op 14), prefixed by 4 lenIV bytes.
        val charstringPlain = byteArrayOf(0, 0, 0, 0, 14.toByte())
        val charstringEncrypted = csEncrypt(charstringPlain)

        // Private dict + CharStrings inside the eexec block.
        val privateText = buildString {
            append("dup /Private 5 dict dup begin\n")
            append("/lenIV 4 def\n")
            append("/Subrs 0 array def\n")
            append("/CharStrings 2 dict dup begin\n")
            append("/.notdef 5 RD ")
        }.encodeToByteArray() + charstringEncrypted + ("\nND\n" +
            "/A 5 RD ").encodeToByteArray() + charstringEncrypted + ("\nND\n" +
            "end\nend\n").encodeToByteArray()

        // Random 4-byte prefix + private dict bytes → eexec-encrypt the whole thing.
        val eexecPlain = byteArrayOf(0, 0, 0, 0) + privateText
        val eexecEncrypted = eexecEncrypt(eexecPlain)

        // Cleartext PostScript header.
        val header = """
            |%!PS-AdobeFont-1.0: TestFont 001.000
            |12 dict begin
            |/FontName /TestFont def
            |/Encoding StandardEncoding def
            |currentdict end
            |currentfile eexec
            |""".trimMargin().encodeToByteArray()

        val fontFile = header + eexecEncrypted

        val font = Type1Font.parse(fontFile, header.size, eexecEncrypted.size)
        // CharStrings should at least include "A".
        assertEquals(true, font.hasGlyphName("A"))
        // And the outline should be the empty path (endchar with no drawing).
        val outline = font.outlineForGlyphName("A")
        assertEquals(true, outline != null)
    }

    /* ─── Helpers: encrypt routines (inverse of Type 1's decrypt) ───────── */

    private fun eexecEncrypt(plain: ByteArray): ByteArray = streamEncrypt(plain, seed = 55665)
    private fun eexecDecrypt(cipher: ByteArray): ByteArray = streamDecrypt(cipher, seed = 55665)
    private fun csEncrypt(plain: ByteArray): ByteArray = streamEncrypt(plain, seed = 4330)
    private fun csDecrypt(cipher: ByteArray): ByteArray = streamDecrypt(cipher, seed = 4330)

    private fun streamEncrypt(plain: ByteArray, seed: Int): ByteArray {
        val out = ByteArray(plain.size)
        var r = seed
        val c1 = 52845; val c2 = 22719
        for (i in plain.indices) {
            val cipher = (plain[i].toInt() and 0xFF) xor (r ushr 8)
            out[i] = (cipher and 0xFF).toByte()
            r = ((cipher + r) * c1 + c2) and 0xFFFF
        }
        return out
    }

    private fun streamDecrypt(cipher: ByteArray, seed: Int): ByteArray {
        val out = ByteArray(cipher.size)
        var r = seed
        val c1 = 52845; val c2 = 22719
        for (i in cipher.indices) {
            val c = cipher[i].toInt() and 0xFF
            val plain = c xor (r ushr 8)
            out[i] = (plain and 0xFF).toByte()
            r = ((c + r) * c1 + c2) and 0xFFFF
        }
        return out
    }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val out = ByteArray(size + other.size)
        copyInto(out, 0); other.copyInto(out, size); return out
    }
}
