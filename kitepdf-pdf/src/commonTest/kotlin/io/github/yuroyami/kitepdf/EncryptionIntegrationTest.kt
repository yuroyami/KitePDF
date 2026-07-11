package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.crypto.Md5
import io.github.yuroyami.kitepdf.crypto.Rc4
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Closed-loop encryption test: build a tiny PDF, encrypt one stream + one
 * string with our crypto routines using the Standard Security Handler V1/R2
 * scheme (40-bit RC4 with empty password), then re-open and verify KitePDF's
 * Decryptor unwraps the plaintext.
 *
 * V1/R2 is intentional because it's the easiest to construct by hand — no
 * SHA-256, no AES, no key salts. The unwinding logic is the same shape as
 * V2/V4 with longer keys, so a passing V1 test gives us solid signal on the
 * pipeline.
 */
class EncryptionIntegrationTest {

    @Test
    fun opens_encrypted_pdf_with_empty_user_password() {
        val bytes = buildEncryptedPdf(visibleContent = "BT /F1 18 Tf 72 720 Td (Encrypted hello) Tj ET")
        val doc = KitePDF.open(bytes)
        assertTrue(doc.isEncrypted, "Document should be flagged encrypted")
        assertTrue(doc.isAuthenticated, "Empty password should authenticate /R=2 /U")
        assertEquals(1, doc.pageCount)
        assertContains(doc.pages[0].extractText(), "Encrypted hello")
    }

    /* ─── T-27: String password overload ─────────────────────────────────── */

    @Test
    fun string_overload_opens_rc4_with_ascii_password() {
        val bytes = buildEncryptedPdf(
            visibleContent = "BT /F1 18 Tf 72 720 Td (Legacy secret) Tj ET",
            userPassword = "secret".encodeToByteArray(),
        )
        val doc = PdfDocument.open(bytes, "secret")
        assertTrue(doc.isEncrypted && doc.isAuthenticated, "ASCII String password authenticates R2/RC4")
        assertContains(doc.pages[0].extractText(), "Legacy secret")
    }

    @Test
    fun string_overload_opens_aes256_with_non_ascii_password() {
        val bytes = io.github.yuroyami.kitepdf.writer.PdfBuilder()
            .page { text(io.github.yuroyami.kitepdf.writer.StandardFont.Helvetica, 18.0, 72.0, 720.0, "Tres secret") }
            .encrypt(userPassword = "h\u00e9llo", random = kotlin.random.Random(7))
            .build()
        val doc = PdfDocument.open(bytes, "h\u00e9llo")
        assertTrue(doc.isEncrypted && doc.isAuthenticated, "non-ASCII String password authenticates V5/R6 via UTF-8")
        assertContains(doc.pages[0].extractText(), "Tres secret")

        var wrong = false
        try {
            PdfDocument.open(bytes, "h\u00e8llo")
        } catch (_: io.github.yuroyami.kitepdf.core.WrongPasswordException) {
            wrong = true
        }
        assertTrue(wrong, "a different non-ASCII password must not authenticate")
    }

    /* ─── Encrypted-PDF builder ──────────────────────────────────────────── */

    private fun buildEncryptedPdf(visibleContent: String, userPassword: ByteArray = byteArrayOf()): ByteArray {
        // ── Encryption parameters (V=1, R=2, Length=40) ────────────────────
        val padding = standardPadding
        val paddedPassword = ByteArray(32).also {
            val n = minOf(userPassword.size, 32)
            userPassword.copyInto(it, 0, 0, n)
            padding.copyInto(it, n, 0, 32 - n)
        }
        val paddedOwner = ByteArray(32).also { padding.copyInto(it, 0) }      // empty owner pwd
        val permissions = -1
        val fileId = ByteArray(16) { it.toByte() }                            // arbitrary 16-byte ID

        // /O entry: Algorithm 3 for R=2.
        val ownerKey = Md5.hash(paddedOwner).copyOf(5)
        val oEntry = Rc4.process(ownerKey, paddedPassword)

        // File encryption key: Algorithm 2 for R=2.
        val keyMaterial = paddedPassword + oEntry + intToLittleEndian(permissions) + fileId
        val fileKey = Md5.hash(keyMaterial).copyOf(5)

        // /U entry: Algorithm 4 for R=2.
        val uEntry = Rc4.process(fileKey, padding)

        // ── Construct the PDF object graph ────────────────────────────────
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun write(s: String) = buf.append(s.encodeToByteArray())

        write("%PDF-1.4\n%Äå\n")

        // Object 1: catalog
        offsets.add(buf.size())
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

        // Object 2: pages root
        offsets.add(buf.size())
        write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")

        // Object 3: page
        offsets.add(buf.size())
        write("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
            "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n")

        // Object 4: font
        offsets.add(buf.size())
        write("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")

        // Object 5: encrypted content stream
        offsets.add(buf.size())
        val plaintextBytes = visibleContent.encodeToByteArray()
        val streamKey = perObjectKey(fileKey, objNum = 5, genNum = 0)
        val ciphertext = Rc4.process(streamKey, plaintextBytes)
        write("5 0 obj\n<< /Length ${ciphertext.size} >>\nstream\n")
        buf.append(ciphertext)
        write("\nendstream\nendobj\n")

        // Object 6: /Encrypt dictionary (referenced by trailer)
        offsets.add(buf.size())
        val oHex = oEntry.toHex()
        val uHex = uEntry.toHex()
        write("6 0 obj\n<< /Filter /Standard /V 1 /R 2 /Length 40 " +
            "/O <$oHex> /U <$uHex> /P $permissions >>\nendobj\n")

        // ── Xref + trailer ────────────────────────────────────────────────
        val xref = buf.size()
        write("xref\n0 7\n0000000000 65535 f \n")
        for (off in offsets) write("${off.toString().padStart(10, '0')} 00000 n \n")
        val idHex = fileId.toHex()
        write("trailer\n<< /Size 7 /Root 1 0 R /Encrypt 6 0 R /ID [<$idHex> <$idHex>] >>\n")
        write("startxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }

    private fun perObjectKey(fileKey: ByteArray, objNum: Long, genNum: Int): ByteArray {
        val input = ByteArray(fileKey.size + 5)
        fileKey.copyInto(input, 0)
        input[fileKey.size] = (objNum and 0xFF).toByte()
        input[fileKey.size + 1] = ((objNum ushr 8) and 0xFF).toByte()
        input[fileKey.size + 2] = ((objNum ushr 16) and 0xFF).toByte()
        input[fileKey.size + 3] = (genNum and 0xFF).toByte()
        input[fileKey.size + 4] = ((genNum ushr 8) and 0xFF).toByte()
        return Md5.hash(input).copyOf(minOf(fileKey.size + 5, 16))
    }

    private fun intToLittleEndian(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
    )

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val out = ByteArray(size + other.size)
        copyInto(out, 0); other.copyInto(out, size); return out
    }

    private val standardPadding = byteArrayOf(
        0x28, 0xBF.toByte(), 0x4E, 0x5E, 0x4E, 0x75, 0x8A.toByte(), 0x41,
        0x64, 0x00, 0x4E, 0x56, 0xFF.toByte(), 0xFA.toByte(), 0x01, 0x08,
        0x2E, 0x2E, 0x00, 0xB6.toByte(), 0xD0.toByte(), 0x68, 0x3E, 0x80.toByte(),
        0x2F, 0x0C, 0xA9.toByte(), 0xFE.toByte(), 0x64, 0x53, 0x69, 0x7A,
    )
}
