package io.github.yuroyami.kitepdf.xps

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.nativerenderer.AwtCanvas
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XpsRasterTest {
    private val folder = File("build/xps-difftest").apply { mkdirs() }

    private fun fixture(): ByteArray {
        val picture = BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB)
        picture.setRGB(0, 0, Color.RED.rgb); picture.setRGB(1, 0, Color.BLUE.rgb)
        val encoded = ByteArrayOutputStream().also { ImageIO.write(picture, "png", it) }.toByteArray()
        val guid = "00112233-4455-6677-8899-aabbccddeeff.odttf"
        val font = deobfuscateFont(XpsFixtures.squareTtf(), guid)!!
        return XpsFixtures.packageBytes("""
            <FixedPage.Resources><ResourceDictionary>
              <SolidColorBrush x:Key="green" Color="#008800"/>
            </ResourceDictionary></FixedPage.Resources>
            <Path Fill="{StaticResource green}" Data="M8,8 L56,8 56,40 8,40 Z"/>
            <Canvas RenderTransform="1,0,0,1,65,8" Clip="M0,0 L48,0 48,32 0,32 Z">
              <Path Stroke="#000000" StrokeThickness="2" Data="M0,32 C12,-16 36,-16 48,32"/>
              <Path Fill="#800000FF" Data="M0,16 L48,16 48,32 0,32 Z"/>
            </Canvas>
            <Path Data="M124,8 L180,8 180,40 124,40 Z"><Path.Fill>
              <ImageBrush ImageSource="../../Resources/test.png" Viewbox="0,0,2,1" Viewport="124,8,56,32"
                ViewboxUnits="Absolute" ViewportUnits="Absolute"/>
            </Path.Fill></Path>
            <Glyphs Fill="#000000" FontUri="../../Resources/$guid" FontRenderingEmSize="100"
                OriginX="8" OriginY="80" UnicodeString="AA" Indices="1,25;1,25"/>
            <Path Fill="#FF8800" Stroke="#000000" StrokeThickness="1"><Path.Data>
              <PathGeometry Transform="2,0,0,1,70,55" Figures="M0,0 L20,0 20,25 0,25Z"/>
            </Path.Data></Path>
            <Path Data="M124,55 L180,55 180,80 124,80 Z"><Path.Fill>
              <LinearGradientBrush StartPoint="124,55" EndPoint="180,55">
                <LinearGradientBrush.GradientStops><GradientStop Offset="0" Color="#000000"/>
                  <GradientStop Offset="1" Color="#FFFFFF"/></LinearGradientBrush.GradientStops>
              </LinearGradientBrush>
            </Path.Fill></Path>
        """.trimIndent(), listOf("Resources/test.png" to encoded, "Resources/$guid" to font))
    }

    private fun raster(bytes: ByteArray): BufferedImage {
        val page = XpsDocument.open(bytes).pages.single()
        val image = BufferedImage((page.displayWidth * 2).toInt(), (page.displayHeight * 2).toInt(), BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.color = Color.WHITE; g.fillRect(0, 0, image.width, image.height)
            page.renderTo(AwtCanvas(g), KiteMatrix.scaling(2.0, 2.0))
        } finally { g.dispose() }
        return image
    }

    @Test fun paintsEmbeddedGlyphsImagesGeometryAndGradients() {
        val image = raster(fixture())
        ImageIO.write(image, "png", File(folder, "kite.png"))
        assertEquals(Color(0, 136, 0).rgb, image.getRGB(24, 24))
        assertEquals(Color.RED.rgb, image.getRGB(195, 24))
        assertEquals(Color.BLUE.rgb, image.getRGB(255, 24))
        assertEquals(Color.BLACK.rgb, image.getRGB(18, 112))
        assertEquals(Color(255, 136, 0).rgb, image.getRGB(120, 90))
        val left = image.getRGB(194, 100) and 255
        val right = image.getRGB(260, 100) and 255
        assertTrue(left < 60 && right > 210, "gradient must vary across the rectangle: $left -> $right")
    }

    @Test fun generatedPageAgreesWithMuPdf() {
        val oracle = sequenceOf(System.getenv("MUTOOL"), "/opt/homebrew/bin/mutool", "/usr/local/bin/mutool", "/usr/bin/mutool")
            .filterNotNull().map(::File).firstOrNull { it.canExecute() }
        assumeTrue("mutool is unavailable; XPS oracle comparison skipped", oracle != null)
        val bytes = fixture()
        val source = File(folder, "fixture.xps").apply { writeBytes(bytes) }
        val output = File(folder, "mupdf.png")
        val log = File(folder, "mupdf.log")
        val process = ProcessBuilder(oracle!!.absolutePath, "draw", "-q", "-r", "144", "-o", output.absolutePath,
            source.absolutePath, "1").redirectErrorStream(true).redirectOutput(log).start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); error("mutool timed out") }
        assertEquals(0, process.exitValue(), log.readText())
        val expected = ImageIO.read(output)
        val actual = raster(bytes)
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        var error = 0L
        var ink = 0
        for (y in 0 until actual.height) for (x in 0 until actual.width) {
            val a = actual.getRGB(x, y); val b = expected.getRGB(x, y)
            if (a and 0xFFFFFF != 0xFFFFFF) ink++
            for (shift in listOf(0, 8, 16)) error += abs((a ushr shift and 255) - (b ushr shift and 255))
        }
        val mean = error.toDouble() / (actual.width * actual.height * 3 * 255)
        File(folder, "report.md").writeText("XPS synthetic AWT vs MuPDF at 144 dpi: mean absolute RGB error $mean; painted pixels $ink.\n")
        assertTrue(ink > 8000, "blank output cannot pass")
        assertTrue(mean < 0.025, "mean RGB error $mean; see ${folder.absolutePath}")
    }
}
