package io.github.yuroyami.kitepdf.document

import io.github.yuroyami.kitepdf.xps.XpsDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class KiteDocXpsTest {
    @Test fun sniffsAndOpensXpsAndOpenXpsPackages() {
        for (ns in listOf("http://schemas.microsoft.com/xps/2005/06", "http://schemas.openxps.org/oxps/v1.0")) {
            val entries = listOf(
                "_rels/.rels" to """<Relationships><Relationship Type="$ns/fixedrepresentation" Target="seq.bin"/></Relationships>""",
                "seq.bin" to """<FixedDocumentSequence xmlns="$ns"><DocumentReference Source="doc.bin"/></FixedDocumentSequence>""",
                "doc.bin" to """<FixedDocument><PageContent Source="page.bin"/></FixedDocument>""",
                "page.bin" to """<FixedPage Width="96" Height="192" xmlns="$ns"/>""",
            )
            val bytes = storedZip(entries.map { it.first to it.second.encodeToByteArray() })
            assertEquals(KiteDocFormat.Xps, KiteDoc.formatOf(bytes))
            val document = assertIs<XpsDocument>(KiteDoc.open(bytes))
            assertEquals(72.0, document.pages.single().displayWidth)
            assertEquals(144.0, document.pages.single().displayHeight)
        }
    }

    @Test fun filenameAloneDoesNotRecognizeXps() {
        assertNull(KiteDoc.formatOf(storedZip(listOf("fake.fdseq" to "<unrelated/>".encodeToByteArray()))))
    }
}
