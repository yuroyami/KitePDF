package io.github.yuroyami.kitepdf.xps

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class XpsDocumentTest {
    private fun render(content: String, resources: List<Pair<String, ByteArray>> = emptyList()): RecordingCanvas =
        RecordingCanvas().also { XpsDocument.open(XpsFixtures.packageBytes(content, resources)).pages.single().renderTo(it) }

    @Test fun opensBothNamespacesAndUsesPhysicalPoints() {
        for (openXps in listOf(false, true)) {
            val bytes = XpsFixtures.packageBytes("", openXps = openXps)
            assertTrue(XpsDocument.isXps(bytes))
            val doc = XpsDocument.open(bytes)
            assertEquals(1, doc.pageCount)
            assertEquals(144.0, doc.pages[0].displayWidth)
            assertEquals(72.0, doc.pages[0].displayHeight)
            assertEquals(KiteMatrix.IDENTITY, doc.pages[0].displayToDeviceBase())
            assertSame(doc.pages[0], doc.page(KiteLocation(0, 0)))
        }
    }

    @Test fun followsReferencesInOrderAndKeepsMissingPages() {
        val parts = XpsFixtures.parts("").map { (name, bytes) ->
            if (name.endsWith("One.fdoc")) name to """<FixedDocument>
                <PageContent Source="Pages/missing.fpage" Width="96" Height="192"/>
                <PageContent Source="Pages/Page%201.fpage"/>
                </FixedDocument>""".encodeToByteArray() else name to bytes
        }
        val document = XpsDocument.open(XpsFixtures.storedZip(parts))
        assertEquals(2, document.pageCount)
        assertEquals(72.0, document.pages[0].displayWidth)
        val calls = RecordingCanvas().also { document.pages[0].renderTo(it) }.calls
        assertEquals(RgbColor(0.9, 0.9, 0.9), calls.filterIsInstance<RecordingCanvas.Call.Fill>().single().color)
        assertEquals(144.0, document.pages[1].displayWidth)
    }

    @Test fun doesNotMistakeArbitraryZipForXps() {
        val archive = XpsFixtures.storedZip(listOf("fake.fdseq" to "<not-xps/>".encodeToByteArray()))
        assertFalse(XpsDocument.isXps(archive))
        assertNull(XpsDocument.openOrNull(archive))
        assertNull(XpsDocument.openOrNull(byteArrayOf(1, 2, 3)))
    }

    @Test fun externalStartRelationshipIsNotFollowed() {
        val parts = XpsFixtures.parts("").map { (name, bytes) ->
            if (name == "_rels/.rels") name to """<Relationships><Relationship
              Type="http://schemas.microsoft.com/xps/2005/06/fixedrepresentation"
              TargetMode="External" Target="Payload/Sequence.bin"/></Relationships>""".encodeToByteArray() else name to bytes
        }
        assertFalse(XpsDocument.isXps(XpsFixtures.storedZip(parts)))
    }

    @Test fun recognizesContentTypeFallbackWithoutFilenameHeuristic() {
        val parts = XpsFixtures.parts("").filter { it.first != "_rels/.rels" }.map { (name, bytes) ->
            if (name == "[Content_Types].xml") name to """<Types><Override PartName="/Payload/Sequence.bin"
                ContentType="application/oxps-fixeddocumentsequence+xml"/></Types>""".encodeToByteArray() else name to bytes
        }
        assertEquals(1, XpsDocument.open(XpsFixtures.storedZip(parts)).pageCount)
    }

    @Test fun interleavedPagePiecesAreJoinedAndMustBeComplete() {
        val parts = XpsFixtures.parts("<Path Fill=\"#ff0000\" Data=\"M0,0 L20,0 20,20Z\"/>")
        val page = parts.last()
        val split = page.second.size / 2
        val pieces = parts.dropLast(1) + listOf(
            "${page.first}/[0].piece" to page.second.copyOfRange(0, split),
            "${page.first}/[1].last.piece" to page.second.copyOfRange(split, page.second.size),
        )
        val doc = XpsDocument.open(XpsFixtures.storedZip(pieces))
        val canvas = RecordingCanvas().also { doc.pages[0].renderTo(it) }
        assertEquals(RgbColor(1.0, 0.0, 0.0), canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().single().color)
        assertNull(XpsPackage(XpsFixtures.storedZip(pieces.dropLast(1))).read(page.first))
    }

    @Test fun uriResolutionIsPackageLocalAndDecodesEscapes() {
        assertEquals("Resources/a b.ttf", resolvePart("Pages/p.fpage", "../Resources/a%20b.ttf#2"))
        assertEquals("Resources/café.ttf", resolvePart("Pages/p.fpage", "/Resources/caf%C3%A9.ttf"))
        for (uri in listOf("https://example.com/font", "//server/font", "../../escape", "%2fescape", "a%5Cb", "a%00b")) {
            assertNull(resolvePart("Pages/p.fpage", uri), uri)
        }
    }

    @Test fun pathsPreserveTransformFillRuleStrokeAndArgb() {
        val canvas = render("""<Canvas RenderTransform="2,0,0,2,10,20">
           <Path Data="F1 M0,0 L10,0 10,10Z" Fill="#80FF0000" Stroke="#0000FF"
              StrokeThickness="2" StrokeDashArray="1,2" StrokeDashOffset="3" StrokeLineJoin="Round"/>
           </Canvas>""")
        val fill = canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().single()
        assertEquals(RgbColor(1.0, 0.0, 0.0), fill.color)
        assertEquals(128.0 / 255.0, fill.alpha)
        assertFalse(fill.evenOdd)
        assertEquals(KiteMatrix(1.5, 0.0, 0.0, 1.5, 7.5, 15.0), fill.ctm)
        val stroke = canvas.calls.filterIsInstance<RecordingCanvas.Call.Stroke>().single()
        assertEquals(listOf(2.0, 4.0), stroke.dashArray)
        assertEquals(6.0, stroke.dashPhase)
        assertEquals(1, stroke.lineJoin)
    }

    @Test fun corruptPathDoesNotLoseLaterSiblingsOrLeakClip() {
        val canvas = render("""<Canvas Clip="M0,0 L50,0 50,50Z">
            <Path Fill="#f00" Data="M0,0 C1,broken"/>
            <Glyphs Fill="#000" FontRenderingEmSize="12" OriginX="0" OriginY="12" UnicodeString="survives" FontUri="missing"/>
            <Path Fill="#00f" Data="M0,0 L10,0 10,10Z"/>
            </Canvas>""")
        assertEquals("survives", canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().joinToString("") { it.text })
        assertEquals(RgbColor(0.0, 0.0, 1.0), canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().single().color)
        assertEquals(canvas.calls.count { it is RecordingCanvas.Call.PushClip }, canvas.calls.count { it is RecordingCanvas.Call.PopClip })
        assertTrue(canvas.calls.last() is RecordingCanvas.Call.EndPage)
    }

    @Test fun canvasOpacityUsesOneGroupForOverlappingChildren() {
        val canvas = render("""<Canvas Opacity="0.5"><Path Fill="#f00" Data="M0,0L20,0 20,20Z"/>
             <Path Fill="#00f" Data="M10,0L30,0 30,20Z"/></Canvas>""")
        assertEquals(0.5, canvas.calls.filterIsInstance<RecordingCanvas.Call.PushGroup>().single().alpha)
        assertTrue(canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().all { it.alpha == 1.0 })
        assertEquals(1, canvas.calls.count { it is RecordingCanvas.Call.PopGroup })
    }

    @Test fun resourceDictionarySourceKeepsItsOwnBaseUri() {
        val dictionary = """<ResourceDictionary xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">
          <ImageBrush x:Key="picture" ImageSource="images/red.bmp" Viewbox="0,0,2,1" Viewport="10,20,40,20"/>
          <SolidColorBrush x:Key="red" Color="#f00"/>
          </ResourceDictionary>"""
        val canvas = render("""<FixedPage.Resources><ResourceDictionary Source="../../Resources/dictionary.xaml"/></FixedPage.Resources>
          <Path Data="M0,0L100,0 100,80 0,80Z" Fill="{StaticResource picture}"/>
          <Canvas><Canvas.Resources><ResourceDictionary><SolidColorBrush Key="red" Color="#00f"/></ResourceDictionary></Canvas.Resources>
            <Path Data="M0,0L1,0 1,1Z" Fill="{StaticResource red}"/>
          </Canvas><Path Data="M0,0L1,0 1,1Z" Fill="{StaticResource red}"/>""",
            listOf("Resources/dictionary.xaml" to dictionary.encodeToByteArray(), "Resources/images/red.bmp" to XpsFixtures.bmp2x1()))
        val image = canvas.calls.filterIsInstance<RecordingCanvas.Call.Image>().single()
        assertEquals(KiteMatrix(30.0, 0.0, 0.0, -15.0, 7.5, 30.0), image.ctm)
        assertEquals(listOf(RgbColor(0.0, 0.0, 1.0), RgbColor(1.0, 0.0, 0.0)),
            canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().map { it.color })
    }

    @Test fun cyclicResourceDictionaryTerminatesAndOtherResourcesSurvive() {
        val dictionary = """<ResourceDictionary Source="dictionary.xaml"><SolidColorBrush Key="red" Color="#f00"/></ResourceDictionary>"""
        val canvas = render("""<FixedPage.Resources><ResourceDictionary Source="../../Resources/dictionary.xaml"/></FixedPage.Resources>
           <Path Fill="{StaticResource red}" Data="M0,0L10,0 10,10Z"/>""", listOf("Resources/dictionary.xaml" to dictionary.encodeToByteArray()))
        assertEquals(1, canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().size)
    }

    @Test fun embeddedFontAndGuidObfuscatedFontBothDrawOutlines() {
        val plain = XpsFixtures.squareTtf()
        val part = "00112233-4455-6677-8899-aabbccddeeff.odttf"
        val obfuscated = assertNotNull(deobfuscateFont(plain, part))
        for ((name, font) in listOf("font.ttf" to plain, part to obfuscated)) {
            val canvas = render("""<Glyphs Fill="#000" FontUri="../../Resources/$name" FontRenderingEmSize="20"
                 OriginX="10" OriginY="30" UnicodeString="AA"/>""", listOf("Resources/$name" to font))
            val glyphs = canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
            assertEquals(2, glyphs.size)
            assertTrue(glyphs.all { it.hasOutlines && it.glyphs.single().outline != null })
            assertEquals(1, glyphs[0].glyphs.single().gid)
            assertEquals(7.5, glyphs[0].textToDevice.e)
            assertEquals(16.5, glyphs[1].textToDevice.e)
            assertEquals(-0.75, glyphs[0].textToDevice.d)
        }
    }

    @Test fun obfuscationUsesGuidBytesInReverseAndOnlyFirst32Bytes() {
        val original = ByteArray(40)
        val decoded = assertNotNull(deobfuscateFont(original, "00112233-4455-6677-8899-aabbccddeeff.odttf"))
        val expected = byteArrayOf(0xff.toByte(), 0xee.toByte(), 0xdd.toByte(), 0xcc.toByte(), 0xbb.toByte(), 0xaa.toByte(),
            0x99.toByte(), 0x88.toByte(), 0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11, 0x00)
        assertContentEquals(expected + expected + ByteArray(8), decoded)
        assertContentEquals(ByteArray(40), original)
        assertNull(deobfuscateFont(ByteArray(31), "00112233-4455-6677-8899-aabbccddeeff.odttf"))
        assertNull(deobfuscateFont(ByteArray(32), "invalid.odttf"))
    }

    @Test fun indicesOverrideUnicodeAndAdvanceWithClusterExtraction() {
        val content = """<Glyphs Fill="#000" FontUri="../../Resources/font.ttf" FontRenderingEmSize="20"
            OriginX="10" OriginY="30" UnicodeString="fiA" Indices="(2:1)1,80,10,20;1,50"/>"""
        val bytes = XpsFixtures.packageBytes(content, listOf("Resources/font.ttf" to XpsFixtures.squareTtf()))
        val doc = XpsDocument.open(bytes)
        val canvas = RecordingCanvas().also { doc.pages.single().renderTo(it) }
        val glyphs = canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        assertEquals(listOf("fi", "A"), glyphs.map { it.text })
        assertEquals(9.0, glyphs[0].textToDevice.e)
        assertEquals(19.5, glyphs[0].textToDevice.f)
        assertEquals(19.5, glyphs[1].textToDevice.e)
        assertEquals("fiA", doc.pages[0].textContent().plainText)
        assertEquals(1, doc.pages[0].textContent().search("fi").size)
    }

    @Test fun supplementaryUnicodeAndMissingFontsRemainReadable() {
        val bytes = XpsFixtures.packageBytes("""<Glyphs Fill="#000" FontUri="missing" FontRenderingEmSize="12"
            UnicodeString="{}😀A" OriginX="0" OriginY="20"/>""")
        val doc = XpsDocument.open(bytes)
        assertEquals("😀A", doc.pages.single().textContent().plainText)
        val calls = RecordingCanvas().also { doc.pages.single().renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        assertEquals(2, calls.size)
        assertFalse(calls[0].hasOutlines)
    }

    @Test fun gradientTextWithAMissingFontStaysReadable() {
        // #267: no outline to fill, so the substitute text takes the first stop's colour.
        val canvas = render("""<Glyphs FontUri="missing.odttf" FontRenderingEmSize="20" OriginX="10" OriginY="30"
            UnicodeString="Title"><Glyphs.Fill><LinearGradientBrush StartPoint="0,0" EndPoint="100,0">
            <LinearGradientBrush.GradientStops><GradientStop Offset="1" Color="#0000ff"/>
            <GradientStop Offset="0" Color="#ff0000"/></LinearGradientBrush.GradientStops>
            </LinearGradientBrush></Glyphs.Fill></Glyphs>""")
        val glyphs = canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        assertEquals("Title", glyphs.joinToString("") { it.text })
        assertTrue(glyphs.all { it.color == RgbColor(1.0, 0.0, 0.0) })
    }

    @Test fun clipUsesOnlyTheFilledFigures() {
        // ECMA-388, 11.2.1 (#268): the unfilled figure takes no part in the clip.
        val canvas = render("""<Canvas><Canvas.Clip><PathGeometry>
          <PathFigure StartPoint="0,0" IsClosed="true" IsFilled="false"><PolyLineSegment Points="500,0 500,500 0,500"/></PathFigure>
          <PathFigure StartPoint="10,10" IsClosed="true"><PolyLineSegment Points="20,10 20,20 10,20"/></PathFigure>
          </PathGeometry></Canvas.Clip><Path Data="M0,0L100,0 100,100 0,100Z" Fill="#f00"/></Canvas>""")
        // The first clip is the page box; the canvas clip comes after it.
        val clip = canvas.calls.filterIsInstance<RecordingCanvas.Call.PushClip>().last()
        assertEquals(KitePath.Segment.MoveTo(10.0, 10.0), clip.path.segments.first())
        assertTrue(clip.path.segments.none { it is KitePath.Segment.LineTo && it.x == 500.0 })
    }

    @Test fun geometryPropertiesHandleCurvesAndUnfilledFigures() {
        val canvas = render("""<Path Stroke="#000" Fill="#f00"><Path.Data><PathGeometry FillRule="NonZero">
          <PathFigure StartPoint="0,0" IsClosed="true" IsFilled="false"><PolyBezierSegment Points="0,10 10,10 10,0"/></PathFigure>
          <PathFigure StartPoint="20,0" IsClosed="true"><PolyLineSegment Points="30,0 30,10"/></PathFigure>
          </PathGeometry></Path.Data></Path>""")
        val fill = canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().single()
        assertEquals(KitePath.Segment.MoveTo(20.0, 0.0), fill.path.segments.first())
        assertFalse(fill.evenOdd)
        assertTrue(canvas.calls.filterIsInstance<RecordingCanvas.Call.Stroke>().single().path.segments.any { it is KitePath.Segment.CurveTo })
    }

    @Test fun arcAndRelativeCommandsProduceFiniteCurves() {
        val geometry = XpsPaths.parse("M10,10 h20 v20 a10,10 0 0 1 -20,0 z")
        assertTrue(geometry.path.segments.any { it is KitePath.Segment.CurveTo })
        assertTrue(geometry.path.segments.filterIsInstance<KitePath.Segment.CurveTo>().all { it.x3.isFinite() && it.y3.isFinite() })
    }

    @Test fun visualBrushRendersInsideMappedTile() {
        val canvas = render("""<Path Data="M0,0L100,0 100,50 0,50Z"><Path.Fill>
          <VisualBrush Viewbox="0,0,10,10" Viewport="20,10,40,40"><VisualBrush.Visual>
          <Path Data="M0,0L10,0 10,10Z" Fill="#f00"/>
          </VisualBrush.Visual></VisualBrush></Path.Fill></Path>""")
        assertEquals(KiteMatrix(3.0, 0.0, 0.0, 3.0, 15.0, 7.5), canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().single().ctm)
    }

    @Test fun gradientBrushPaintsAndArgbScRgbHaveCorrectChannels() {
        val canvas = render("""<Path Data="M0,0L100,0 100,50 0,50Z"><Path.Fill>
          <LinearGradientBrush StartPoint="0,0" EndPoint="100,0"><LinearGradientBrush.GradientStops>
          <GradientStop Offset="0" Color="#ff0000"/><GradientStop Offset="1" Color="#0000ff"/>
          </LinearGradientBrush.GradientStops></LinearGradientBrush></Path.Fill></Path>""")
        assertTrue(canvas.calls.any { it is RecordingCanvas.Call.Fill })
        assertEquals(RgbColor(1.0, 0.0, 0.0), assertNotNull(xpsColor("#8f00")).color)
        assertEquals(0.5, assertNotNull(xpsColor("sc#0.5,1,0,0")).alpha)
    }
    @Test fun geometryTransformChangesVerticesWithoutScalingStrokeOrBrush() {
        val canvas = render("""<Path Fill="#f00" Stroke="#000" StrokeThickness="2"><Path.Data>
          <PathGeometry Transform="2,0,0,2,10,20" Figures="M0,0L10,0 10,10Z"/>
          </Path.Data></Path>""")
        val stroke = canvas.calls.filterIsInstance<RecordingCanvas.Call.Stroke>().single()
        assertEquals(KiteMatrix.scaling(0.75, 0.75), stroke.ctm)
        assertEquals(2.0, stroke.lineWidth)
        assertEquals(KitePath.Segment.MoveTo(10.0, 20.0), stroke.path.segments.first())
    }

    @Test fun rtlSearchAndHitTestingKeepLogicalTextWithPositiveQuads() {
        val bytes = XpsFixtures.packageBytes("""<Glyphs Fill="#000" FontRenderingEmSize="12"
          OriginX="100" OriginY="30" BidiLevel="1" UnicodeString="אב" Indices=",50;,50"/>""")
        val text = XpsDocument.open(bytes).pages.single().textContent()
        assertEquals("אב", text.plainText)
        assertEquals(0, text.charIndexAt(72.0, 20.0))
        val box = text.search("אב").single().quads.single()
        assertEquals(66.0, box.left)
        assertEquals(75.0, box.right)
        assertTrue(box.width > 0)
    }

    @Test fun manyGlyphClusterSelectionSpansEveryGlyph() {
        val bytes = XpsFixtures.packageBytes("""<Glyphs Fill="#000" FontRenderingEmSize="20"
          FontUri="../../Resources/font.ttf" OriginX="0" OriginY="30" UnicodeString="A" Indices="(1:2)1,30;1,40"/>""",
          listOf("Resources/font.ttf" to XpsFixtures.squareTtf()))
        val text = XpsDocument.open(bytes).pages.single().textContent()
        assertEquals("A", text.plainText)
        assertEquals(10.5, text.search("A").single().quads.single().width)
    }

    @Test fun trueTypeCollectionFaceIndexIsApplied() {
        val ttf = XpsFixtures.squareTtf()
        val ttc = ByteArray(ttf.size + 16)
        "ttcf".encodeToByteArray().copyInto(ttc)
        ttc[5] = 1; ttc[11] = 1; ttc[15] = 16
        ttf.copyInto(ttc, 16)
        val tables = (ttf[4].toInt() and 255) * 256 + (ttf[5].toInt() and 255)
        for (i in 0 until tables) {
            val at = 12 + i * 16 + 8
            var offset = 0
            for (j in 0..3) offset = (offset shl 8) or (ttf[at + j].toInt() and 255)
            offset += 16
            for (j in 0..3) ttc[16 + at + j] = (offset ushr (24 - j * 8)).toByte()
        }
        val font = assertNotNull(XpsFont.parse(ttc, 0))
        assertNotNull(font.outline(1))
        assertEquals(600, font.ttf.advanceWidth(1))
        assertNull(XpsFont.parse(ttc, 1))
    }

    @Test fun recursiveVisualBrushFanOutIsCutOffWithoutLosingSiblings() {
        val canvas = render("""<FixedPage.Resources><ResourceDictionary>
          <Canvas Key="loop"><Path Fill="{StaticResource brush}" Data="M0,0L10,0 10,10Z"/>
            <Path Fill="{StaticResource brush}" Data="M0,0L10,0 10,10Z"/></Canvas>
          <VisualBrush Key="brush" Visual="{StaticResource loop}" Viewbox="0,0,10,10" Viewport="0,0,10,10"/>
          </ResourceDictionary></FixedPage.Resources>
          <Path Fill="{StaticResource brush}" Data="M0,0L10,0 10,10Z"/>
          <Path Fill="#f00" Data="M20,20L30,20 30,30Z"/>""")
        assertEquals(1, canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().size)
        assertTrue(canvas.calls.size < 100)
        assertEquals(canvas.calls.count { it is RecordingCanvas.Call.PushClip }, canvas.calls.count { it is RecordingCanvas.Call.PopClip })
    }

    @Test fun glyphClusterFanOutHonorsThePageBudget() {
        val node = io.github.yuroyami.kitepdf.core.xml.KiteXmlNode.Element("glyphs", mapOf(
            "unicodestring" to "A", "indices" to "(1:65536)1", "fontrenderingemsize" to "12"))
        assertEquals(12, layoutGlyphs(node, null, false, maxGlyphs = 12).glyphs.size)
    }

    @Test fun relativePartUrisDecodeOnlyTheNewReference() {
        assertEquals("Docs%/Pages/1.fpage", resolvePart("Docs%/Document.fdoc", "Pages/1.fpage"))
        assertEquals("Docs%20/Pages/1.fpage", resolvePart("Docs%20/Document.fdoc", "Pages/1.fpage"))
        assertEquals("Docs%/Fonts/name%.ttf", resolvePart("Docs%/Pages/1.fpage", "../Fonts/name%25.ttf"))
        val bytes = XpsFixtures.storedZip(listOf(
            "Package%25/Sequence.fdseq" to """<FixedDocumentSequence><DocumentReference Source="../Docs%25/Document.fdoc"/></FixedDocumentSequence>""",
            "Docs%25/Document.fdoc" to """<FixedDocument><PageContent Source="Pages/1.fpage"/></FixedDocument>""",
            "Docs%25/Pages/1.fpage" to """<FixedPage Width="96" Height="192"/>""",
        ).map { it.first to it.second.encodeToByteArray() })
        val document = XpsDocument.open(bytes)
        assertEquals(1, document.pageCount)
        assertEquals(72.0, document.pages.single().displayWidth)
        assertEquals(144.0, document.pages.single().displayHeight)
    }

}
