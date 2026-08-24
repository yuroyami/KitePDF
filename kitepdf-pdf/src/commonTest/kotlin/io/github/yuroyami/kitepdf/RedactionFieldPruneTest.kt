package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRawApi
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.filters.FilterChain
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.parser.PdfString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Dropping a widget from a page's `/Annots` is not enough. A widget annotation
 * is usually also the form field (ISO 32000-1, 12.7.3.3), and `/AcroForm
 * /Fields` (12.7.2) still names it, so its value, default value, name and
 * appearance stream stay reachable from the catalog and survive the rewrite
 * (ledger D-2). The field has to go with the widget, and a field that keeps a
 * widget somewhere else has to stay.
 */
class RedactionFieldPruneTest {

    private val secret = "PATIENT-9999"

    /** Page body text, far from every region these tests redact. */
    private val body = "BT /F1 12 Tf 100 400 Td (body) Tj ET".encodeToByteArray()

    private val upperRegion = KiteRectangle(left = 90.0, bottom = 690.0, right = 310.0, top = 730.0)
    private val lowerRegion = KiteRectangle(left = 90.0, bottom = 90.0, right = 310.0, top = 130.0)

    private fun apStream(number: Int, text: String): Pair<Int, ByteArray> = RawPdf.obj(
        number,
        "<< /Type /XObject /Subtype /Form /BBox [0 0 200 20] >>",
        "BT /F1 12 Tf 0 0 Td ($text) Tj ET".encodeToByteArray(),
    )

    /** One merged widget-and-field at (100,700)-(300,720), value in /V, form in its own object. */
    private fun mergedFieldPdf(): ByteArray = RawPdf.page(
        content = body,
        annots = "/Annots [6 0 R]",
        catalogExtra = "/AcroForm 8 0 R",
        extra = listOf(
            RawPdf.obj(
                6,
                "<< /Type /Annot /Subtype /Widget /FT /Tx /T (patient) /V ($secret) /DV ($secret) " +
                    "/Rect [100 700 300 720] /P 3 0 R /AP << /N 7 0 R >> >>",
            ),
            apStream(7, secret),
            RawPdf.obj(8, "<< /Fields [6 0 R] /DA (/Helv 0 Tf 0 g) >>"),
        ),
    )

    /** One field with two widget kids: upper at y 700, lower at y 100. */
    private fun twoWidgetFieldPdf(): ByteArray = RawPdf.page(
        content = body,
        annots = "/Annots [7 0 R 8 0 R]",
        catalogExtra = "/AcroForm 11 0 R",
        extra = listOf(
            RawPdf.obj(6, "<< /FT /Tx /T (patient) /V ($secret) /Kids [7 0 R 8 0 R] >>"),
            RawPdf.obj(
                7,
                "<< /Type /Annot /Subtype /Widget /Rect [100 700 300 720] /Parent 6 0 R /P 3 0 R /AP << /N 9 0 R >> >>",
            ),
            RawPdf.obj(
                8,
                "<< /Type /Annot /Subtype /Widget /Rect [100 100 300 120] /Parent 6 0 R /P 3 0 R /AP << /N 10 0 R >> >>",
            ),
            apStream(9, "UPPER-COPY"),
            apStream(10, "LOWER-COPY"),
            RawPdf.obj(11, "<< /Fields [6 0 R] >>"),
        ),
    )

    /**
     * True when any object in [pdf] still carries [needle], in a dictionary value
     * or inside a stream.
     *
     * A scan of the raw file bytes would pass for the wrong reason: streams are
     * written compressed, and a rewrite may pack dictionaries into an object
     * stream, so neither is visible as plain text in the file.
     */
    @OptIn(KiteRawApi::class)
    private fun holds(pdf: ByteArray, needle: String): Boolean {
        val doc = KitePDF.open(pdf)
        val bytes = needle.encodeToByteArray()
        for (num in doc.xref.keys) {
            when (val obj = doc.resolve(PdfReference(num, 0))) {
                null -> {}
                is PdfStream -> {
                    if (obj.dict.toString().contains(needle)) return true
                    val decoded = runCatching { FilterChain.decode(obj) }.getOrNull() ?: continue
                    if (RawPdf.containsBytes(decoded, bytes)) return true
                }
                else -> if (obj.toString().contains(needle)) return true
            }
        }
        return false
    }

