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
    fun choosingAnOptionOrFillingRichTextIsFilling() {
        // ISO 32000-1, Tables 231 and 222: /I, /TI and /RV are part of a field's value (#997).
        for (entries in listOf("/V (b) /I [1] /TI 1", "/V (after) /RV (<body><p>after</p></body>)")) {
            val o = PdfOptions(certification = 2, textField = "before")
            val result = check(
                SigFixtures.update(
                    signed(o),
                    mapOf(SigFixtures.numbers(o).textField to "<< /Type /Annot /Subtype /Widget /FT /Tx /T (Name) /Rect [20 150 180 170] /F 4 /P 3 0 R $entries >>"),
                ),
            )
            assertEquals(Kind.FieldValue, result.changes!!.single().kind, entries)
            assertTrue(result.areChangesPermitted, entries)
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

    private fun signatureField(lock: String? = null, value: Int = 5): String =
        "<< /Type /Annot /Subtype /Widget /FT /Sig /T (Signature1) /Rect [0 0 0 0] /F 132 /P 3 0 R /V $value 0 R" + (lock?.let { " /Lock $it" } ?: "") + " >>"

    @Test
    fun aSignatureThatALaterRevisionAddsDoesNotRaiseTheLevel() {
        // ISO 32000-1, 12.8.2.2: the level is that of the signature /Perms /DocMDP names, as it was signed (#1002).
        val o = PdfOptions(certification = 1)
        val n = SigFixtures.numbers(o)
        val raised = SigFixtures.update(
            signed(o),
            mapOf(
                n.next to "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /Contents (A note) /P 3 0 R >>",
                n.next + 1 to "<< /Type /Annot /Subtype /Widget /FT /Sig /T (Fake) /Rect [0 0 0 0] /F 132 /P 3 0 R /V ${n.next + 2} 0 R >>",
                n.next + 2 to "<< /Type /Sig /Filter /Adobe.PPKLite /SubFilter /adbe.pkcs7.detached " +
                    "/Reference [<< /Type /SigRef /TransformMethod /DocMDP /TransformParams << /Type /TransformParams /P 3 /V /1.2 >> >>] " +
                    "/ByteRange [0 1 2 1] /Contents <00> >>",
                n.page to page(o, "4 0 R ${n.next} 0 R ${n.next + 1} 0 R"),
                n.catalog to catalog(o, "${n.next + 1} 0 R 4 0 R"),
            ),
        )
        val document = PdfDocument.open(raised)
        val result = document.signatures.single { it.field.fullyQualifiedName == "Signature1" }.validate(listOf(root.cert))
        assertEquals(Status.Valid, result.status, result.detail)
        assertTrue(result.changes!!.none { it.isPermitted }, result.changes.toString())
        assertFalse(result.areChangesPermitted)
    }

    @Test
    fun aLooserLockInALaterRevisionDoesNotFreeTheField() {
        // ISO 32000-1, 12.8.2.4: the lock is the field's lock as it was signed (#1002).
        val o = PdfOptions(lock = "<< /Type /SigFieldLock /Action /All >>", textField = "before")
        val n = SigFixtures.numbers(o)
        val loosened = SigFixtures.update(
            signed(o),
            mapOf(
                n.textField to "<< /Type /Annot /Subtype /Widget /FT /Tx /T (Name) /Rect [20 150 180 170] /F 4 /P 3 0 R /V (after) >>",
                n.signatureField to signatureField(lock = "<< /Type /SigFieldLock /Action /Include /Fields [(Nothing)] >>"),
            ),
        )
        val result = check(loosened)
        assertEquals(listOf("a change to signed field Signature1", "the value of field Name"), result.changes!!.map { it.subject })
        assertTrue(result.changes.none { it.isPermitted })
        assertFalse(result.areChangesPermitted)
    }

    @Test
    fun aSignedFieldPointedAtAnotherSignatureIsNotSigning() {
        val o = PdfOptions(certification = 1)
        val n = SigFixtures.numbers(o)
        val original = signed(o)
        val text = String(original, Charsets.ISO_8859_1)
        val body = text.substring(text.indexOf("5 0 obj\n") + "5 0 obj\n".length, text.indexOf("\nendobj", text.indexOf("5 0 obj\n")))
        val copy = body.replace(Regex("""/Reference \[.*?\] """), "")
        assertTrue(copy != body && "/ByteRange [0 " in copy)
        val repointed = SigFixtures.update(
            original,
            mapOf(
                n.next to "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /Contents (A note) /P 3 0 R >>",
                n.next + 1 to copy,
                n.signatureField to signatureField(value = n.next + 1),
                n.page to page(o, "4 0 R ${n.next} 0 R"),
            ),
        )
        val result = check(repointed)
        assertTrue("a change to signed field Signature1" in result.changes!!.map { it.subject }, result.changes.toString())
        assertTrue(result.changes.none { it.isPermitted }, result.changes.toString())
        assertFalse(result.areChangesPermitted)
    }

    /**
     * [prefix] with a revision appended: [plain] objects, an object stream [stream] holding
     * [members], and a cross-reference stream, numbered [xref], that lists them all.
     */
    private fun withObjectStream(prefix: ByteArray, plain: Map<Int, String>, stream: Int, members: Map<Int, String>, xref: Int, prev: Int?): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(prefix)
        if (prefix.isEmpty()) out.write("%PDF-1.7\n".toByteArray(Charsets.ISO_8859_1))
        fun write(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))
        val rows = sortedMapOf<Int, Triple<Int, Int, Int>>()
        for ((number, body) in plain) {
            rows[number] = Triple(1, out.size(), 0)
            write("$number 0 obj\n$body\nendobj\n")
        }
        val bodies = StringBuilder()
        val header = StringBuilder()
        for ((index, entry) in members.entries.withIndex()) {
            header.append("${entry.key} ${bodies.length} ")
            bodies.append(entry.value).append('\n')
            rows[entry.key] = Triple(2, stream, index)
        }
        val data = header.toString() + bodies
        rows[stream] = Triple(1, out.size(), 0)
        write("$stream 0 obj\n<< /Type /ObjStm /N ${members.size} /First ${header.length} /Length ${data.length} >>\nstream\n$data\nendstream\nendobj\n")
        val xrefAt = out.size()
        rows[xref] = Triple(1, xrefAt, 0)
        val table = java.io.ByteArrayOutputStream()
        for ((type, a, b) in rows.values) table.write(byteArrayOf(type.toByte(), (a ushr 24).toByte(), (a ushr 16).toByte(), (a ushr 8).toByte(), a.toByte(), 0, b.toByte()))
        val index = rows.keys.joinToString(" ") { "$it 1" }
        write("$xref 0 obj\n<< /Type /XRef /Size ${xref + 1} /Root 1 0 R ${prev?.let { "/Prev $it " } ?: ""}/W [1 4 2] /Index [$index] /Length ${table.size()} >>\nstream\n")
        out.write(table.toByteArray())
        write("\nendstream\nendobj\nstartxref\n$xrefAt\n%%EOF\n")
        return out.toByteArray()
    }

    @Test
    fun aMemberOfAnObjectStreamWrittenAgainIsAChange() {
        // ISO 32000-1, 7.5.7: a new object stream under the same number changes its members, whose entries stay the same (#1003).
        fun members(font: String) = mapOf(
            1 to "<< /Type /Catalog /Pages 2 0 R >>",
            2 to "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            3 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << /Font << /F1 7 0 R >> >> /Contents 6 0 R >>",
            7 to font,
        )
        val helvetica = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        val signed = withObjectStream(ByteArray(0), mapOf(6 to SigFixtures.stream("BT /F1 24 Tf 20 100 Td (1000) Tj ET")), 8, members(helvetica), 9, null)
        val prev = String(signed, Charsets.ISO_8859_1).let { it.substring(it.lastIndexOf("startxref") + 9).trim().lines().first().trim().toInt() }
        val changed = withObjectStream(signed, emptyMap(), 8, members("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding << /Differences [49 /nine] >> >>"), 10, prev)
        val old = PdfDocument.open(signed)
        val new = PdfDocument.open(changed)
        assertEquals("9000", new.pages[0].extractText().trim(), "the update is read")
        val changes = io.github.yuroyami.kitepdf.RevisionChanges.between(old, new, 1, null)
        assertEquals(listOf(7L), changes.map { it.objectNumber }, changes.toString())
        assertFalse(changes.single().isPermitted)
        assertEquals(emptyList(), io.github.yuroyami.kitepdf.RevisionChanges.between(old, old, 1, null))
    }

    @Test
    fun anAnnotationTakenOffAPageIsAnAnnotationChange() {
        // ISO 32000-1, Table 254: only level 3 allows deleting an annotation (#1004).
        val square = "<< /Type /Annot /Subtype /Square /Rect [10 10 80 80] /IC [1 1 1] /P 3 0 R >>"
        for ((level, permitted) in listOf(1 to false, 3 to true)) {
            val o = PdfOptions(certification = level, annotation = square)
            val result = check(SigFixtures.update(signed(o), mapOf(SigFixtures.numbers(o).page to page(o, "4 0 R"))))
            val change = result.changes!!.single()
            assertEquals(Kind.Annotation, change.kind)
            assertEquals("a Square annotation taken off page 1", change.subject)
            assertEquals(permitted, result.areChangesPermitted, "level $level")
        }
    }

    @Test
    fun anUnlistedAnnotationPutOnAPageIsAnAnnotationChange() {
        val freeText = "<< /Type /Annot /Subtype /FreeText /Rect [10 10 180 40] /Contents (PAID IN FULL) /DA (/Helv 12 Tf 0 g) /P 3 0 R >>"
        val o = PdfOptions(certification = 1, annotation = freeText, listAnnotation = false)
        val n = SigFixtures.numbers(o)
        val result = check(SigFixtures.update(signed(o), mapOf(n.page to page(o, "4 0 R ${n.annotation} 0 R"))))
        val change = result.changes!!.single()
        assertEquals(Kind.Annotation, change.kind)
        assertEquals("a FreeText annotation listed on page 1", change.subject)
        assertFalse(result.areChangesPermitted)
    }

    @Test
    fun aFormFieldRemovedFromTheFormIsNeverPermitted() {
        val o = PdfOptions(certification = 3, textField = "before")
        val n = SigFixtures.numbers(o)
        val result = check(SigFixtures.update(signed(o), mapOf(n.catalog to catalog(o, "4 0 R"), n.page to page(o, "4 0 R"))))
        val change = result.changes!!.single()
        assertEquals(Kind.Other, change.kind)
        assertEquals("the removal of form field Name", change.subject)
        assertFalse(result.areChangesPermitted)
    }

    @Test
    fun aDirectAnnotationAddedToAPageIsAnAnnotationChange() {
        val o = PdfOptions(certification = 1)
        val n = SigFixtures.numbers(o)
        val result = check(SigFixtures.update(signed(o), mapOf(n.page to page(o, "4 0 R << /Type /Annot /Subtype /Text /Rect [10 10 30 30] /Contents (A note) >>"))))
        val change = result.changes!!.single()
        assertEquals(Kind.Annotation, change.kind)
        assertEquals("the direct annotations of page 1", change.subject)
        assertFalse(result.areChangesPermitted)
    }
}
