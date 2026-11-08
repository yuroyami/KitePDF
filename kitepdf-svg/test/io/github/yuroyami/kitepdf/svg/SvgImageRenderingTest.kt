package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** `image-rendering: pixelated` draws an enlarged image with nearest pixels (CSS Images 3; #936). */
class SvgImageRenderingTest {

    private val checker = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFUlEQVR4nAXBAQEAAACAEP9PF1CpMCnkBftM2nerAAAAAElFTkSuQmCC"

    private fun interpolates(image: String, group: String = ""): List<Boolean> {
        val svg = assertNotNull(
            SvgImage.parse(
                """<svg xmlns="http://www.w3.org/2000/svg" width="400" height="400"><g $group>
                     <image href="$checker" width="400" height="400" $image/></g></svg>""".encodeToByteArray(),
            ),
        )
        val canvas = RecordingCanvas()
        svg.render(canvas, KiteMatrix.IDENTITY)
        return canvas.calls.filterIsInstance<RecordingCanvas.Call.Image>().map { it.image.interpolate }
    }

    @Test
    fun pixelated_and_its_kin_turn_smoothing_off_and_auto_keeps_it() {
        assertEquals(listOf(true), interpolates(""))
        assertEquals(listOf(false), interpolates("""image-rendering="pixelated""""))
        assertEquals(listOf(false), interpolates("""style="image-rendering: crisp-edges""""))
        assertEquals(listOf(false), interpolates("""image-rendering="optimizeSpeed""""))
        assertEquals(listOf(false), interpolates("", group = """image-rendering="pixelated""""), "inherited from the group")
        assertEquals(listOf(true), interpolates("""image-rendering="auto"""", group = """image-rendering="pixelated""""))
    }
}
