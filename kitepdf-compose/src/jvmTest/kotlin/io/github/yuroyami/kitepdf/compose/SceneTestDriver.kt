package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import org.jetbrains.skia.Image

/**
 * Drives an [ImageComposeScene] until a pixel condition holds.
 *
 * [PdfView] fades a freshly rasterized page in via `Crossfade`, so a page is not
 * fully opaque within the handful of frames a hard-cut raster used to need. This
 * driver advances the virtual frame clock (so the fade animates) and polls after
 * each frame until the page is on screen or a timeout elapses. The frame-time
 * cursor is monotonic across calls, so a test can pump, change state, then pump
 * again. A small real sleep per frame keeps it robust to any post-frame effects.
 */
internal class SceneTestDriver(private val scene: ImageComposeScene) {

    private var timeNanos = 0L

    /**
     * Render frames until [check] passes against the latest frame, or until the
     * frame/time budget is exhausted. Returns the last rendered frame either way
     * — let the caller's assertions report the failure if the condition never
     * held.
     */
    fun pumpUntil(
        maxFrames: Int = 600,
        timeoutMs: Long = 10_000,
        check: (PixelMap) -> Boolean,
    ): Image {
        var img = scene.render(timeNanos)
        val deadline = System.currentTimeMillis() + timeoutMs
        var frame = 0
        while (frame < maxFrames && System.currentTimeMillis() < deadline) {
            if (check(img.toComposeImageBitmap().toPixelMap())) return img
            Thread.sleep(4)
            timeNanos += FRAME_NANOS
            img = scene.render(timeNanos)
            frame++
        }
        return img
    }

    private companion object {
        const val FRAME_NANOS = 16_000_000L
    }
}
