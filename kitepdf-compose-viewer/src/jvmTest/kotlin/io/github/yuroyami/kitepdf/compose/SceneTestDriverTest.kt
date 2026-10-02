package io.github.yuroyami.kitepdf.compose

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import io.github.yuroyami.kitepdf.compose.EdtImageComposeScene as ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The scene driver itself: a wait that never succeeds fails, and queued effects run in an app's order (#328). */
class SceneTestDriverTest {

    @Test
    fun a_wait_that_never_succeeds_fails_within_its_budget() {
        ImageComposeScene(width = 20, height = 20, density = Density(1f)) {}.use { scene ->
            val driver = SceneTestDriver(scene)
            val started = System.currentTimeMillis()
            assertFailsWith<AssertionError> { driver.pumpUntilState(timeoutMs = 300) { false } }
            assertFailsWith<AssertionError> { driver.pumpUntil(maxFrames = 5) { false } }
            assertTrue(System.currentTimeMillis() - started < 10_000, "the waits ended at their budgets")
            driver.pumpUntilState { true }
            driver.pumpFrames(3)
        }
    }

    /**
     * A state change schedules a recomposition for the next frame, and an effect waits on
     * that frame. The default scene context resumes the effect inside the frame, before the
     * recomposition. An app, and [QueuedEffects], resume it after.
     */
    @Test
    fun queued_effects_see_the_frame_recomposition_as_an_app_does() {
        assertEquals(0, valueSeenByAnEffect(queued = false), "the default scene order")
        assertEquals(5, valueSeenByAnEffect(queued = true), "the app order")
    }

    private fun valueSeenByAnEffect(queued: Boolean): Int {
        var input by mutableIntStateOf(0)
        var composed = -1
        var seen = -1
        val effects = QueuedEffects().takeIf { queued }
        val scene = if (effects != null) {
            ImageComposeScene(width = 20, height = 20, density = Density(1f), coroutineContext = effects) {
                composed = input
                LaunchedEffect(Unit) {
                    while (true) {
                        withFrameNanos { }
                        seen = composed
                    }
                }
            }
        } else {
            ImageComposeScene(width = 20, height = 20, density = Density(1f)) {
                composed = input
                LaunchedEffect(Unit) {
                    while (true) {
                        withFrameNanos { }
                        seen = composed
                    }
                }
            }
        }
        scene.use {
            val driver = SceneTestDriver(it, effects)
            driver.pumpFrames(2)
            input = 5
            driver.pumpFrames(0)
        }
        effects?.release()
        return seen
    }
}
