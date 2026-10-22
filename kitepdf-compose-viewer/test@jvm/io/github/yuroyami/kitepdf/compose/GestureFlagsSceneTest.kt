package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import io.github.yuroyami.kitepdf.compose.EdtImageComposeScene as ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.test.Test
import kotlin.test.assertEquals

/** A gesture flag that the host changes at run time applies to the next gesture (#404). */
class GestureFlagsSceneTest {

    /** One page of 1,000 x 200 pt. */
    private fun widePdf(): ByteArray {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 1000 200] >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> >>")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    /** Two fingers 40 px apart move 30 px to the left together: a pan with no zoom. */
    @OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
    private fun twoFingerPan(scene: ImageComposeScene, driver: SceneTestDriver) {
        fun fingers(dx: Float, pressed: Boolean) = listOf(
            ComposeScenePointer(PointerId(1), Offset(80f + dx, 100f), pressed, PointerType.Touch),
            ComposeScenePointer(PointerId(2), Offset(120f + dx, 100f), pressed, PointerType.Touch),
        )
        scene.sendPointerEvent(PointerEventType.Press, fingers(0f, true))
        driver.pumpFrames(1)
        for (step in 1..3) {
            scene.sendPointerEvent(PointerEventType.Move, fingers(-10f * step, true))
            driver.pumpFrames(1)
        }
        scene.sendPointerEvent(PointerEventType.Release, fingers(-30f, false))
        driver.pumpFrames(2)
    }

    @Test
    fun turning_pan_off_stops_the_two_finger_pan_as_well() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(widePdf())
            lateinit var state: KiteDocViewState
            var panAllowed by mutableStateOf(true)
            val (scene, driver) = drivenScene(200, 200, queued) {
                state = rememberKiteDocViewState(doc)
                KiteDocView(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    layout = KiteDocLayout.SinglePage(0),
                    zoomSpec = KiteZoomSpec(pinchEnabled = true, panEnabled = panAllowed, doubleTapEnabled = false),
                )
            }
            scene.use {
                driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                onTestUiThread { state.setZoom(2f) }
                driver.pumpFrames(4)
                val start = state.panOffset.x
                twoFingerPan(scene, driver)
                assertEquals(start - 30f, state.panOffset.x, 0.5f, "a two-finger move pans while pan is on")
                panAllowed = false
                driver.pumpFrames(4)
                val before = state.panOffset.x
                twoFingerPan(scene, driver)
                assertEquals(before, state.panOffset.x, 0.5f, "a two-finger move still pans after pan was turned off")
                panAllowed = true
                driver.pumpFrames(4)
                twoFingerPan(scene, driver)
                assertEquals(before - 30f, state.panOffset.x, 0.5f, "a two-finger move pans again once pan is back on")
            }
        }
    }
}
