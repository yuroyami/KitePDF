package io.github.yuroyami.kitepdf.core.render

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An animated image keeps its first frame until a player moves it, and plays its loops (#939). */
@OptIn(ExperimentalEncodingApi::class)
class KiteImageAnimationTest {

    /** Two 8 by 8 frames of 100 ms, red then green, written by Pillow. */
    private val gifForever = Base64.decode("R0lGODlhCAAIAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQECgAAACwAAAAACAAIAAAIDwABCBxIsKDBgwgTKkwYEAAh+QQFCgABACwAAAAACAAIAIEAyAAAAAAAAAAAAAAIDwABCBxIsKDBgwgTKkwYEAA7")
    private val gifThree = Base64.decode("R0lGODlhCAAIAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEDAAAh+QQECgAAACwAAAAACAAIAAAIDwABCBxIsKDBgwgTKkwYEAAh+QQFCgABACwAAAAACAAIAIEAyAAAAAAAAAAAAAAIDwABCBxIsKDBgwgTKkwYEAA7")
    private val webpForever = Base64.decode("UklGRoQAAABXRUJQVlA4WAoAAAACAAAABwAABwAAQU5JTQYAAAAAAAAAAABBTk1GKAAAAAAAAAAAAAcAAAcAAGQAAAJWUDhMDwAAAC8HwAEABxD9j/4HIqL/AQBBTk1GKAAAAAAAAAAAAAcAAAcAAGQAAABWUDhMDwAAAC8HwAEAB1DkiP4HIqL/AQA=")

    private fun centre(image: KiteImageData): Triple<Int, Int, Int> {
        val rgba = image.toRgbaBytes()!!
        val i = (image.height / 2 * image.width + image.width / 2) * 4
        return Triple(rgba[i].toInt() and 0xFF, rgba[i + 1].toInt() and 0xFF, rgba[i + 2].toInt() and 0xFF)
    }

    @Test
    fun an_animated_gif_is_its_first_frame_until_a_player_moves_it() {
        val image = assertNotNull(KiteImageData.fromEncodedImage(gifForever))
        val animation = assertNotNull(image.animation)
        assertEquals(2, animation.frameCount)
        assertEquals(0L, animation.loopCount)
        assertEquals(255, centre(image).first, "the image itself is the red first frame")
        assertNull(animation.currentFrame(), "nothing to draw in its place before a player moves it")
        assertTrue(animation.prepare())
        assertEquals(listOf(100, 100), animation.frameDelays!!.toList())
        animation.frameIndex = 1
        val second = assertNotNull(animation.currentFrame())
        assertEquals(0, centre(second).first)
        assertTrue(centre(second).second > 150, "the second frame is green")
        animation.release()
        assertNull(animation.currentFrame())
        assertEquals(0, animation.frameIndex)
    }

    @Test
    fun frames_follow_their_delays_and_loop() {
        val animation = assertNotNull(KiteImageData.fromEncodedImage(gifForever)?.animation)
        assertEquals(0, animation.frameAt(0), "not prepared: the first frame")
        assertTrue(animation.prepare())
        assertEquals(0, animation.frameAt(0))
        assertEquals(0, animation.frameAt(99))
        assertEquals(1, animation.frameAt(100))
        assertEquals(0, animation.frameAt(200), "a GIF that loops for ever starts again")
        assertEquals(1, animation.frameAt(10_150))
        assertFalse(animation.isFinished(1_000_000))
    }

    @Test
    fun a_counted_loop_stops_on_its_last_frame() {
        val animation = assertNotNull(KiteImageData.fromEncodedImage(gifThree)?.animation)
        assertTrue(animation.loopCount > 0, "the file counts its loops: ${animation.loopCount}")
        assertTrue(animation.prepare())
        val end = 200L * animation.loopCount
        assertEquals(0, animation.frameAt(end - 200))
        assertFalse(animation.isFinished(end - 1))
        assertTrue(animation.isFinished(end))
        assertEquals(1, animation.frameAt(end), "the last frame stays")
        assertEquals(1, animation.frameAt(end * 10))
    }

    @Test
    fun an_animated_webp_has_its_frames() {
        val image = assertNotNull(KiteImageData.fromEncodedImage(webpForever))
        val animation = assertNotNull(image.animation)
        assertTrue(animation.prepare())
        assertEquals(1, animation.frameAt(150))
    }

    @Test
    fun a_still_image_has_no_animation_and_a_budget_refuses_one() {
        val still = assertNotNull(KiteImageData.fromEncodedImage(Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAAFElEQVR4nGNkYPjPAANMDEgANwcAMdMBB1sLEtoAAAAASUVORK5CYII=")))
        assertNull(still.animation)
        val animation = assertNotNull(KiteImageData.fromEncodedImage(gifForever)?.animation)
        assertFalse(animation.prepare(maxBytes = 100), "two 8 by 8 frames take 512 bytes")
        assertFalse(animation.isPrepared)
        assertTrue(animation.prepare())
    }
}
