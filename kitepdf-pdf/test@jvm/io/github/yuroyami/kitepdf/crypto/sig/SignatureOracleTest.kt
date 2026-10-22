package io.github.yuroyami.kitepdf.crypto.sig

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfSignatureValidation.Status
import io.github.yuroyami.kitepdf.crypto.sig.SigFixtures.CmsOptions
import io.github.yuroyami.kitepdf.crypto.sig.SigFixtures.PdfOptions
import io.github.yuroyami.kitepdf.font.orSkip
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Signature checks against two outside implementations: OpenSSL signs byte ranges that KitePDF
 * checks, and poppler's `pdfsig` checks the fixtures KitePDF's tests sign. Each part reports as
 * skipped where its tool is missing (#203).
 */
class SignatureOracleTest {

    private companion object {
        val root = SigFixtures.certificate("KitePDF Oracle Root", SigFixtures.rsaKeys(), issuer = null, ca = true, serial = 1)
        val signer = SigFixtures.certificate("KitePDF Oracle Signer", SigFixtures.rsaKeys(), issuer = root, ca = false, serial = 2)
        val ecSigner = SigFixtures.certificate("KitePDF Oracle EC Signer", SigFixtures.ecKeys(), issuer = root, ca = false, serial = 3)
    }

    private fun tool(name: String, vararg probe: String): String? =
        listOf("/opt/homebrew/bin/$name", "/usr/local/bin/$name", "/usr/bin/$name", name).firstOrNull { path ->
            runCatching { ProcessBuilder(listOf(path) + probe).redirectErrorStream(true).start().run { inputStream.readBytes(); waitFor() } }.getOrNull() == 0
        }

    private fun run(dir: File, command: List<String>): String {
        val process = ProcessBuilder(command).directory(dir).redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "${command.first()} failed: $out" }
        return out
    }

    @Test
    fun kitePdfAcceptsWhatOpenSslSigns() {
        val openssl = tool("openssl", "cms", "-help").orSkip("openssl with the cms command")
        val dir = Files.createTempDirectory("kitepdf-sig").toFile()
        try {
            File(dir, "root.pem").writeText(SigFixtures.pem("CERTIFICATE", root.cert))
            for ((identity, flags) in listOf(
                signer to listOf<String>(),
                signer to listOf("-md", "sha384"),
                signer to listOf("-noattr"),
                ecSigner to listOf("-md", "sha512"),
            )) {
                File(dir, "cert.pem").writeText(SigFixtures.pem("CERTIFICATE", identity.cert))
                File(dir, "key.pem").writeText(SigFixtures.pem("PRIVATE KEY", identity.keys.private.encoded))
                val pdf = SigFixtures.signedPdf { signed ->
                    File(dir, "signed.bin").writeBytes(signed)
                    run(dir, listOf(openssl, "cms", "-sign", "-binary", "-in", "signed.bin", "-signer", "cert.pem", "-inkey", "key.pem",
                        "-certfile", "root.pem", "-outform", "DER", "-out", "sig.der") + flags)
                    File(dir, "sig.der").readBytes()
                }
                val result = PdfDocument.open(pdf).signatures.single().validate(listOf(root.cert))
                assertEquals(Status.Valid, result.status, "$flags: ${result.detail}")
                assertEquals(listOf(identity, root).map { it.cert.toList() }, result.chain.map { it.encoded.toList() }, "$flags")
                pdf[String(pdf, Charsets.ISO_8859_1).indexOf("(Here)") + 1] = 'T'.code.toByte()
                val changed = PdfDocument.open(pdf).signatures.single().validate(listOf(root.cert)).status
                assertEquals(if ("-noattr" in flags) Status.Invalid else Status.DigestMismatch, changed, "$flags, changed")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pdfsigAgreesWithKitePdf() {
        val pdfsig = tool("pdfsig", "-v").orSkip("poppler's pdfsig")
        val dir = Files.createTempDirectory("kitepdf-sig").toFile()
        try {
            val changed = SigFixtures.signedPdf { SigFixtures.cms(signer, listOf(signer, root), it) }
            changed[String(changed, Charsets.ISO_8859_1).indexOf("(Testing)") + 1] = 'R'.code.toByte()
            val fixtures = mapOf(
                "rsa" to SigFixtures.signedPdf { SigFixtures.cms(signer, listOf(signer, root), it) },
                "ec" to SigFixtures.signedPdf { SigFixtures.cms(ecSigner, listOf(ecSigner, root), it) },
                "pss" to SigFixtures.signedPdf { SigFixtures.cms(signer, listOf(signer, root), it, o = CmsOptions(pss = SigFixtures.PSS_SHA256)) },
                "cades" to SigFixtures.signedPdf(PdfOptions(subFilter = "ETSI.CAdES.detached")) {
                    SigFixtures.cms(signer, listOf(signer, root), it, o = CmsOptions(extraAttributes = listOf(SigFixtures.essV2(signer.cert))))
                },
                "noattr" to SigFixtures.signedPdf { SigFixtures.cms(signer, listOf(signer, root), it, o = CmsOptions(signedAttributes = false)) },
                "changed" to changed,
                "appended" to SigFixtures.appendRevision(SigFixtures.signedPdf { SigFixtures.cms(signer, listOf(signer, root), it) }),
            )
            for ((name, pdf) in fixtures) {
                val file = File(dir, "$name.pdf").apply { writeBytes(pdf) }
                val process = ProcessBuilder(pdfsig, file.path).redirectErrorStream(true).start()
                val report = process.inputStream.readBytes().decodeToString()
                process.waitFor()
                val theirs = when {
                    "Signature is Valid." in report -> Status.Valid
                    "Digest Mismatch." in report -> Status.DigestMismatch
                    else -> null
                }
                val result = PdfDocument.open(pdf).signatures.single().validate(listOf(root.cert))
                assertEquals(theirs, result.status, "$name: $report")
                assertEquals("Total document signed" in report, !result.isModifiedAfterSigning, "$name: $report")
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
