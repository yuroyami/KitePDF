package io.github.yuroyami.kitepdf.webview

import io.github.yuroyami.kitepdf.core.kiteWarn
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The desktop's default [KiteDesktopWebEngine]: JavaFX's web view, in process (#41). It draws
 * offscreen in a window of JavaFX's embedding, the way JavaFX's own Swing and SWT panels do,
 * so [EpubWebView] can draw its frames on the page.
 *
 * This library compiles against JavaFX and does not bring it: an app adds the `javafx-base`,
 * `javafx-graphics`, `javafx-controls`, `javafx-media` and `javafx-web` artifacts of its
 * platform, or a JDK that carries JavaFX. Without JavaFX, or without a display for it to start
 * on, [open] returns null and the page shows the library's own rendering.
 */
public object JavaFxWebEngine : KiteDesktopWebEngine {

    /** True once JavaFX runs. Starts it on first use; false when it is missing or cannot start. */
    public val isAvailable: Boolean by lazy { start() }

    override fun open(url: String, host: KiteDesktopWebHost): KiteDesktopWebSurface? {
        if (!isAvailable) return null
        return runCatching { FxWebSurface.open(url, host) }
            .onFailure { failure -> kiteWarn { "webview: JavaFX could not open a web view: ${failure.message}" } }
            .getOrNull()
    }

    private fun start(): Boolean {
        val present = runCatching { Class.forName("javafx.scene.web.WebView", false, javaClass.classLoader) }.isSuccess
        if (!present) {
            kiteWarn { "webview: JavaFX is not on the class path, so scripted content shows without its scripts" }
            return false
        }
        return runCatching { FxToolkit.start() }
            .onFailure { failure -> kiteWarn { "webview: JavaFX cannot start, so scripted content shows without its scripts: ${failure.message}" } }
            .getOrDefault(false)
    }
}

/** JavaFX's toolkit. Only touched once [JavaFxWebEngine.isAvailable] found JavaFX on the class path. */
internal object FxToolkit {
    fun start(): Boolean {
        val started = CountDownLatch(1)
        try {
            javafx.application.Platform.startup { started.countDown() }
        } catch (_: IllegalStateException) {
            // Running already, started by the app or by another library.
            started.countDown()
        }
        // The app's windows are not JavaFX's, so closing the last JavaFX one must not stop it.
        javafx.application.Platform.setImplicitExit(false)
        return started.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    /** Runs [block] on the JavaFX thread and waits for it. */
    fun <T> run(block: () -> T): T {
        if (javafx.application.Platform.isFxApplicationThread()) return block()
        var result: Result<T>? = null
        val done = CountDownLatch(1)
        javafx.application.Platform.runLater {
            result = runCatching(block)
            done.countDown()
        }
        check(done.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "the JavaFX thread did not answer" }
        return result!!.getOrThrow()
    }

    fun later(block: () -> Unit) = javafx.application.Platform.runLater(block)

    private const val START_TIMEOUT_SECONDS = 20L
}