    private fun kidCount(pdf: ByteArray, fieldName: String): Int {
        val doc = KitePDF.open(pdf)
        val field = doc.formField(fieldName) ?: return -1
        return field.fieldDict.getArray("Kids", doc)?.size ?: -1
    }

    /* ─── A merged widget-and-field ──────────────────────────────────────── */

    @Test fun the_fixture_really_carries_the_value() {
        val pdf = mergedFieldPdf()
        assertTrue(holds(pdf, secret), "fixture is wrong, the scan proves nothing")
        val doc = KitePDF.open(pdf)
        assertEquals(secret, doc.formField("patient")?.value)
        assertEquals(1, doc.pages[0].annotations.size, "fixture is wrong, the widget is not on the page")
    }

    @Test fun redacting_a_merged_widget_removes_its_field_from_the_file() {
        val doc = KitePDF.open(mergedFieldPdf())
        val out = doc.edit().apply { redactRegion(doc.pages[0], upperRegion) }.saveRewritten()

        assertFalse(
            holds(out, secret),
            "the field value survived: /AcroForm /Fields still reaches the pruned widget",
        )
        assertEquals(0, KitePDF.open(out).formFields.size, "the form still lists the redacted field")
    }

    @Test fun a_widget_outside_every_region_keeps_its_field() {
        val doc = KitePDF.open(mergedFieldPdf())
        val elsewhere = KiteRectangle(left = 0.0, bottom = 0.0, right = 50.0, top = 50.0)
        val out = doc.edit().apply { redactRegion(doc.pages[0], elsewhere) }.saveRewritten()

        assertTrue(holds(out, secret), "an untouched field was pruned")
        assertEquals(secret, KitePDF.open(out).formField("patient")?.value, "an untouched field left the form")
    }

    @Test fun a_form_written_into_the_catalog_is_detached_too() {
        // /AcroForm is a dictionary in the catalog rather than its own object, which
        // is legal and which KitePDF's own CatalogExtras fixture writes. The catalog
        // is then what has to be restaged.
        val pdf = RawPdf.page(
            content = body,
            annots = "/Annots [6 0 R]",
            catalogExtra = "/AcroForm << /Fields [6 0 R] >>",
            extra = listOf(
                RawPdf.obj(
                    6,
                    "<< /Type /Annot /Subtype /Widget /FT /Tx /T (patient) /V ($secret) " +
                        "/Rect [100 700 300 720] /P 3 0 R /AP << /N 7 0 R >> >>",
                ),
                apStream(7, secret),
            ),
        )
        assertEquals(secret, KitePDF.open(pdf).formField("patient")?.value, "fixture is wrong, the form is not read")

        val doc = KitePDF.open(pdf)
        val out = doc.edit().apply { redactRegion(doc.pages[0], upperRegion) }.saveRewritten()

        assertFalse(holds(out, secret), "a /AcroForm written into the catalog was left pointing at the widget")
    }

    /* ─── A field with several widgets ───────────────────────────────────── */

    @Test fun the_two_widget_fixture_really_draws_both() {
        val pdf = twoWidgetFieldPdf()
        assertTrue(holds(pdf, "UPPER-COPY"), "fixture is wrong, the scan proves nothing")
        assertTrue(holds(pdf, "LOWER-COPY"), "fixture is wrong, the scan proves nothing")
        assertEquals(2, KitePDF.open(pdf).pages[0].annotations.size)
        assertEquals(2, kidCount(pdf, "patient"))
    }

