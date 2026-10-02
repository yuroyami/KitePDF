package io.github.yuroyami.kitepdf.compose

import io.github.yuroyami.kitepdf.compose.EdtImageComposeScene as ImageComposeScene
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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

    /** Runs a suspending host call while its scene and the EDT remain able to make progress. */
    fun <T> runOnUi(block: suspend CoroutineScope.() -> T): T {
        check(!java.awt.EventQueue.isDispatchThread()) { "the scene driver belongs to the test control thread" }
        val work = onTestUiThread { CoroutineScope(TestUiDispatcher).async(block = block) }
        try {
            if (!work.isCompleted) pumpUntilState { work.isCompleted }
            return runBlocking { work.await() }
        } finally {
            work.cancel()
        }
    }

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
        return onTestUiThread {
            val img = scene.render(timeNanos)
            effects?.drain()
            img
        }
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

    @Volatile
    private var released = false

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (released) {
            TestUiDispatcher.dispatch(context, block)
        } else {
            tasks.add(block)
            // Release may have drained the queue between the first check and this insertion.
            if (released && tasks.remove(block)) TestUiDispatcher.dispatch(context, block)
        }
    }

    /**
     * Hands the queued work, and any work dispatched later, to the EDT. After a test closes its
     * scene nothing drains the queue, and a raster stranded in it would hold the process-wide
     * render lock, which hangs every later raster in the test JVM.
     */
    fun release() {
        released = true
        while (true) TestUiDispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext, tasks.poll() ?: return)
    }

    /**
     * Runs the queued work, and the work it queues in turn, until none is left. A loop that
     * queues itself forever stops at a cap and goes on after the next frame, as in an app.
     */
    fun drain() {
        onTestUiThread {
            repeat(MAX_TASKS_PER_FRAME) { (tasks.poll() ?: return@onTestUiThread).run() }
        }
    }

    private companion object {
        const val MAX_TASKS_PER_FRAME = 100_000
    }
}

/* ── effect orders ───────────────────────────────────────────────────────────── */

/**
 * Runs [body] twice: with the scene's default effect order, then with an app's order through
 * [QueuedEffects]. A failure names the order it happened in. Landing and zoom tests use it, so
 * they cannot pass in an order no app uses (#328).
 */
internal inline fun forBothEffectOrders(body: (queued: Boolean) -> Unit) {
    for (queued in listOf(false, true)) {
        try {
            body(queued)
        } catch (failure: AssertionError) {
            val order = if (queued) "app effect order" else "default effect order"
            throw AssertionError("$order: ${failure.message}", failure)
        } finally {
            releaseLeftovers()
        }
    }
}

/** Queued scene contexts and latched documents that the current test made. */
private val leftovers = ThreadLocal.withInitial { ArrayList<() -> Unit>() }

/**
 * Lets whatever a finished or failed test left waiting run to its end: the queue of a closed
 * scene, and a chapter layout still held on a latch. Either one can hold a pool thread or the
 * render lock, and a later test would wait on it forever.
 */
internal fun releaseLeftovers() {
    val pending = leftovers.get()
    pending.forEach { it() }
    pending.clear()
}

/** Runs [release] when the current test ends, so a test double that holds threads lets them go. */
internal fun releaseAtEnd(release: () -> Unit) {
    leftovers.get() += release
}

/** A scene of [content] and its driver, with effects in the default order or, with [queued], an app's. */
internal fun drivenScene(
    width: Int,
    height: Int,
    queued: Boolean,
    content: @androidx.compose.runtime.Composable () -> Unit,
): Pair<ImageComposeScene, SceneTestDriver> {
    val density = androidx.compose.ui.unit.Density(1f)
    if (!queued) {
        val scene = ImageComposeScene(width, height, density, content = content)
        return scene to SceneTestDriver(scene)
    }
    val effects = QueuedEffects()
    leftovers.get() += effects::release
    val scene = ImageComposeScene(width, height, density, coroutineContext = effects, content = content)
    return scene to SceneTestDriver(scene, effects)
}

/**
 * Delegates everything, but holds the layout of chapter [held] until [release]: a slow chapter,
 * with the timing in the test's hands. Opening at a bookmark in that chapter waits the same way.
 */
internal class LatchedDocument(
    private val inner: io.github.yuroyami.kitepdf.core.KiteDocument,
    private val held: Int,
) : io.github.yuroyami.kitepdf.core.KiteDocument by inner {
    private val latch = java.util.concurrent.CountDownLatch(1)

    init {
        leftovers.get() += ::release
    }

    fun release() = latch.countDown()

    override fun prepareChapter(chapter: Int) {
        if (chapter == held) latch.await()
        inner.prepareChapter(chapter)
    }

    override fun pageCountIn(chapter: Int): Int {
        if (chapter == held) latch.await()
        return inner.pageCountIn(chapter)
    }

    override fun locate(bookmark: io.github.yuroyami.kitepdf.core.KiteBookmark): io.github.yuroyami.kitepdf.core.KiteLocation {
        if (bookmark is io.github.yuroyami.kitepdf.core.KiteBookmark.Flow && bookmark.chapter == held) latch.await()
        return inner.locate(bookmark)
    }
}

/**
 * Runs [body] and returns the writes to Compose state that viewer code made meanwhile on another
 * thread than the EDT, each as the viewer frame that made it. A frame of a test or of this file
 * is not viewer code (#443, #429).
 */
internal fun viewerWritesOffThread(body: () -> Unit): List<String> {
    val here = onTestUiThread { Thread.currentThread() }
    val found = java.util.Collections.synchronizedList(ArrayList<String>())
    val observer = androidx.compose.runtime.snapshots.Snapshot.registerGlobalWriteObserver {
        val thread = Thread.currentThread()
        if (thread !== here) {
            Throwable().stackTrace.firstOrNull { frame ->
                val owner = frame.className.substringBefore('$')
                owner.startsWith(VIEWER_PACKAGE) && !owner.endsWith("Test") && !owner.endsWith("TestKt") && !owner.endsWith(".SceneTestDriverKt")
            }?.let { found += "$it on ${thread.name}" }
        }
    }
    try {
        body()
    } finally {
        observer.dispose()
    }
    return found.toList()
}

private const val VIEWER_PACKAGE = "io.github.yuroyami.kitepdf.compose."

/**
 * Runs [body] and fails when anything reached the uncaught-exception handler meanwhile. That
 * handler is where a failure in an effect, a gesture or a pool thread goes, and on Android it
 * ends the host app.
 */
internal inline fun <T> withoutEscapes(body: () -> T): T {
    val escaped = java.util.Collections.synchronizedList(ArrayList<Throwable>())
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { _, failure -> escaped += failure }
    val result = try {
        body()
    } finally {
        Thread.setDefaultUncaughtExceptionHandler(previous)
    }
    if (escaped.isNotEmpty()) throw AssertionError("a failure escaped to the uncaught-exception handler: $escaped", escaped.first())
    return result
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
