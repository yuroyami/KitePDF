package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals

/** The HTML Standard's rendering section hides these elements, and a dialog until it is open (15.3.1; #931). */
class UnrenderedElementsTest {

    @Test
    fun theContentOfUnrenderedElementsIsNotOnThePage() {
        val body = """
            <p>Visible.</p>
            <template><p>TEMPLATE-TEXT</p></template>
            <noembed>NOEMBED-TEXT</noembed>
            <noframes>NOFRAMES-TEXT</noframes>
            <datalist id="d"><option>DATALIST-TEXT</option></datalist>
            <dialog><p>DIALOG-TEXT</p></dialog>
            <dialog open="open"><p>Open.</p></dialog>
            <p>End.</p>
        """.trimIndent()
        val doc = EpubDocument.open(EpubFixtures.epub(body))
        val text = doc.pages.joinToString(" ") { it.textContent().plainText }
        assertEquals(listOf("Visible.", "Open.", "End."), text.split(Regex("[ \\n]+")).filter { it.isNotEmpty() })
    }
}