    @Test fun a_field_that_keeps_a_widget_keeps_its_place_in_the_form() {
        val doc = KitePDF.open(twoWidgetFieldPdf())
        val out = doc.edit().apply { redactRegion(doc.pages[0], upperRegion) }.saveRewritten()

        val reopened = KitePDF.open(out)
        assertEquals(secret, reopened.formField("patient")?.value, "a field with a surviving widget left the form")
        assertEquals(1, reopened.pages[0].annotations.size, "the widget outside the region was pruned too")
        assertEquals(1, kidCount(out, "patient"), "the redacted widget is still one of the field's /Kids")
        assertFalse(holds(out, "UPPER-COPY"), "the redacted widget's appearance stream is still in the file")
        assertTrue(holds(out, "LOWER-COPY"), "the surviving widget lost its appearance stream")
    }

    @Test fun a_field_whose_every_widget_goes_leaves_the_form() {
        val doc = KitePDF.open(twoWidgetFieldPdf())
        val out = doc.edit().apply { redactRegions(doc.pages[0], listOf(upperRegion, lowerRegion)) }.saveRewritten()

        assertEquals(0, KitePDF.open(out).formFields.size, "the emptied field is still in /Fields")
        assertFalse(holds(out, secret), "the emptied field's value survived")
        assertFalse(holds(out, "UPPER-COPY"), "a redacted widget's appearance stream survived")
        assertFalse(holds(out, "LOWER-COPY"), "a redacted widget's appearance stream survived")
    }

    @Test fun two_calls_can_empty_one_field_between_them() {
        // The second call has to read the /Kids the FIRST call staged. Reading the
        // original would show the widget the first call already took out as a
        // survivor, and the field would stay in /Fields with its value.
        val doc = KitePDF.open(twoWidgetFieldPdf())
        val out = doc.edit().apply {
            redactRegion(doc.pages[0], upperRegion)
            redactRegion(doc.pages[0], lowerRegion)
        }.saveRewritten()

        assertEquals(0, KitePDF.open(out).formFields.size, "the emptied field is still in /Fields")
        assertFalse(holds(out, secret), "the emptied field's value survived the second call")
    }

    @Test fun redacting_one_branch_leaves_the_other_branch_in_the_form() {
        // address (root) owns line1 and line2, each with one widget. Only line1's
        // widget is in a region, so line1 goes and line2 stays: /Fields lists roots
        // (12.7.2), and dropping the root would take an untouched field with it.
        val pdf = RawPdf.page(
            content = body,
            annots = "/Annots [9 0 R 10 0 R]",
            catalogExtra = "/AcroForm 11 0 R",
            extra = listOf(
                RawPdf.obj(6, "<< /T (address) /Kids [7 0 R 8 0 R] >>"),
                RawPdf.obj(7, "<< /FT /Tx /T (line1) /V (SECRET-LINE-ONE) /Parent 6 0 R /Kids [9 0 R] >>"),
                RawPdf.obj(8, "<< /FT /Tx /T (line2) /V (KEEP-LINE-TWO) /Parent 6 0 R /Kids [10 0 R] >>"),
                RawPdf.obj(9, "<< /Type /Annot /Subtype /Widget /Rect [100 700 300 720] /Parent 7 0 R /P 3 0 R >>"),
                RawPdf.obj(10, "<< /Type /Annot /Subtype /Widget /Rect [100 100 300 120] /Parent 8 0 R /P 3 0 R >>"),
                RawPdf.obj(11, "<< /Fields [6 0 R] >>"),
            ),
        )
        assertEquals(2, KitePDF.open(pdf).formFields.size, "fixture is wrong, it must hold two fields")

        val doc = KitePDF.open(pdf)
        val out = doc.edit().apply { redactRegion(doc.pages[0], upperRegion) }.saveRewritten()

        val reopened = KitePDF.open(out)
        assertFalse(holds(out, "SECRET-LINE-ONE"), "the redacted branch's value survived")
        assertTrue(holds(out, "KEEP-LINE-TWO"), "the untouched branch went with the redacted one")
        assertNotNull(reopened.formField("address.line2"), "the untouched branch left the form")
        assertEquals(1, reopened.formFields.size, "the redacted branch is still a field")
    }

    /* ─── Malformed input ────────────────────────────────────────────────── */

