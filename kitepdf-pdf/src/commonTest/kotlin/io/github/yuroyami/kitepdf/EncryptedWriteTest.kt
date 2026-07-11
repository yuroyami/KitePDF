package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.WrongPasswordException
import io.github.yuroyami.kitepdf.crypto.Md5
import io.github.yuroyami.kitepdf.crypto.Rc4
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-83: creating and editing encrypted PDFs. [PdfBuilder.encrypt] mints
 * V5/AES-256 R6 documents; [PdfDocument.edit] re-encrypts staged objects for
 * AES documents and refuses RC4. Randomness is seeded throughout so the
 * output bytes are reproducible.
 */
class EncryptedWriteTest {

    private val userPw = "hunter2"
    private val ownerPw = "admin-secret"

    private fun buildEncrypted(seed: Int = 42): ByteArray = PdfBuilder()
        .setInfo(title = "Secret Report")
        .encrypt(userPassword = userPw, ownerPassword = ownerPw, random = Random(seed))
        .page { text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "Classified payload") }
        .build()

    @Test
    fun encrypted_build_opens_with_user_password_and_extracts() {
        val doc = PdfDocument.open(buildEncrypted(), password = userPw.encodeToByteArray())
        assertTrue(doc.isEncrypted)
        assertTrue(doc.isAuthenticated)
        assertContains(doc.pages[0].extractText(), "Classified payload")
        assertEquals("Secret Report", doc.metadata.title, "the /Info strings decrypt")
    }

    @Test
    fun encrypted_build_opens_with_owner_password() {
        val doc = PdfDocument.open(buildEncrypted(), password = ownerPw.encodeToByteArray())
        assertTrue(doc.isAuthenticated)
        assertContains(doc.pages[0].extractText(), "Classified payload")
    }

    @Test
    fun wrong_or_missing_password_is_rejected() {
        assertFailsWith<WrongPasswordException> {
            PdfDocument.open(buildEncrypted(), password = "wrong".encodeToByteArray())
        }
        assertFailsWith<WrongPasswordException> {
            PdfDocument.open(buildEncrypted())
        }
    }

    @Test
    fun seeded_random_makes_the_build_reproducible() {
        assertContentEquals(buildEncrypted(seed = 7), buildEncrypted(seed = 7))
    }

    @Test
    fun permissions_round_trip_through_the_p_flags() {
        val restricted = PdfPermissions.allowAll.copy(
            canPrint = false,
            canPrintHighResolution = false,
            canModifyContents = false,
        )
        val bytes = PdfBuilder()
            .encrypt(userPassword = userPw, permissions = restricted, random = Random(1))
            .page { text(StandardFont.Helvetica, 12.0, 72.0, 700.0, "restricted") }
            .build()
        val doc = PdfDocument.open(bytes, password = userPw.encodeToByteArray())
        assertFalse(doc.permissions.canPrint)
        assertFalse(doc.permissions.canModifyContents)
        assertTrue(doc.permissions.canCopyContents)
    }

    @Test
    fun plain_builds_stay_unencrypted() {
        val bytes = PdfBuilder()
            .page { text(StandardFont.Helvetica, 12.0, 72.0, 700.0, "plain") }
            .build()
        val doc = PdfDocument.open(bytes)
        assertFalse(doc.isEncrypted)
        // No /Encrypt token anywhere in the file.
        assertFalse(bytes.decodeToString().contains("/Encrypt"))
    }

    @Test
    fun incremental_edit_of_an_encrypted_document() {
        val doc = PdfDocument.open(buildEncrypted(), password = userPw.encodeToByteArray())
        val editor = doc.edit(random = Random(9))
        editor.setInfo(title = "Stamped Report")
        editor.stampPage(doc.pages[0]) {
            setFillRgb(0.8, 0.1, 0.1)
            text(StandardFont.HelveticaBold, 36.0, 100.0, 400.0, "STAMPED")
        }
        val edited = editor.saveIncremental()

        assertFailsWith<WrongPasswordException>("the edited file must still require the password") {
            PdfDocument.open(edited)
        }
        val re = PdfDocument.open(edited, password = userPw.encodeToByteArray())
        assertTrue(re.isEncrypted)
        val text = re.pages[0].extractText()
        assertContains(text, "Classified payload", false, "original content survives the edit")
        assertContains(text, "STAMPED", false, "the stamp decrypts and extracts")
        assertEquals("Stamped Report", re.metadata.title, "the re-encrypted /Info strings decrypt")
    }

    @Test
    fun editing_without_authentication_is_refused() {
        val doc = PdfDocument.open(
            buildEncrypted(),
            password = "wrong".encodeToByteArray(),
            allowInvalidPassword = true,
        )
        assertFailsWith<IllegalArgumentException> { doc.edit() }
    }

    @Test
    fun rc4_documents_stay_read_only() {
        val doc = KitePDF.open(buildRc4Pdf())
        assertTrue(doc.isAuthenticated)
        val e = assertFailsWith<IllegalArgumentException> { doc.edit() }
        assertContains(e.message ?: "", "RC4")
    }

    /* ─── Minimal V1/R2 RC4 fixture (empty password) ─────────────────────── */

    private fun buildRc4Pdf(): ByteArray {
        val padded = ByteArray(32).also { PAD.copyInto(it, 0) }
        val oEntry = Rc4.process(Md5.hash(padded).copyOf(5), padded)
        val fileId = ByteArray(16) { it.toByte() }
        val pBytes = byteArrayOf(-1, -1, -1, -1)
        val fileKey = Md5.hash(padded + oEntry + pBytes + fileId).copyOf(5)
        val uEntry = Rc4.process(fileKey, PAD)

        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun write(s: String) = buf.append(s.encodeToByteArray())
        fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

        write("%PDF-1.4\n")
        offsets.add(buf.size()); write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets.add(buf.size()); write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        offsets.add(buf.size())
        write("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>\nendobj\n")
        offsets.add(buf.size())
        val key = Md5.hash(fileKey + byteArrayOf(4, 0, 0, 0, 0)).copyOf(10)
        val ciphertext = Rc4.process(key, "q Q".encodeToByteArray())
        write("4 0 obj\n<< /Length ${ciphertext.size} >>\nstream\n")
        buf.append(ciphertext)
        write("\nendstream\nendobj\n")
        offsets.add(buf.size())
        write("5 0 obj\n<< /Filter /Standard /V 1 /R 2 /Length 40 /O <${hex(oEntry)}> /U <${hex(uEntry)}> /P -1 >>\nendobj\n")
        val xref = buf.size()
        write("xref\n0 6\n0000000000 65535 f \n")
        for (off in offsets) write("${off.toString().padStart(10, '0')} 00000 n \n")
        write("trailer\n<< /Size 6 /Root 1 0 R /Encrypt 5 0 R /ID [<${hex(fileId)}> <${hex(fileId)}>] >>\n")
        write("startxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val out = ByteArray(size + other.size)
        copyInto(out, 0); other.copyInto(out, size); return out
    }

    private val PAD = byteArrayOf(
        0x28, 0xBF.toByte(), 0x4E, 0x5E, 0x4E, 0x75, 0x8A.toByte(), 0x41,
        0x64, 0x00, 0x4E, 0x56, 0xFF.toByte(), 0xFA.toByte(), 0x01, 0x08,
        0x2E, 0x2E, 0x00, 0xB6.toByte(), 0xD0.toByte(), 0x68, 0x3E, 0x80.toByte(),
        0x2F, 0x0C, 0xA9.toByte(), 0xFE.toByte(), 0x64, 0x53, 0x69, 0x7A,
    )
}
