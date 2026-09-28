package io.github.yuroyami.kitepdf.crypto.sig

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfRevisionChange.Kind
import io.github.yuroyami.kitepdf.PdfSignatureValidation
import io.github.yuroyami.kitepdf.PdfSignatureValidation.Status
import io.github.yuroyami.kitepdf.crypto.sig.SigFixtures.PdfOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What later revisions changed after a signature, and whether the certification level and the locks allow it (#448). */
class RevisionChangesTest {

    private companion object {
        val root = SigFixtures.certificate("KitePDF Test Root", SigFixtures.rsaKeys(), issuer = null, ca = true, serial = 1)
        val signer = SigFixtures.certificate("KitePDF Test Signer", SigFixtures.rsaKeys(), issuer = root, ca = false, serial = 2)
    }

    private fun signed(o: PdfOptions): ByteArray = SigFixtures.signedPdf(o) { SigFixtures.cms(signer, listOf(signer, root), it) }

    private fun check(pdf: ByteArray): PdfSignatureValidation {
        val result = PdfDocument.open(pdf).signatures.first().validate(listOf(root.cert))
        assertEquals(Status.Valid, result.status, result.detail)
        return result
    }

    private fun catalog(o: PdfOptions, fields: String, extra: String = ""): String =
        "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [$fields] /SigFlags 3 >>" +
            (if (o.certification != null) " /Perms << /DocMDP 5 0 R >>" else "") + " $extra >>"

    private fun page(o: PdfOptions, annots: String): String =
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Annots [$annots] /Contents ${SigFixtures.numbers(o).content} 0 R >>"

    private fun filled(o: PdfOptions, value: String = "after"): ByteArray = SigFixtures.update(
        signed(o),
        mapOf(SigFixtures.numbers(o).textField to "<< /Type /Annot /Subtype /Widget /FT /Tx /T (Name) /Rect [20 150 180 170] /F 4 /P 3 0 R /V ($value) >>"),
    )

    private fun annotated(o: PdfOptions): ByteArray {
        val n = SigFixtures.numbers(o)
        return SigFixtures.update(
            signed(o),
            mapOf(
                n.next to "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /Contents (A note) /P 3 0 R >>",
                n.page to page(o, "4 0 R ${n.next} 0 R"),
            ),
        )
    }

    @Test
    fun anUnchangedDocumentHasNoChanges() {
        val o = PdfOptions(certification = 2)
        val pdf = signed(o)
        assertEquals(2, PdfDocument.open(pdf).signatures.single().certificationLevel)
        val result = check(pdf)
        assertEquals(emptyList(), result.changes)
        assertTrue(result.areChangesPermitted)
    }

    @Test
    fun aDocMdpReferenceWithoutALevelMeansLevel2() {
        assertEquals(2, PdfDocument.open(signed(PdfOptions(certification = 0))).signatures.single().certificationLevel)
    }

    @Test
    fun anObjectWrittenAgainUnchangedIsNoChange() {
        val result = check(filled(PdfOptions(certification = 1, textField = "before"), value = "before"))
        assertTrue(result.isModifiedAfterSigning)
        assertEquals(emptyList(), result.changes)
    }

    @Test
    fun anUpdateWithACrossReferenceStreamReportsOnlyItsChanges() {
        val o = PdfOptions(certification = 2, textField = "before")
        val update = SigFixtures.updateWithXrefStream(
            signed(o),
            mapOf(SigFixtures.numbers(o).textField to "<< /Type /Annot /Subtype /Widget /FT /Tx /T (Name) /Rect [20 150 180 170] /F 4 /P 3 0 R /V (after) >>"),
        )
        val document = PdfDocument.open(update)
        assertEquals("after", document.formField("Name")?.value, "the update is read")
        val result = document.signatures.first().validate(listOf(root.cert))
        assertEquals(listOf(Kind.FieldValue), result.changes!!.map { it.kind })
        assertTrue(result.areChangesPermitted)
    }

    @Test
    fun anApprovalSignatureHasNoCertificationLevel() {
        assertNull(PdfDocument.open(signed(PdfOptions())).signatures.single().certificationLevel)
    }

    @Test
    fun fillingAFieldIsPermittedFromLevel2() {
        for ((level, permitted) in listOf(1 to false, 2 to true, 3 to true, null to true)) {
            val result = check(filled(PdfOptions(certification = level, textField = "before")))
            assertTrue(result.isModifiedAfterSigning)
            val change = result.changes!!.single()
            assertEquals(Kind.FieldValue, change.kind, "level $level")
            assertEquals("the value of field Name", change.subject)
            assertEquals(permitted, change.isPermitted, "level $level")
            assertEquals(permitted, result.areChangesPermitted, "level $level")
        }
    }

    @Test
    fun anAnnotationIsPermittedAtLevel3() {
        for ((level, permitted) in listOf(1 to false, 2 to false, 3 to true, null to true)) {
            val change = check(annotated(PdfOptions(certification = level))).changes!!.single()
            assertEquals(Kind.Annotation, change.kind)
            assertEquals("a Text annotation on page 1", change.subject)
            assertEquals(permitted, change.isPermitted, "level $level")
        }
    }

    @Test
    fun changedPageContentIsNeverPermitted() {
        for (level in listOf(null, 3)) {
            val o = PdfOptions(certification = level)
            val n = SigFixtures.numbers(o)
            val result = check(SigFixtures.update(signed(o), mapOf(n.content to SigFixtures.stream("1 0 0 rg 20 20 50 50 re f"))))
            val change = result.changes!!.single()
            assertEquals(Kind.Other, change.kind)
            assertEquals(n.content.toLong(), change.objectNumber)
            assertFalse(change.isPermitted)
            assertFalse(result.areChangesPermitted)
        }
    }

    @Test
    fun theSecurityStoreMayAlwaysGrow() {
        val o = PdfOptions(certification = 1)
        val n = SigFixtures.numbers(o)
        val crl = String(SigFixtures.crl(root, emptyList()), Charsets.ISO_8859_1)
        val ltv = SigFixtures.update(
            signed(o),
            mapOf(
                n.next to "<< /CRLs [${n.next + 1} 0 R] >>",
                n.next + 1 to "<< /Length ${crl.length} >>\nstream\n$crl\nendstream",
                n.catalog to catalog(o, "4 0 R", "/DSS ${n.next} 0 R"),
            ),
        )
        val result = check(ltv)
        assertEquals(listOf(Kind.SecurityStore, Kind.SecurityStore), result.changes!!.map { it.kind })
        assertTrue(result.areChangesPermitted)
    }

    @Test
    fun aSecondSignatureIsPermittedFromLevel2() {
        for ((level, permitted) in listOf(1 to false, 2 to true)) {
            val o = PdfOptions(certification = level)
            val n = SigFixtures.numbers(o)
            val second = SigFixtures.update(
                signed(o),
                mapOf(
                    n.next to "<< /Type /Annot /Subtype /Widget /FT /Sig /T (Signature2) /Rect [0 0 0 0] /F 132 /P 3 0 R /V ${n.next + 1} 0 R >>",
                    n.next + 1 to "<< /Type /Sig /Filter /Adobe.PPKLite /SubFilter /adbe.pkcs7.detached /ByteRange [0 1 2 1] /Contents <00> >>",
                    n.page to page(o, "4 0 R ${n.next} 0 R"),
                    n.catalog to catalog(o, "4 0 R ${n.next} 0 R"),
                ),
            )
            val result = check(second)
            assertEquals(listOf(Kind.Signature, Kind.Signature), result.changes!!.map { it.kind }, "level $level")
            assertEquals(permitted, result.areChangesPermitted, "level $level")
        }
    }

    @Test
    fun aNewFormFieldIsNeverPermitted() {
        val o = PdfOptions(certification = 3)
        val n = SigFixtures.numbers(o)
        val added = SigFixtures.update(
            signed(o),
            mapOf(
                n.next to "<< /Type /Annot /Subtype /Widget /FT /Tx /T (Added) /Rect [20 100 180 120] /F 4 /P 3 0 R >>",
                n.page to page(o, "4 0 R ${n.next} 0 R"),
                n.catalog to catalog(o, "4 0 R ${n.next} 0 R"),
            ),
        )
        val change = check(added).changes!!.single()
        assertEquals(Kind.Other, change.kind)
        assertEquals("the new form field Added", change.subject)
        assertFalse(change.isPermitted)
    }

    @Test
    fun aLockCoversTheFieldsItNames() {
        for ((lock, permitted) in listOf(
            "<< /Type /SigFieldLock /Action /All >>" to false,
            "<< /Type /SigFieldLock /Action /Include /Fields [(Name)] >>" to false,
            "<< /Type /SigFieldLock /Action /Include /Fields [(Other)] >>" to true,
            "<< /Type /SigFieldLock /Action /Exclude /Fields [(Name)] >>" to true,
        )) {
            val change = check(filled(PdfOptions(lock = lock, textField = "before"))).changes!!.single()
            assertEquals(Kind.FieldValue, change.kind)
            assertEquals(permitted, change.isPermitted, lock)
        }
    }

    @Test
    fun aChangedInfoDictionaryIsReported() {
        val result = check(SigFixtures.appendRevision(signed(PdfOptions())))
        val change = result.changes!!.single()
        assertEquals(Kind.Other, change.kind)
        assertEquals("the document information", change.subject)
        assertFalse(result.areChangesPermitted)
    }

    @Test
    fun anObjectThatNothingReachesIsNoChange() {
        val o = PdfOptions(certification = 1)
        val orphan = SigFixtures.update(signed(o), mapOf(SigFixtures.numbers(o).next to "<< /Unused true >>"))
        val result = check(orphan)
        assertTrue(result.isModifiedAfterSigning)
        assertEquals(emptyList(), result.changes)
        assertTrue(result.areChangesPermitted)
    }
}