    @Test fun a_looping_parent_chain_is_refused_rather_than_half_redacted() {
        // The widget's parent names ITSELF as its parent and claims no kids, so a
        // climb to the root field never ends on its own. /Parent chains are a tree
        // (12.7.3.3), so this file is malformed and the root field cannot be found:
        // the value would stay in /Fields, which is the very thing this redaction
        // removes, so the call says so instead of handing back a file the caller
        // would believe is clean.
        val pdf = RawPdf.page(
            content = body,
            annots = "/Annots [7 0 R]",
            catalogExtra = "/AcroForm 8 0 R",
            extra = listOf(
                RawPdf.obj(6, "<< /FT /Tx /T (loop) /V (LOOP-SECRET) /Parent 6 0 R >>"),
                RawPdf.obj(7, "<< /Type /Annot /Subtype /Widget /Rect [100 700 300 720] /Parent 6 0 R /P 3 0 R >>"),
                RawPdf.obj(8, "<< /Fields [6 0 R] >>"),
            ),
        )
        val doc = KitePDF.open(pdf)
        val editor = doc.edit()
        val failure = assertFailsWith<IllegalStateException> { editor.redactRegion(doc.pages[0], upperRegion) }
        assertTrue(
            failure.message.orEmpty().contains("Parent"),
            "the failure does not say what is wrong: ${failure.message}",
        )
    }

    /* ─── Objects staged in this session ─────────────────────────────────── */

    @OptIn(KiteRawApi::class)
    @Test fun a_widget_staged_in_this_session_is_pruned_like_one_from_the_file() {
        // The /Annots array, the widget, its /Rect and one of that rect's numbers
        // live in the staging map and nowhere else. Every lookup the prune makes has
        // to see staged objects: one that went through the base document alone hits
        // a dangling reference and the whole redaction throws.
        val staged = "STAGED-9999"
        val pdf = RawPdf.page(
            content = body,
            catalogExtra = "/AcroForm 6 0 R",
            extra = listOf(RawPdf.obj(6, "<< /Fields [] >>")),
        )
        val doc = KitePDF.open(pdf)
        val editor = doc.edit()
        val ap = editor.addFlateStream(
            "BT /F1 12 Tf 0 0 Td ($staged) Tj ET".encodeToByteArray(),
            mapOf(
                "Type" to PdfName("XObject"),
                "Subtype" to PdfName("Form"),
                "BBox" to PdfArray(listOf(PdfInt(0), PdfInt(0), PdfInt(200), PdfInt(20))),
            ),
        )
        val top = editor.addObject(PdfInt(720))
        val rect = editor.addObject(PdfArray(listOf(PdfInt(100), PdfInt(700), PdfInt(300), top)))
        val widget = editor.addObject(
            PdfDictionary(
                linkedMapOf<String, PdfObject>(
                    "Type" to PdfName("Annot"),
                    "Subtype" to PdfName("Widget"),
                    "FT" to PdfName("Tx"),
                    "T" to PdfString("staged".encodeToByteArray()),
                    "V" to PdfString(staged.encodeToByteArray()),
                    "Rect" to rect,
                    "AP" to PdfDictionary(linkedMapOf<String, PdfObject>("N" to ap)),
                ),
            ),
        )
        val annotsRef = editor.addObject(PdfArray(listOf(widget)))
        val pageRef = doc.pages[0].reference!!
        editor.updateObject(
            pageRef,
            PdfDictionary(LinkedHashMap(doc.pages[0].dictionary.map).apply { put("Annots", annotsRef) }),
        )
        editor.updateObject(
            PdfReference(6, 0),
            PdfDictionary(linkedMapOf<String, PdfObject>("Fields" to PdfArray(listOf(widget)))),
        )

        editor.redactRegion(doc.pages[0], upperRegion)
        val out = editor.saveRewritten()

        assertFalse(holds(out, staged), "a widget staged in this session kept its field")
        assertEquals(0, KitePDF.open(out).formFields.size, "the form still lists the staged widget")
    }
}
