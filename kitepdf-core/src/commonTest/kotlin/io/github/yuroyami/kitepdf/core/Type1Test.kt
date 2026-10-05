package io.github.yuroyami.kitepdf.core

import io.github.yuroyami.kitepdf.core.font.Type1Font
import io.github.yuroyami.kitepdf.core.render.KitePath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Type 1 parser tests. We can't easily ship a real .pfb fixture, so we
 * construct minimal PostScript headers + encrypted CharStrings on the fly
 * and verify the decryption + charstring extraction round-trip.
 */
class Type1Test {

    /** eexec decryption inverts eexec encryption: closed-loop test. */
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

    /**
     * The number after `/CharStrings` counts glyphs. Read as a charstring length, it
     * skipped that many bytes and lost every glyph that started inside them.
     */
    @Test
    fun every_charstring_is_found_whatever_the_glyph_count() {
        val cs = csEncrypt(byteArrayOf(0, 0, 0, 0, 14))
        val privateText = "dup /Private 5 dict dup begin\n/lenIV 4 def\n/Subrs 0 array def\n/CharStrings 40 dict dup begin\n"
            .encodeToByteArray() +
            listOf(".notdef", "A", "B").fold(ByteArray(0)) { acc, name ->
                acc + "/$name ${cs.size} RD ".encodeToByteArray() + cs + "\nND\n".encodeToByteArray()
            } + "end\nend\n".encodeToByteArray()
        val eexec = eexecEncrypt(byteArrayOf(0, 0, 0, 0) + privateText)
        val header = "%!PS-AdobeFont-1.0: Three 001.000\n/FontName /Three def\ncurrentfile eexec\n".encodeToByteArray()
        val font = Type1Font.parse(header + eexec, header.size, eexec.size)
        assertEquals(setOf(".notdef", "A", "B"), font.glyphNames)
    }

    /**
     * A Type 1 font whose glyph /A is a 500 by 700 box, with [fontMatrix] as its
     * `/FontMatrix` line, or none.
     */
    private fun boxFont(fontMatrix: String?, inEexec: Boolean = false): Type1Font {
        fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
        // hsbw 0 500, rmoveto 0 0, rlineto 500 0, rlineto 0 700, rlineto -500 0, closepath, endchar.
        val charstring = csEncrypt(
            bytes(0, 0, 0, 0, 139, 248, 136, 13, 139, 139, 21, 248, 136, 139, 5, 139, 249, 80, 5, 252, 136, 139, 5, 9, 14),
        )
        val matrixLine = fontMatrix?.let { "$it\n" } ?: ""
        val privateText = ("dup /Private 5 dict dup begin\n" + (if (inEexec) matrixLine else "") +
            "/lenIV 4 def\n/Subrs 0 array def\n/CharStrings 1 dict dup begin\n/A ${charstring.size} RD ")
            .encodeToByteArray() + charstring + "\nND\nend\nend\n".encodeToByteArray()
        val eexec = eexecEncrypt(byteArrayOf(0, 0, 0, 0) + privateText)
        val header = ("%!PS-AdobeFont-1.0: BoxFont 001.000\n12 dict begin\n/FontName /BoxFont def\n" +
            (if (inEexec) "" else matrixLine) + "/Encoding StandardEncoding def\ncurrentdict end\ncurrentfile eexec\n")
            .encodeToByteArray()
        return Type1Font.parse(header + eexec, header.size, eexec.size)
    }

    /** The left, bottom, right and top of the points of glyph /A. */
    private fun boxBounds(font: Type1Font): List<Double> {
        val points = font.outlineForGlyphName("A")!!.segments.mapNotNull { s ->
            when (s) {
                is KitePath.Segment.MoveTo -> s.x to s.y
                is KitePath.Segment.LineTo -> s.x to s.y
                else -> null
            }
        }
        return listOf(points.minOf { it.first }, points.minOf { it.second }, points.maxOf { it.first }, points.maxOf { it.second })
    }

