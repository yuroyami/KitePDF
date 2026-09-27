package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.PdfAction
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.PdfScriptHandler
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A real tap on a form widget, through the viewer: it reaches the widget under the finger (#359),
 * skips a widget a script hid (#360), performs the widget's own action (#361), and clears a radio
 * group that allows no selection (#440).
 */
class WidgetTapSceneTest {

    /** A PDF whose objects are [bodies], numbered from 1. Object 1 is the catalog; pages are 200 x 200. */
    private fun pdf(vararg bodies: String): ByteArray {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        for ((index, body) in bodies.withIndex()) {
            offsets.add(sb.length)
            sb.append("${index + 1} 0 obj\n$body\nendobj\n")
        }
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    /** A handler that records what the viewer asks of it, from whatever thread it asks. */
    private class Recorder(document: PdfDocument) : PdfScriptHandler {
        override val formState: PdfFormState = PdfFormState(document)
        val events: MutableList<String> = Collections.synchronizedList(ArrayList())
        override fun mouseDown(fieldName: String, widgetIndex: Int) { events += "down $fieldName $widgetIndex" }
        override fun mouseUp(fieldName: String, widgetIndex: Int) { events += "up $fieldName $widgetIndex" }
        override fun focus(fieldName: String, widgetIndex: Int) { events += "focus $fieldName $widgetIndex" }
        override fun runWidgetAction(fieldName: String, action: PdfAction): Boolean {
            events += "action $fieldName ${action::class.simpleName}"
            return super.runWidgetAction(fieldName, action)
        }
    }

    private class Viewer(val scene: ImageComposeScene, val driver: SceneTestDriver, val state: () -> KiteDocViewState)

    private fun viewer(
        doc: PdfDocument,
        scripts: PdfScriptHandler,
        queued: Boolean,
        layout: KiteDocLayout = KiteDocLayout.SinglePage(0),
        onTap: ((Offset) -> Unit)? = null,
        onLinkTap: ((KiteLinkAction) -> Boolean)? = null,
    ): Viewer {
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(200, 200, queued) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(
                state = state,
                modifier = Modifier.fillMaxSize(),
                layout = layout,
                zoomSpec = KiteZoomSpec(doubleTapEnabled = false),
                scripts = scripts,
                onTap = onTap,
                onLinkTap = onLinkTap,
            )
        }
        driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
        return Viewer(scene, driver) { state }
    }

    private fun Viewer.tap(x: Float, y: Float) {
        scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), type = PointerType.Touch)
        scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), type = PointerType.Touch)
    }

    /**
     * A radio group with buttons `a` at [30 30 50 50] and `b` at [80 30 100 50], centred at display
     * (40, 160) and (90, 160).
     */
    private fun radioPdf(flags: Int, hidden: String = ""): ByteArray = pdf(
        "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] >> >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>",
        "<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [5 0 R 6 0 R] >>",
        "<< /FT /Btn /Ff $flags /T (choice) /V /Off /Kids [5 0 R 6 0 R] >>",
        "<< /Type /Annot /Subtype /Widget /Parent 4 0 R /Rect [30 30 50 50] $hidden /AS /Off /AP << /N << /a 7 0 R /Off 7 0 R >> >> >>",
        "<< /Type /Annot /Subtype /Widget /Parent 4 0 R /Rect [80 30 100 50] /AS /Off /AP << /N << /b 7 0 R /Off 7 0 R >> >> >>",
        "<< /Type /XObject /Subtype /Form /BBox [0 0 20 20] /Length 0 >>\nstream\n\nendstream",
    )

    @Test
    fun a_tap_selects_the_radio_button_under_the_finger_and_runs_its_scripts() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(radioPdf(flags = 49152))
            val scripts = Recorder(doc)
            viewer(doc, scripts, queued).run {
                scene.use {
                    tap(90f, 160f)
                    driver.pumpUntilState { scripts.formState.value("choice") == "b" }
                    driver.pumpUntilState { "up choice 1" in scripts.events }
                    assertEquals(listOf("down choice 1", "up choice 1"), scripts.events.toList())
                    tap(40f, 160f)
                    driver.pumpUntilState { scripts.formState.value("choice") == "a" }
                    // The group keeps one button selected, so a tap on the selected one changes nothing.
                    tap(40f, 160f)
                    driver.pumpUntilState { scripts.events.count { it == "up choice 0" } == 2 }
                    assertEquals("a", scripts.formState.value("choice"))
                }
            }
        }
    }

    @Test
    fun a_tap_on_the_selected_radio_button_clears_a_group_that_allows_no_selection() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(radioPdf(flags = 32768))
            val scripts = Recorder(doc)
            viewer(doc, scripts, queued).run {
                scene.use {
                    tap(40f, 160f)
                    driver.pumpUntilState { scripts.formState.value("choice") == "a" }
                    tap(40f, 160f)
                    driver.pumpUntilState { scripts.formState.value("choice") == "Off" }
                }
            }
        }
    }

    @Test
    fun a_tap_on_the_second_page_reaches_the_field_of_that_page() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(
                pdf(
                    "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [5 0 R 6 0 R] >> >>",
                    "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 200 200] >>",
                    "<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [5 0 R] >>",
                    "<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [6 0 R] >>",
                    "<< /Type /Annot /Subtype /Widget /FT /Tx /T (first) /Rect [30 30 50 50] >>",
                    "<< /Type /Annot /Subtype /Widget /FT /Tx /T (second) /Rect [30 30 50 50] >>",
                ),
            )
            val scripts = Recorder(doc)
            viewer(doc, scripts, queued, layout = KiteDocLayout.SinglePage(1)).run {
                scene.use {
                    tap(40f, 160f)
                    driver.pumpUntilState { "focus second 0" in scripts.events }
                    assertEquals("second", state().focusedField)
                }
            }
        }
    }

    @Test
    fun a_widget_a_script_hid_lets_the_tap_through_and_one_it_showed_takes_it() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(radioPdf(flags = 49152, hidden = "/F 2"))
            val scripts = Recorder(doc)
            val taps = Collections.synchronizedList(ArrayList<Offset>())
            viewer(doc, scripts, queued, onTap = { taps += it }).run {
                scene.use {
                    // The file hides button a, so the tap is the page's.
                    tap(40f, 160f)
                    driver.pumpUntilState { taps.size == 1 }
                    // A script shows it, and it takes the next tap.
                    scripts.formState.setHidden("choice", false)
                    tap(40f, 160f)
                    driver.pumpUntilState { scripts.formState.value("choice") == "a" }
                    // A script hides the group, and button b lets the tap through as well.
                    scripts.formState.setHidden("choice", true)
                    tap(90f, 160f)
                    driver.pumpUntilState { taps.size == 2 }
                    driver.pumpFrames(10)
                    assertEquals("a", scripts.formState.value("choice"), "a hidden button changes nothing")
                }
            }
        }
    }

    /**
     * A push button at [20 20 90 60], centred at display (55, 160), whose `/A` is [action], and a
     * text field `name` holding `Ada` with no default. Page 2 is empty.
     */
    private fun buttonPdf(action: String): ByteArray = pdf(
        "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [5 0 R 6 0 R] >> >>",
        "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 200 200] >>",
        "<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [5 0 R 6 0 R] >>",
        "<< /Type /Page /Parent 2 0 R /Resources << >> >>",
        "<< /Type /Annot /Subtype /Widget /FT /Btn /Ff 65536 /T (go) /Rect [20 20 90 60] " +
            "/AA << /U << /S /JavaScript /JS (up) >> >> /A $action >>",
        "<< /Type /Annot /Subtype /Widget /FT /Tx /T (name) /V (Ada) /Rect [20 120 180 160] >>",
    )

    @Test
    fun a_push_button_runs_its_own_script_in_place_of_its_release_script() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(buttonPdf("<< /S /JavaScript /JS (go) >>"))
            val scripts = Recorder(doc)
            viewer(doc, scripts, queued).run {
                scene.use {
                    tap(55f, 160f)
                    driver.pumpUntilState { "action go JavaScript" in scripts.events }
                    driver.pumpFrames(5)
                    assertEquals(listOf("down go 0", "action go JavaScript"), scripts.events.toList())
                }
            }
        }
    }

    @Test
    fun a_reset_button_gives_the_fields_their_default_values_back() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(buttonPdf("<< /S /ResetForm >>"))
            val scripts = Recorder(doc)
            scripts.formState.setValue("name", "Bob")
            viewer(doc, scripts, queued).run {
                scene.use {
                    tap(55f, 160f)
                    driver.pumpUntilState { scripts.formState.value("name") == "" }
                }
            }
        }
    }

    @Test
    fun a_next_page_button_turns_the_page() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(buttonPdf("<< /S /Named /N /NextPage >>"))
            val scripts = Recorder(doc)
            viewer(doc, scripts, queued, layout = KiteDocLayout.Paged()).run {
                scene.use {
                    tap(55f, 160f)
                    driver.pumpUntilState { state().currentPage == 1 }
                }
            }
        }
    }

    @Test
    fun a_go_to_button_goes_to_its_page() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(buttonPdf("<< /S /GoTo /D [4 0 R /Fit] >>"))
            val scripts = Recorder(doc)
            viewer(doc, scripts, queued, layout = KiteDocLayout.Paged()).run {
                scene.use {
                    tap(55f, 160f)
                    driver.pumpUntilState { state().currentPage == 1 }
                }
            }
        }
    }

    @Test
    fun a_chain_runs_in_order_and_hands_a_link_to_the_host() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(
                buttonPdf("<< /S /JavaScript /JS (first) /Next << /S /URI /URI (https://example.com/) >> >>"),
            )
            val scripts = Recorder(doc)
            val links = Collections.synchronizedList(ArrayList<KiteLinkAction>())
            viewer(
                doc, scripts, queued,
                onLinkTap = { link ->
                    scripts.events += "link"
                    links += link
                    true
                },
            ).run {
                scene.use {
                    tap(55f, 160f)
                    driver.pumpUntilState { links.isNotEmpty() }
                    val uri = (links.single() as KiteLinkAction.Pdf).action as PdfAction.Uri
                    assertEquals("https://example.com/", uri.uri)
                    assertTrue(scripts.events.indexOf("action go JavaScript") < scripts.events.indexOf("link"), "${scripts.events}")
                }
            }
        }
    }
}
