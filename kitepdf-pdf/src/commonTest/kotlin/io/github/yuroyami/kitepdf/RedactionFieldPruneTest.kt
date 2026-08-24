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
                    // A stream that will not decode still has its bytes in the file, so
                    // scan those: skipping it would read as "the content is gone".
                    val payload = runCatching { FilterChain.decode(obj) }.getOrNull() ?: obj.rawBytes
                    if (RawPdf.containsBytes(payload, bytes)) return true
                }
                else -> if (obj.toString().contains(needle)) return true
            }
        }
        return false
    }

    /** Size of the form's /CO array, or -1 when there is none. */
    private fun calcOrderSize(pdf: ByteArray): Int {
        val doc = KitePDF.open(pdf)
        return doc.acroForm?.raw?.getArray("CO", doc)?.size ?: -1
    }

    /** Every object an /OBJR in the structure tree points at, resolved in the output. */
    private fun structTreeTargets(pdf: ByteArray): List<PdfDictionary> {
        val doc = KitePDF.open(pdf)
        val root = doc.catalog.getDict("StructTreeRoot", doc) ?: return emptyList()
        val elems = when (val k = root["K"]?.resolve(doc)) {
            is PdfArray -> k.items.mapNotNull { it.resolve(doc) as? PdfDictionary }
            is PdfDictionary -> listOf(k)
            else -> emptyList()
        }
        return elems.mapNotNull { elem ->
            val ref = elem.getDict("K", doc)?.getRef("Obj") ?: return@mapNotNull null
            doc.resolve(ref) as? PdfDictionary
        }
    }

    /** The one object the structure tree keeps alive, for the fixtures with a single /OBJR. */
    private fun structTreeTarget(pdf: ByteArray): PdfDictionary? = structTreeTargets(pdf).singleOrNull()

    /** /Kids count of the field owning the first annotation still on the page. */
    private fun survivingWidgetKidCount(pdf: ByteArray): Int {
        val doc = KitePDF.open(pdf)
        val annots = doc.pages[0].dictionary.getArray("Annots", doc) ?: return -1
        val widget = annots.items.firstOrNull()?.resolve(doc) as? PdfDictionary ?: return -1
        val parent = widget.getRef("Parent")?.let { doc.resolve(it) } as? PdfDictionary ?: return -1
        return parent.getArray("Kids", doc)?.size ?: -1
    }

    private fun kidCount(pdf: ByteArray, fieldName: String): Int {
        val doc = KitePDF.open(pdf)
        val field = doc.formField(fieldName) ?: return -1
        return field.fieldDict.getArray("Kids", doc)?.size ?: -1
    }

    @Test fun the_scan_sees_bytes_in_a_stream_that_will_not_decode() {
        // /FlateDecode over bytes that are not Flate. If the scan skipped a stream it
        // cannot decode, every absence assertion in this file could pass for the wrong
        // reason.
        val pdf = RawPdf.page(
            content = body,
            extra = listOf(
                RawPdf.obj(
                    6,
                    "<< /Type /XObject /Subtype /Form /BBox [0 0 200 20] /Filter /FlateDecode >>",
                    "NOT-REALLY-FLATE-$secret".encodeToByteArray(),
                ),
            ),
        )
        assertTrue(holds(pdf, secret), "the scan cannot see into a stream that fails to decode")
    }


    /** A field that names ITSELF as its parent and claims no kids. */
    private fun loopingParentPdf(): ByteArray = RawPdf.page(
        content = body,
        annots = "/Annots [7 0 R]",
        catalogExtra = "/AcroForm 8 0 R",
        extra = listOf(
            RawPdf.obj(6, "<< /FT /Tx /T (loop) /V (LOOP-SECRET) /Parent 6 0 R >>"),
            RawPdf.obj(7, "<< /Type /Annot /Subtype /Widget /Rect [100 700 300 720] /Parent 6 0 R /P 3 0 R >>"),
            RawPdf.obj(8, "<< /Fields [6 0 R] >>"),
        ),
    )

    @Test fun a_looping_parent_chain_is_refused_rather_than_half_redacted() {
        // A climb to the root field never ends on its own here. /Parent chains are a
        // tree (12.7.3.3), so this file is malformed and the root field cannot be
        // found: the value would stay in /Fields, which is the very thing this
        // redaction removes, so the call says so instead of handing back a file the
        // caller would believe is clean.
        val pdf = loopingParentPdf()
        val doc = KitePDF.open(pdf)
        val editor = doc.edit()
        val failure = assertFailsWith<IllegalStateException> { editor.redactRegion(doc.pages[0], upperRegion) }
        assertTrue(
            failure.message.orEmpty().contains("Parent"),
            "the failure does not say what is wrong: ${failure.message}",
        )
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

    @Test fun a_widget_with_an_indirect_subtype_is_still_detached() {
        // /Subtype written as an indirect reference is legal, and the /Rect test
        // resolves it, so the annotation is dropped from the page either way. A gate
        // that reads the name without resolving cannot tell this is a widget, and
        // skipping it would leave the field in /Fields with its value.
        val pdf = RawPdf.page(
            content = body,
            annots = "/Annots [6 0 R]",
            catalogExtra = "/AcroForm 8 0 R",
            extra = listOf(
                RawPdf.obj(
                    6,
                    "<< /Type /Annot /Subtype 9 0 R /FT /Tx /T (patient) /V ($secret) " +
                        "/Rect [100 700 300 720] /P 3 0 R /AP << /N 7 0 R >> >>",
                ),
                apStream(7, secret),
                RawPdf.obj(8, "<< /Fields [6 0 R] >>"),
                RawPdf.obj(9, "/Widget"),
            ),
        )
        assertEquals(secret, KitePDF.open(pdf).formField("patient")?.value, "fixture is wrong, the form is not read")

        val doc = KitePDF.open(pdf)
        val out = doc.edit().apply { redactRegion(doc.pages[0], upperRegion) }.saveRewritten()

        assertEquals(0, KitePDF.open(out).formFields.size, "the form still lists the redacted field")
        assertFalse(holds(out, secret), "a widget whose /Subtype is indirect kept its field")
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

    /**
     * One root field (address) owning two terminal fields (line1 upper, line2
     * lower), each with one widget. [calcOrder] goes into the form dictionary
     * verbatim, for the `/CO` case.
     */
    private fun branchedFieldPdf(calcOrder: String = ""): ByteArray = RawPdf.page(
        content = body,
        annots = "/Annots [9 0 R 10 0 R]",
        catalogExtra = "/AcroForm 11 0 R",
        extra = listOf(
            RawPdf.obj(6, "<< /T (address) /Kids [7 0 R 8 0 R] >>"),
            RawPdf.obj(7, "<< /FT /Tx /T (line1) /V (SECRET-LINE-ONE) /Parent 6 0 R /Kids [9 0 R] >>"),
            RawPdf.obj(8, "<< /FT /Tx /T (line2) /V (KEEP-LINE-TWO) /Parent 6 0 R /Kids [10 0 R] >>"),
            RawPdf.obj(9, "<< /Type /Annot /Subtype /Widget /Rect [100 700 300 720] /Parent 7 0 R /P 3 0 R >>"),
            RawPdf.obj(10, "<< /Type /Annot /Subtype /Widget /Rect [100 100 300 120] /Parent 8 0 R /P 3 0 R >>"),
            RawPdf.obj(11, "<< /Fields [6 0 R] $calcOrder >>"),
        ),
    )

    @Test fun redacting_one_branch_leaves_the_other_branch_in_the_form() {
        // address (root) owns line1 and line2, each with one widget. Only line1's
        // widget is in a region, so line1 goes and line2 stays: /Fields lists roots
        // (12.7.2), and dropping the root would take an untouched field with it.
        val pdf = branchedFieldPdf()
        assertEquals(2, KitePDF.open(pdf).formFields.size, "fixture is wrong, it must hold two fields")

        val doc = KitePDF.open(pdf)
        val out = doc.edit().apply { redactRegion(doc.pages[0], upperRegion) }.saveRewritten()

        val reopened = KitePDF.open(out)
        assertFalse(holds(out, "SECRET-LINE-ONE"), "the redacted branch's value survived")
        assertTrue(holds(out, "KEEP-LINE-TWO"), "the untouched branch went with the redacted one")
        assertNotNull(reopened.formField("address.line2"), "the untouched branch left the form")
        assertEquals(1, reopened.formFields.size, "the redacted branch is still a field")
    }

    @Test fun a_calculation_order_stops_naming_a_detached_field() {
        // /CO is the calculation order (12.7.2, Table 218): it names TERMINAL fields,
        // which are usually not the roots /Fields lists. Here /Fields keeps the
        // untouched root, so the /CO entry is the only thing still naming the redacted
        // field, and it alone keeps that field (and its value) in the file.
        val pdf = branchedFieldPdf(calcOrder = "/CO [7 0 R]")
        assertEquals(1, calcOrderSize(pdf), "fixture is wrong, /CO must name the field")

        val doc = KitePDF.open(pdf)
        val out = doc.edit().apply { redactRegion(doc.pages[0], upperRegion) }.saveRewritten()

        assertEquals(0, calcOrderSize(out), "/CO still names the detached field")
        assertFalse(holds(out, "SECRET-LINE-ONE"), "the redacted branch's value survived")
        assertTrue(holds(out, "KEEP-LINE-TWO"), "the untouched branch went with the redacted one")
    }

    @Test fun a_detached_field_kept_alive_by_something_else_ships_empty() {
        // A tagged document names the annotation from its structure tree through an
        // OBJR (14.7.4.3), which this editor does not rewrite, so the rewrite's GC
        // keeps the object. Detaching alone would ship the value; the scrub is what
        // makes the surviving object an empty field.
        val pdf = RawPdf.page(
            content = body,
            annots = "/Annots [6 0 R]",
            catalogExtra = "/AcroForm 8 0 R /StructTreeRoot 9 0 R",
            extra = listOf(
                RawPdf.obj(
                    6,
                    "<< /Type /Annot /Subtype /Widget /FT /Tx /T (patient) /V ($secret) /DV ($secret) " +
                        "/Rect [100 700 300 720] /P 3 0 R /AP << /N 7 0 R >> >>",
                ),
                apStream(7, secret),
                RawPdf.obj(8, "<< /Fields [6 0 R] >>"),
                RawPdf.obj(9, "<< /Type /StructTreeRoot /K 10 0 R >>"),
                RawPdf.obj(10, "<< /Type /StructElem /S /Form /P 9 0 R /K << /Type /OBJR /Obj 6 0 R >> >>"),
            ),
        )
        val doc = KitePDF.open(pdf)
        val out = doc.edit().apply { redactRegion(doc.pages[0], upperRegion) }.saveRewritten()

        // The object is still in the file: this test says nothing about the GC, it
        // says the survivor carries no value.
        val survivor = structTreeTarget(out)
        assertNotNull(survivor, "fixture is wrong, the structure tree no longer keeps the widget alive")
        assertEquals("Widget", survivor.getName("Subtype"), "the surviving object is not the widget")
        assertEquals(null, survivor["V"], "the detached field kept its value")
        assertEquals(null, survivor["DV"], "the detached field kept its default value")
        assertEquals(null, survivor["T"], "the detached field kept its name")
        assertEquals(null, survivor["AP"], "the detached field kept its appearance stream")
        assertFalse(holds(out, secret), "the value survived somewhere in the file")
    }

    @Test fun a_detached_check_box_kept_alive_by_something_else_forgets_which_box_was_ticked() {
        // For a check box or radio button the appearance state IS the value
        // (12.7.4.2.1), and the state name can be the sensitive part on its own. /TU
        // and /TM identify the field the same way /T does.
        val state = "HIVPositive"
        val pdf = RawPdf.page(
            content = body,
            annots = "/Annots [6 0 R]",
            catalogExtra = "/AcroForm 9 0 R /StructTreeRoot 10 0 R",
            extra = listOf(
                RawPdf.obj(
                    6,
                    "<< /Type /Annot /Subtype /Widget /FT /Btn /T (status) /TU (TOOLTIP-TEXT) " +
                        "/TM (MAPPING-NAME) /V /$state /AS /$state /Rect [100 700 300 720] /P 3 0 R " +
                        "/AP << /N << /$state 7 0 R /Off 8 0 R >> >> >>",
                ),
                apStream(7, "TICKED"),
                apStream(8, "EMPTY"),
                RawPdf.obj(9, "<< /Fields [6 0 R] >>"),
                RawPdf.obj(10, "<< /Type /StructTreeRoot /K 11 0 R >>"),
                RawPdf.obj(11, "<< /Type /StructElem /S /Form /P 10 0 R /K << /Type /OBJR /Obj 6 0 R >> >>"),
            ),
        )
        val doc = KitePDF.open(pdf)
        val out = doc.edit().apply { redactRegion(doc.pages[0], upperRegion) }.saveRewritten()

        val survivor = structTreeTarget(out)
        assertNotNull(survivor, "fixture is wrong, the structure tree no longer keeps the widget alive")
        assertEquals(null, survivor["AS"], "the survivor still says which box was ticked")
        assertEquals(null, survivor["V"], "the detached check box kept its value")
        assertEquals(null, survivor["TU"], "the detached field kept its user-facing name")
        assertEquals(null, survivor["TM"], "the detached field kept its export name")
        assertFalse(holds(out, state), "the state name survived somewhere in the file")
        assertFalse(holds(out, "TOOLTIP-TEXT"), "the tooltip survived somewhere in the file")
    }

    @Test fun a_dropped_annotation_kept_alive_by_something_else_loses_its_text() {
        // A FreeText is not a form field, so nothing detaches it: it is dropped from
        // /Annots and that is all. Its text lives in /Contents, the same text as rich
        // content in /RC (12.5.6.2), and its /AP draws it, so a second reference would
        // ship all three.
        val note = "FREETEXT-SECRET"
        val pdf = RawPdf.page(
            content = body,
            annots = "/Annots [6 0 R]",
            catalogExtra = "/StructTreeRoot 8 0 R",
            extra = listOf(
                RawPdf.obj(
                    6,
                    "<< /Type /Annot /Subtype /FreeText /Rect [100 700 300 720] /P 3 0 R " +
                        "/Contents ($note) /RC (<body>$note</body>) /AP << /N 7 0 R >> >>",
                ),
                apStream(7, note),
                RawPdf.obj(8, "<< /Type /StructTreeRoot /K 9 0 R >>"),
                RawPdf.obj(9, "<< /Type /StructElem /S /Annot /P 8 0 R /K << /Type /OBJR /Obj 6 0 R >> >>"),
            ),
        )
        assertTrue(holds(pdf, note), "fixture is wrong, the scan proves nothing")

        val doc = KitePDF.open(pdf)
        val out = doc.edit().apply { redactRegion(doc.pages[0], upperRegion) }.saveRewritten()

        val survivor = structTreeTarget(out)
        assertNotNull(survivor, "fixture is wrong, the structure tree no longer keeps the annotation alive")
        assertEquals("FreeText", survivor.getName("Subtype"), "the surviving object is not the annotation")
        assertEquals(null, survivor["Contents"], "the dropped annotation kept its text")
        assertEquals(null, survivor["RC"], "the dropped annotation kept its rich text")
        assertEquals(null, survivor["AP"], "the dropped annotation kept its appearance stream")
        assertFalse(holds(out, note), "the annotation text survived somewhere in the file")
    }

    @Test fun an_unreadable_field_list_still_detaches_the_widget_from_its_parent() {
        // The form has no /Fields at all. The parent field is still reachable through
        // the surviving widget's /Parent, so a /Kids entry left naming the redacted
        // widget keeps it alive: the /Kids detachment cannot be skipped just because
        // the form's root list is unusable.
        val pdf = RawPdf.page(
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
                RawPdf.obj(11, "<< /DA (/Helv 0 Tf 0 g) >>"),
            ),
        )
        val doc = KitePDF.open(pdf)
        val out = doc.edit().apply { redactRegion(doc.pages[0], upperRegion) }.saveRewritten()

        assertEquals(1, survivingWidgetKidCount(out), "the parent's /Kids still names the redacted widget")
        assertFalse(holds(out, "UPPER-COPY"), "the redacted widget's appearance stream is still in the file")
        assertTrue(holds(out, "LOWER-COPY"), "the surviving widget lost its appearance stream")
    }

    @Test fun a_dropped_attachment_sound_or_movie_keeps_none_of_its_payload() {
        // /FS is the attached file (12.5.6.15), /Sound the sound stream (12.5.6.16) and
        // /Movie the movie dictionary (12.5.6.17). Each is content in the same way
        // /Contents is, and none of the three annotations is a form field.
        val pdf = RawPdf.page(
            content = body,
            annots = "/Annots [6 0 R 7 0 R 8 0 R]",
            catalogExtra = "/StructTreeRoot 12 0 R",
            extra = listOf(
                RawPdf.obj(6, "<< /Type /Annot /Subtype /FileAttachment /Rect [100 700 150 720] /FS 9 0 R >>"),
                RawPdf.obj(7, "<< /Type /Annot /Subtype /Sound /Rect [160 700 200 720] /Sound 10 0 R >>"),
                RawPdf.obj(8, "<< /Type /Annot /Subtype /Movie /Rect [210 700 260 720] /Movie << /F (SECRET-MOVIE) >> >>"),
                RawPdf.obj(9, "<< /Type /Filespec /F (SECRET-FILENAME) /EF << /F 11 0 R >> >>"),
                RawPdf.obj(10, "<< /Type /Sound /R 44100 >>", "SECRET-SOUND-SAMPLES".encodeToByteArray()),
                RawPdf.obj(11, "<< /Type /EmbeddedFile >>", "SECRET-ATTACHED-BYTES".encodeToByteArray()),
                RawPdf.obj(12, "<< /Type /StructTreeRoot /K [13 0 R 14 0 R 15 0 R] >>"),
                RawPdf.obj(13, "<< /Type /StructElem /S /Annot /P 12 0 R /K << /Type /OBJR /Obj 6 0 R >> >>"),
                RawPdf.obj(14, "<< /Type /StructElem /S /Annot /P 12 0 R /K << /Type /OBJR /Obj 7 0 R >> >>"),
                RawPdf.obj(15, "<< /Type /StructElem /S /Annot /P 12 0 R /K << /Type /OBJR /Obj 8 0 R >> >>"),
            ),
        )
        assertEquals(3, structTreeTargets(pdf).size, "fixture is wrong, it must keep three annotations alive")

        val doc = KitePDF.open(pdf)
        val out = doc.edit().apply { redactRegion(doc.pages[0], upperRegion) }.saveRewritten()

        val survivors = structTreeTargets(out).associateBy { it.getName("Subtype") }
        assertEquals(
            setOf<String?>("FileAttachment", "Sound", "Movie"),
            survivors.keys,
            "fixture is wrong, the structure tree no longer keeps all three alive",
        )
        assertEquals(null, survivors.getValue("FileAttachment")["FS"], "the attachment kept its file")
        assertEquals(null, survivors.getValue("Sound")["Sound"], "the sound annotation kept its sound")
        assertEquals(null, survivors.getValue("Movie")["Movie"], "the movie annotation kept its movie")
        assertFalse(holds(out, "SECRET-FILENAME"), "the attached file specification survived")
        assertFalse(holds(out, "SECRET-ATTACHED-BYTES"), "the embedded file survived")
        assertFalse(holds(out, "SECRET-SOUND-SAMPLES"), "the sound samples survived")
        assertFalse(holds(out, "SECRET-MOVIE"), "the movie survived")
    }

    /* ─── Malformed input ────────────────────────────────────────────────── */

    @Test fun redacting_a_page_does_not_delete_a_sibling_page() {
        // A producer that writes /Parent where it means /P points the annotation at the
        // page itself. Treating that as a field walks into the page tree: the page has
        // no /Kids so it reads as an emptied field, and its own parent is the /Pages
        // node, whose /Kids is every page in the document.
        val pdf = RawPdf.twoPages(
            content = body,
            annots = "/Annots [7 0 R]",
            extra = listOf(
                RawPdf.obj(
                    7,
                    "<< /Type /Annot /Subtype /FreeText /Rect [100 700 300 720] /Contents (NOTE-TEXT) /Parent 3 0 R >>",
                ),
            ),
        )
        assertEquals(2, KitePDF.open(pdf).pages.size, "fixture is wrong, it must have two pages")

        val doc = KitePDF.open(pdf)
        val out = doc.edit().apply { redactRegion(doc.pages[0], upperRegion) }.saveRewritten()

        val reopened = KitePDF.open(out)
        assertEquals(2, reopened.pages.size, "redacting page one deleted a page from the document")
        assertEquals(2, reopened.pageCount, "the page tree /Count no longer matches its /Kids")
        assertFalse(holds(out, "NOTE-TEXT"), "the dropped annotation kept its text")
    }

    @Test fun a_dropped_annotation_pointing_at_a_note_leaves_the_note_whole() {
        // The same producer error as the page case, aimed sideways: a FreeText whose
        // /Parent names the note it was drawn beside. A markup annotation carries /T
        // too, but there it is the author (12.5.6.4), so a walk that took any /T for a
        // field name would strip the title and appearance off a note nobody redacted.
        val pdf = RawPdf.page(
            content = body,
            annots = "/Annots [6 0 R 7 0 R]",
            extra = listOf(
                RawPdf.obj(
                    6,
                    "<< /Type /Annot /Subtype /Text /Rect [400 400 420 420] /T (AUTHOR-NAME) " +
                        "/Contents (a note) /AP << /N 8 0 R >> >>",
                ),
                RawPdf.obj(
                    7,
                    "<< /Type /Annot /Subtype /FreeText /Rect [100 700 300 720] /Contents (DROPPED-TEXT) /Parent 6 0 R >>",
                ),
                apStream(8, "NOTE-BODY"),
            ),
        )
        val doc = KitePDF.open(pdf)
        val out = doc.edit().apply { redactRegion(doc.pages[0], upperRegion) }.saveRewritten()

        assertEquals(1, KitePDF.open(out).pages[0].annotations.size, "the note outside the region was pruned")
        assertTrue(holds(out, "AUTHOR-NAME"), "the note was scrubbed as if it were a form field")
        assertTrue(holds(out, "NOTE-BODY"), "the note lost its appearance stream")
        assertFalse(holds(out, "DROPPED-TEXT"), "the dropped annotation kept its text")
    }

    @Test fun a_popup_annotation_is_not_walked_as_a_form_field() {
        // A Popup's /Parent is the markup annotation it belongs to (12.5.6.14), not a
        // field parent. Walking it as one detaches and scrubs that annotation, taking
        // its /T and /AP with it, and a self-referential one aborts the redaction.
        // Both popups are in the region; the note itself is not.
        val pdf = RawPdf.page(
            content = body,
            annots = "/Annots [6 0 R 7 0 R 8 0 R]",
            extra = listOf(
                RawPdf.obj(
                    6,
                    "<< /Type /Annot /Subtype /Text /Rect [400 400 420 420] /T (AUTHOR-NAME) " +
                        "/Contents (a note) /Popup 7 0 R /AP << /N 9 0 R >> >>",
                ),
                RawPdf.obj(7, "<< /Type /Annot /Subtype /Popup /Rect [100 700 300 720] /Parent 6 0 R >>"),
                RawPdf.obj(8, "<< /Type /Annot /Subtype /Popup /Rect [100 700 300 720] /Parent 8 0 R >>"),
                apStream(9, "NOTE-BODY"),
            ),
        )
        val doc = KitePDF.open(pdf)
        val out = doc.edit().apply { redactRegion(doc.pages[0], upperRegion) }.saveRewritten()

        val reopened = KitePDF.open(out)
        assertEquals(1, reopened.pages[0].annotations.size, "the note outside the region was pruned")
        assertTrue(holds(out, "AUTHOR-NAME"), "the popup's parent annotation was scrubbed as if it were a field")
        assertTrue(holds(out, "NOTE-BODY"), "the popup's parent annotation lost its appearance stream")
    }

    @Test fun a_refused_detach_still_blocks_an_incremental_save() {
        // The page is rewritten before the detach can raise, so an incremental save
        // would append the redacted page and leave every original byte in the file
        // where it is still recoverable. The refusal has to be armed before the raise.
        val doc = KitePDF.open(loopingParentPdf())
        val editor = doc.edit()
        assertFailsWith<IllegalStateException> { editor.redactRegion(doc.pages[0], upperRegion) }

        val refusal = assertFailsWith<IllegalStateException> { editor.saveIncremental() }
        assertTrue(
            refusal.message.orEmpty().contains("saveRewritten"),
            "an incremental save was allowed after a refused redaction: ${refusal.message}",
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