    /** The font's own `/FontMatrix` maps outlines into glyph space, 1000 units per em (#140). */
    @Test
    fun font_matrix_maps_outlines_into_glyph_space() {
        assertEquals(listOf(0.0, 0.0, 500.0, 700.0), boxBounds(boxFont(null)))
        assertEquals(listOf(0.0, 0.0, 500.0, 700.0), boxBounds(boxFont("/FontMatrix [0.001 0 0 0.001 0 0] readonly def")))
        assertEquals(listOf(0.0, 0.0, 250.0, 350.0), boxBounds(boxFont("/FontMatrix [0.0005 0 0 0.0005 0 0] readonly def")))
        // FreeType also reads the braces form and numbers without a leading zero.
        assertEquals(listOf(0.0, 0.0, 250.0, 350.0), boxBounds(boxFont("/FontMatrix {.0005 0 0 .0005 0 0} readonly def")))
        assertEquals(listOf(0.0, 0.0, 250.0, 350.0), boxBounds(boxFont("/FontMatrix [0.0005 0 0 0.0005 0 0] def", inEexec = true)))
        // No area, or a word where a number goes: the default applies.
        assertEquals(listOf(0.0, 0.0, 500.0, 700.0), boxBounds(boxFont("/FontMatrix [0 0 0 0 0 0] readonly def")))
        assertEquals(listOf(0.0, 0.0, 500.0, 700.0), boxBounds(boxFont("/FontMatrix [0.0005 0 0 x 0 0] readonly def")))
    }

    /**
     * A Type 1 font whose glyph /A runs [glyph] with [subrs] as its `/Subrs`, each program
     * charstring-encrypted behind four random bytes, as a font file stores it, or stored as it
     * is when [lenIV] is -1.
     */
    private fun subrFont(subrs: List<IntArray>, glyph: IntArray, lenIV: Int = 4): Type1Font {
        fun encrypted(program: IntArray): ByteArray {
            val bytes = ByteArray(program.size) { program[it].toByte() }
            return if (lenIV < 0) bytes else csEncrypt(ByteArray(lenIV) { 7 } + bytes)
        }
        val subrText = subrs.withIndex().fold("/Subrs ${subrs.size} array\n".encodeToByteArray()) { acc, (i, program) ->
            val bytes = encrypted(program)
            acc + "dup $i ${bytes.size} RD ".encodeToByteArray() + bytes + " NP\n".encodeToByteArray()
        }
        val charstring = encrypted(glyph)
        val privateText = "dup /Private 5 dict dup begin\n/lenIV $lenIV def\n".encodeToByteArray() + subrText +
            "ND\n/CharStrings 1 dict dup begin\n/A ${charstring.size} RD ".encodeToByteArray() + charstring +
            "\nND\nend\nend\n".encodeToByteArray()
        val eexec = eexecEncrypt(byteArrayOf(0, 0, 0, 0) + privateText)
        val header = "%!PS-AdobeFont-1.0: SubrFont 001.000\n/FontName /SubrFont def\ncurrentfile eexec\n".encodeToByteArray()
        return Type1Font.parse(header + eexec, header.size, eexec.size)
    }

    /** The segments of glyph /A that draw, without the closing ones. */
    private fun drawn(font: Type1Font): List<KitePath.Segment> =
        font.outlineForGlyphName("A")!!.segments.filter { it != KitePath.Segment.Close }

