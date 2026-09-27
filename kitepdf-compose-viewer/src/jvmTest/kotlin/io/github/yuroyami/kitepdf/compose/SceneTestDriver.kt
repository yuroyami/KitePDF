package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.skia.Image

/**
 * Drives an [ImageComposeScene] until a condition holds, and fails the test when it never does.
 *
 * [KiteDocView] fades a freshly rasterized page in via `Crossfade`, so a page is not
 * fully opaque within the handful of frames a hard-cut raster used to need. This
 * driver advances the virtual frame clock (so the fade animates) and polls after
 * each frame until the condition holds. The frame-time cursor is monotonic across
 * calls, so a test can pump, change state, then pump again. A small real sleep per
 * frame keeps it robust to any post-frame effects.
 *
 * A wait that runs out throws an [AssertionError]. A test that only wants time to
 * pass calls [pumpFrames] instead (#328).
 *
 * Pass [effects] when the scene was made with a [QueuedEffects] context: the driver
 * then runs the queued effect work after each frame, the order an app's main thread
 * uses. The default scene context runs it inside the frame instead.
 */
internal class SceneTestDriver(
    private val scene: ImageComposeScene,
    private val effects: QueuedEffects? = null,
) {

    private var timeNanos = 0L

    /**
     * Render frames until [check] passes against the latest frame. Returns that frame.
     * Throws when [maxFrames] frames or [timeoutMs] of wall-clock time pass first. The
     * budget is wall-clock time by default, as for [pumpUntilState]: a page raster
     * comes from a background thread.
     */
    fun pumpUntil(
        maxFrames: Int = Int.MAX_VALUE,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        check: (PixelMap) -> Boolean,
    ): Image {
        var img = frame()
        val deadline = System.currentTimeMillis() + timeoutMs
        var frame = 0
        while (true) {
            if (check(img.toComposeImageBitmap().toPixelMap())) return img
            if (frame >= maxFrames || System.currentTimeMillis() >= deadline) {
                throw AssertionError("the frame condition did not hold after $frame frames and $timeoutMs ms at most")
            }
            Thread.sleep(4)
            timeNanos += FRAME_NANOS
            img = frame()
            frame++
        }
    }

    /**
     * Render frames until [check] holds. For conditions that live in state
     * rather than in pixels, such as a chapter finishing its layout on a
     * background thread. Throws when the budget runs out first.
     *
     * The default budget is wall-clock time, not a frame count. Such a condition
     * waits on real threads, and a loaded machine slows those, not the virtual
     * clock: 900 frames came to under four seconds, and a CI runner under load
     * needed more than that to lay out a twelve-chapter book (#294).
     */
    fun pumpUntilState(
        maxFrames: Int = Int.MAX_VALUE,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        check: () -> Boolean,
    ) {
        frame()
        val deadline = System.currentTimeMillis() + timeoutMs
        var frame = 0
        while (true) {
            if (check()) return
            if (frame >= maxFrames || System.currentTimeMillis() >= deadline) {
                throw AssertionError("the state condition did not hold after $frame frames and $timeoutMs ms at most")
            }
            Thread.sleep(4)
            timeNanos += FRAME_NANOS
            frame()
            frame++
        }
    }

    /**
     * Renders a frame at the current time and [count] more, one frame time apart, whatever
     * happens in them. Returns the last one.
     */
    fun pumpFrames(count: Int): Image {
        var img = frame()
        repeat(count) {
            Thread.sleep(4)
            timeNanos += FRAME_NANOS
            img = frame()
        }
        return img
    }

    private fun frame(): Image {
        val img = scene.render(timeNanos)
        effects?.drain()
        return img
    }

    private companion object {
        const val FRAME_NANOS = 16_000_000L

        /** Long enough for a loaded CI runner, short enough that a wait that never ends fails soon. */
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}

/**
 * A scene context that holds effect work until the frame is over, as an app's main
 * thread does. `ImageComposeScene` runs effects on `Dispatchers.Unconfined` by default,
 * so a continuation after a frame await runs inside the frame, before recomposition,
 * and a test can pass in an order no app uses (#328).
 *
 * Make the scene with this as its `coroutineContext` and hand it to [SceneTestDriver].
 */
internal class QueuedEffects : CoroutineDispatcher() {

    private val tasks = ConcurrentLinkedQueue<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.add(block)
    }

    /**
     * Runs the queued work, and the work it queues in turn, until none is left. A loop that
     * queues itself forever stops at a cap and goes on after the next frame, as in an app.
     */
    fun drain() {
        repeat(MAX_TASKS_PER_FRAME) { (tasks.poll() ?: return).run() }
    }

    private companion object {
        const val MAX_TASKS_PER_FRAME = 100_000
    }
}

/* ── shared fixture ──────────────────────────────────────────────────────────── */

internal fun multiSpineEpub(bodies: List<String>): ByteArray {
    val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
    val items = bodies.indices.joinToString("") {
        """<item id="c${it + 1}" href="chapter${it + 1}.xhtml" media-type="application/xhtml+xml"/>"""
    }
    val refs = bodies.indices.joinToString("") { """<itemref idref="c${it + 1}"/>""" }
    val opf = """<?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">scene</dc:identifier></metadata>
          <manifest>$items</manifest>
          <spine>$refs</spine>
        </package>"""
    val files = bodies.mapIndexed { i, body ->
        "OEBPS/chapter${i + 1}.xhtml" to
            """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body>$body</body></html>""".encodeToByteArray()
    }
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        zip.setMethod(ZipOutputStream.STORED)
        val entries = listOf(
            "mimetype" to "application/epub+zip".encodeToByteArray(),
            "META-INF/container.xml" to container.encodeToByteArray(),
            "OEBPS/content.opf" to opf.encodeToByteArray(),
        ) + files
        for ((name, data) in entries) {
            zip.putNextEntry(
                ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = data.size.toLong()
                    compressedSize = data.size.toLong()
                    crc = CRC32().apply { update(data) }.value
                },
            )
            zip.write(data)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}
