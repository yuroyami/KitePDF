package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals

/** View usage must be selected by /AS (ISO 32000-1, 8.11.4.4, #57). */
class OptionalContentUsageTest {
    private fun pdf(
        application: String = "<< /Event /View /Category [/View] /OCGs [5 0 R] >>",
        state: String = "OFF",
        config: String = "/ON [5 0 R]",
        membership: String? = null,
        extraUsage: String = "",
    ): ByteArray = TestPdf.onePage(
        "0 0 1 rg 0 0 10 10 re f /OC /Layer BDC 1 0 0 rg 20 20 10 10 re f EMC",
        resources = "/Properties << /Layer ${if (membership == null) "5 0 R" else "6 0 R"} >>",
        catalogEntries = "/OCProperties << /OCGs [5 0 R] /D << $config /AS [$application] >> >>",
        extra = listOf("<< /Type /OCG /Name (Print marks) /Usage << /View << /ViewState /$state >> $extraUsage >> >>") +
            listOfNotNull(membership),
    )

    private fun fills(pdf: ByteArray) = TestPdf.calls(pdf).filterIsInstance<RecordingCanvas.Call.Fill>().size

    @Test
    fun view_off_suppresses_an_explicitly_on_layer() {
        val pdf = pdf()
        assertEquals(1, fills(pdf))
        assertEquals(false, PdfDocument.open(pdf).optionalContent!!.isVisibleByDefault("5"))
    }

    @Test
    fun view_on_enables_an_explicitly_off_layer() {
        assertEquals(2, fills(pdf(state = "ON", config = "/OFF [5 0 R]")))
    }

    @Test
    fun usage_does_not_act_without_a_matching_application() {
        for (application in listOf(
            "",
            "<< /Event /Print /Category [/View] /OCGs [5 0 R] >>",
            "<< /Event /View /Category [/Print] /OCGs [5 0 R] >>",
            "<< /Event /View /Category [/View] >>",
            "<< /Event /View /Category [/View] /OCGs [] >>",
            "<< /Event /View /Category [/View] /OCGs [99 0 R] >>",
            "42 << /Event /View /Category 42 /OCGs [5 0 R] >>",
        )) assertEquals(2, fills(pdf(application)), application)
    }

    @Test
    fun membership_policies_and_expressions_observe_the_view_state() {
        assertEquals(1, fills(pdf(membership = "<< /Type /OCMD /OCGs [5 0 R] /P /AllOn >>")))
        assertEquals(2, fills(pdf(membership = "<< /Type /OCMD /VE [/Not 5 0 R] >>")))
    }

    @Test
    fun multiple_applications_combine_their_recommendations() {
        val view = "<< /Event /View /Category [/View] /OCGs [5 0 R] >>"
        val export = "<< /Event /View /Category [/Export] /OCGs [5 0 R] >>"
        for (application in listOf("$view $export", "$export $view")) {
            assertEquals(1, fills(pdf(application, extraUsage = "/Export << /ExportState /ON >>")))
        }
    }

    @Test
    fun a_reference_to_a_missing_object_reads_as_null_and_hides_nothing() {
        // ISO 32000-1, 7.3.10: object 9 does not exist, so every page must still render (#252).
        for (application in listOf(
            "9 0 R",
            "<< /Event /View /Category [9 0 R] /OCGs [5 0 R] >>",
            "<< /Event /View /Category 9 0 R /OCGs [5 0 R] >>",
            "<< /Event /View /Category [/View] /OCGs 9 0 R >>",
        )) assertEquals(2, fills(pdf(application)), application)
        assertEquals(2, fills(pdf(extraUsage = "/Print 9 0 R", application = "<< /Event /View /Category [/Print] /OCGs [5 0 R] >>")))
        assertEquals(2, fills(pdf(membership = "<< /Type /OCMD /VE 9 0 R >>")))
        assertEquals(2, fills(pdf(membership = "<< /Type /OCMD /OCGs 9 0 R >>")))
        for ((catalog, expected) in listOf(
            "/OCProperties << /OCGs [5 0 R] /D 9 0 R >>" to 2,
            // The /OFF list still applies when only the /OCGs list is broken.
            "/OCProperties << /OCGs 9 0 R /D << /OFF [5 0 R] >> >>" to 1,
            "/OCProperties 9 0 R" to 2,
        )) {
            val doc = TestPdf.onePage(
                "0 0 1 rg 0 0 10 10 re f /OC /Layer BDC 1 0 0 rg 20 20 10 10 re f EMC",
                resources = "/Properties << /Layer 5 0 R >>",
                catalogEntries = catalog,
                extra = listOf("<< /Type /OCG /Name (L) >>"),
            )
            assertEquals(expected, fills(doc), catalog)
        }
        val danglingProperty = TestPdf.onePage(
            "/OC /Layer BDC 0 0 10 10 re f EMC",
            resources = "/Properties << /Layer 9 0 R >>",
            catalogEntries = "/OCProperties << /OCGs [5 0 R] >>",
            extra = listOf("<< /Type /OCG /Name (L) >>"),
        )
        assertEquals(1, fills(danglingProperty))
    }
}
