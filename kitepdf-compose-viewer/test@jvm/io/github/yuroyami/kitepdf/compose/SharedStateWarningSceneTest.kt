package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.KiteWarningSink
import io.github.yuroyami.kitepdf.core.KiteWarnings
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertTrue

/** A state that a second view shows logs a warning, since the two views overwrite each other's parts of it (#656). */
class SharedStateWarningSceneTest {

    private class Blank : KitePage {
        override val displayWidth: Double = 100.0
        override val displayHeight: Double = 100.0
        override fun displayToDeviceBase(): KiteMatrix = KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, displayHeight)
        override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) = Unit
    }

    private class Pages(count: Int) : KiteDocument {
        override val pageCount: Int = count
        override val pages: List<KitePage> = List(count) { Blank() }
    }

    @Test
    fun a_second_view_of_one_state_warns() {
        val warnings = Collections.synchronizedList(ArrayList<String>())
        val previous = KiteWarnings.sink
        KiteWarnings.sink = KiteWarningSink { warnings += it }
        try {
            val state = KiteDocViewState(Pages(3))
            var second by mutableStateOf(false)
            val (scene, driver) = drivenScene(200, 400, queued = true) {
                Column {
                    KiteDocView(state = state, modifier = Modifier.fillMaxWidth().height(200.dp))
                    if (second) KiteDocView(state = state, modifier = Modifier.fillMaxWidth().height(200.dp))
                }
            }
            scene.use {
                driver.pumpUntilState { state.adapter != null }
                driver.pumpFrames(5)
                assertTrue(warnings.none { "shown by" in it }, "one view warned: $warnings")
                onTestUiThread { second = true }
                driver.pumpUntilState { warnings.any { "shown by 2 views" in it } }
            }
        } finally {
            KiteWarnings.sink = previous
        }
    }
}