    /**
     * Each subroutine is charstring-encrypted on its own, as each charstring is. Run still
     * encrypted, a subroutine that draws part of a letter drew noise, so every letter of a
     * LaTeX font that calls one broke (#596).
     */
    @Test
    fun a_subroutine_is_decrypted_before_it_runs() {
        // Subr 0: rlineto 500 0, rlineto 0 700, rlineto -500 0, return.
        val sides = intArrayOf(248, 136, 139, 5, 139, 249, 80, 5, 252, 136, 139, 5, 11)
        // hsbw 0 500, rmoveto 0 0, 0 callsubr, closepath, endchar.
        val glyph = intArrayOf(139, 248, 136, 13, 139, 139, 21, 139, 10, 9, 14)
        assertEquals(
            listOf(
                KitePath.Segment.MoveTo(0.0, 0.0), KitePath.Segment.LineTo(500.0, 0.0),
                KitePath.Segment.LineTo(500.0, 700.0), KitePath.Segment.LineTo(0.0, 700.0),
            ),
            drawn(subrFont(listOf(sides), glyph)),
        )
    }

    /**
     * Flex reaches OtherSubrs 0, 1 and 2 through Subrs 0, 1 and 2, so a font's flex draws its
     * two curves only when those subroutines run decrypted. Without them, each flex point
     * drew a move, and the letter fell apart into pieces (#596).
     */
    @Test
    fun flex_through_the_standard_subroutines_draws_two_curves() {
        val subrs = listOf(
            intArrayOf(142, 139, 12, 16, 12, 17, 12, 17, 12, 33, 11), // 3 0 callothersubr pop pop setcurrentpoint return
            intArrayOf(139, 140, 12, 16, 11),                         // 0 1 callothersubr return
            intArrayOf(139, 141, 12, 16, 11),                         // 0 2 callothersubr return
        )
        val glyph = intArrayOf(
            139, 248, 136, 13,                  // hsbw 0 500
            139, 139, 21,                       // rmoveto 0 0
            140, 10,                            // 1 callsubr: flex begins
            247, 92, 139, 21, 141, 10,          // 200 0 rmoveto 2 callsubr: the reference point
            39, 159, 21, 141, 10,               // -100 20 rmoveto 2 callsubr
            189, 139, 21, 141, 10,              // 50 0 rmoveto 2 callsubr, four times
            189, 139, 21, 141, 10,
            189, 139, 21, 141, 10,
            189, 139, 21, 141, 10,
            239, 119, 21, 141, 10,              // 100 -20 rmoveto 2 callsubr
            189, 248, 36, 139, 139, 10,         // 50 400 0 0 callsubr: flex ends at (400, 0)
            139, 247, 192, 5,                   // rlineto 0 300
            252, 36, 139, 5,                    // rlineto -400 0
            9, 14,                              // closepath endchar
        )
        assertEquals(
            listOf(
                KitePath.Segment.MoveTo(0.0, 0.0),
                KitePath.Segment.CurveTo(100.0, 20.0, 150.0, 20.0, 200.0, 20.0),
                KitePath.Segment.CurveTo(250.0, 20.0, 300.0, 20.0, 400.0, 0.0),
                KitePath.Segment.LineTo(400.0, 300.0), KitePath.Segment.LineTo(0.0, 300.0),
            ),
            drawn(subrFont(subrs, glyph)),
        )
    }

    /**
     * A `/lenIV` of -1 says the charstrings and subroutines are not encrypted, as FreeType reads
     * it. Decrypted all the same, the glyph threw and drew nothing (#597).
     */
    @Test
    fun a_font_with_len_iv_minus_one_runs_its_programs_as_they_are() {
        val sides = intArrayOf(248, 136, 139, 5, 139, 249, 80, 5, 252, 136, 139, 5, 11)
        val glyph = intArrayOf(139, 248, 136, 13, 139, 139, 21, 139, 10, 9, 14)
        assertEquals(
            listOf(
                KitePath.Segment.MoveTo(0.0, 0.0), KitePath.Segment.LineTo(500.0, 0.0),
                KitePath.Segment.LineTo(500.0, 700.0), KitePath.Segment.LineTo(0.0, 700.0),
            ),
            drawn(subrFont(listOf(sides), glyph, lenIV = -1)),
        )
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
