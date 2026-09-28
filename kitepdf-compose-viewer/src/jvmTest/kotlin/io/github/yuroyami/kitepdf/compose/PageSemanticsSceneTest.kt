package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.PdfAction
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.PdfScriptHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A screen reader finds a page's text, its links and its form fields, each with a name and a
 * role at its place, and a link or a field node acts as a tap on it does (#427).
 */
class PageSemanticsSceneTest {

    /** A 200 x 300 page: a line of text, a web link over its own words, a text field and a check box. */
    private fun pdf(): PdfDocument {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        val content = "BT /F1 18 Tf 20 260 Td (Opening words) Tj ET BT /F1 12 Tf 20 200 Td (Visit site) Tj ET"
        add("<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [6 0 R 7 0 R] >> >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 300] >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R /Annots [8 0 R 6 0 R 7 0 R] >>")
        add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        add("<< /Length ${content.length} >>\nstream\n$content\nendstream")
        add("<< /Type /Annot /Subtype /Widget /FT /Tx /T (email) /TU (Email address) /V () /Rect [20 120 180 145] /DA (/Helv 12 Tf 0 g) >>")
        add(
            "<< /Type /Annot /Subtype /Widget /FT /Btn /T (agree) /TU (I agree) /V /Off /AS /Off /Rect [20 80 40 100] " +
                "/AP << /N << /Yes 9 0 R /Off 10 0 R >> >> >>",
        )
        add("<< /Type /Annot /Subtype /Link /Rect [18 195 90 215] /A << /S /URI /URI (https://example.com/site) >> >>")
        add("<< /Type /XObject /Subtype /Form /BBox [0 0 20 20] /Length 16 >>\nstream\n0 g 4 4 12 12 re f\nendstream")
        add("<< /Type /XObject /Subtype /Form /BBox [0 0 20 20] /Length 0 >>\nstream\n\nendstream")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(sb.toString().encodeToByteArray())
    }

    /** Keeps every key, as a field with no keystroke script does. */
    private class Scripts(document: PdfDocument) : PdfScriptHandler {
        override val formState: PdfFormState = PdfFormState(document)
        override fun keystroke(fieldName: String, change: String, selectionStart: Int, selectionEnd: Int): String = change
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        val out = ArrayList<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            out += node
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return out
    }

    private fun described(scene: ImageComposeScene, name: String): SemanticsNode? =
        nodes(scene).firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { d -> d == name } }

    private fun texted(scene: ImageComposeScene, words: String): SemanticsNode? =
        nodes(scene).firstOrNull { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == words } }

    @Test
    fun a_page_gives_its_text_links_and_fields_with_names_roles_and_actions() {
        for (render in listOf(KiteRenderSpec.Rasterized(), KiteRenderSpec.Vectorized())) {
            val doc = pdf()
            val scripts = Scripts(doc)
            val state = KiteDocViewState(doc)
            val tapped = ArrayList<KiteLinkAction>()
            ImageComposeScene(width = 200, height = 300, density = Density(1f)) {
                KiteDocView(
                    state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Paged(), renderSpec = render,
                    scripts = scripts, onLinkTap = { tapped += it; true },
                )
            }.use { scene ->
                val driver = SceneTestDriver(scene)
                driver.pumpUntilState { texted(scene, "Opening words") != null }
                val text = texted(scene, "Opening words")!!
                // The node sits over its words, near the top of the page.
                assertTrue(
                    text.boundsInRoot.top < 60f && text.boundsInRoot.height in 5f..40f && text.boundsInRoot.left in 10f..30f,
                    "$render: the text node is at ${text.boundsInRoot}",
                )

                val link = assertNotNull(described(scene, "Visit site"), "$render: no node names the link")
                assertEquals(Role.Button, link.config.getOrNull(SemanticsProperties.Role))
                link.config[SemanticsActions.OnClick].action!!.invoke()
                driver.pumpUntilState { tapped.isNotEmpty() }
                assertEquals("https://example.com/site", ((tapped.single() as KiteLinkAction.Pdf).action as PdfAction.Uri).uri)

                val email = assertNotNull(described(scene, "Email address"), "$render: no node names the text field")
                email.config[SemanticsActions.OnClick].action!!.invoke()
                driver.pumpUntilState { state.focusedField == "email" }

                val agree = assertNotNull(described(scene, "I agree"), "$render: no node names the check box")
                assertEquals(Role.Checkbox, agree.config.getOrNull(SemanticsProperties.Role))
                assertEquals(ToggleableState.Off, agree.config.getOrNull(SemanticsProperties.ToggleableState))
                agree.config[SemanticsActions.OnClick].action!!.invoke()
                driver.pumpUntilState {
                    described(scene, "I agree")?.config?.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On
                }
            }
        }
    }
}
