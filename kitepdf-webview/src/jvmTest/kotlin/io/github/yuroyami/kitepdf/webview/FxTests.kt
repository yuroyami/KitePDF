package io.github.yuroyami.kitepdf.webview

import com.sun.net.httpserver.HttpServer
import org.junit.Assume
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** What the desktop web view tests share (#41). */
internal object FxTests {

    /**
     * Skips a test that needs JavaFX when there is no display to start it on, as on a Linux host
     * without X. CI runs these tests under xvfb-run, so there a missing display fails instead of
     * skipping, and a JavaFX that does not start fails everywhere.
     */
    fun assumeJavaFx() {
        val os = System.getProperty("os.name").lowercase()
        val display = System.getenv("DISPLAY") != null || "mac" in os || "win" in os
        if (!display) {
            check(System.getenv("CI") == null) { "CI runs the web view tests under a display: run them with xvfb-run" }
            Assume.assumeTrue("JavaFX needs a display: run these tests with xvfb-run (os $os)", false)
        }
        check(JavaFxWebEngine.isAvailable) { "JavaFX did not start" }
    }

    /** Waits up to [timeoutMs] for [condition], and says whether it came true. */
    fun waitFor(timeoutMs: Long = 20_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    /** A host that keeps the last frame and every link. */
    class Recorder : KiteDesktopWebHost {
        class Frame(val pixels: IntArray, val width: Int, val height: Int) {
            fun at(x: Int, y: Int): Int = pixels[y * width + x]
        }

        @Volatile var frame: Frame? = null
        val links: MutableList<String> = CopyOnWriteArrayList()

        override fun frame(pixels: IntArray, width: Int, height: Int) {
            frame = Frame(pixels.copyOf(width * height), width, height)
        }

        override fun link(url: String) {
            links += url
        }
    }

    /** True when [argb] is near the colour of [rgb], `0xRRGGBB`. */
    fun near(argb: Int, rgb: Int, tolerance: Int = 24): Boolean =
        intArrayOf(16, 8, 0).all { kotlin.math.abs(((argb ushr it) and 0xFF) - ((rgb ushr it) and 0xFF)) <= tolerance } &&
            (argb ushr 24) > 200

    /** A server on the loopback address that counts every request, standing in for the network. */
    class Outside : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val hits = AtomicInteger()
        val url: String get() = "http://127.0.0.1:${server.address.port}/outside.png"

        init {
            server.createContext("/") { exchange ->
                hits.incrementAndGet()
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }
}
