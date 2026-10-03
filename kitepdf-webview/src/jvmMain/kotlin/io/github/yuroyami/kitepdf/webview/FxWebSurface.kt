package io.github.yuroyami.kitepdf.webview

import com.sun.javafx.cursor.CursorFrame
import com.sun.javafx.embed.AbstractEvents
import com.sun.javafx.embed.EmbeddedSceneInterface
import com.sun.javafx.embed.EmbeddedStageInterface
import com.sun.javafx.embed.HostInterface
import com.sun.javafx.stage.EmbeddedWindow
import javafx.concurrent.Worker
import javafx.scene.Scene
import javafx.scene.paint.Color
import javafx.scene.web.WebView
import netscape.javascript.JSObject
import java.nio.IntBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

/**
 * One JavaFX web view, drawn offscreen in a window of JavaFX's embedding (#41). JavaFX draws a
 * frame of the embedded scene when the page changes and calls [HostInterface.repaint]; the
 * surface then reads the frame's pixels on a thread of its own and hands them to the host. Input
 * goes in through the embedded scene, as JavaFX's Swing panel sends it.
 *
 * A link opened in the page reaches the host through `window.kitepdfHost`, which the island
 * script calls. A page that sets its own location instead is taken back to its document after
 * the host has the link.
 */
internal class FxWebSurface private constructor(
    private val url: String,
    private val host: KiteDesktopWebHost,
) : KiteDesktopWebSurface, HostInterface {

    private lateinit var view: WebView
    private lateinit var window: EmbeddedWindow
    @Volatile private var scene: EmbeddedSceneInterface? = null
    @Volatile private var stage: EmbeddedStageInterface? = null
    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var scale = 1f
    @Volatile private var closed = false

    /** The window shows on the first size: one without a size has nothing to draw into. */
    private var shown = false

    /** The scale the embedded scene was last given, on the JavaFX thread. */
    private var appliedScale = 0f

    // The page keeps only a weak reference to an object it is given, so the surface keeps the bridge.
    private val bridge = LinkBridge(host)
    private val reading = AtomicBoolean(false)
    private val reader = Executors.newSingleThreadExecutor { task -> Thread(task, "kitepdf-webview-frames").apply { isDaemon = true } }
    private var buffer: IntBuffer = IntBuffer.allocate(0)

    private fun create() {
        view = WebView()
        view.isContextMenuEnabled = false
        val engine = view.engine
        // A page cannot open a window of its own: the island has no place to put one.
        engine.setCreatePopupHandler { null }
        // The bridge goes in as soon as the document exists, and again once it loaded: a page
        // shows, and takes taps, while its pictures still load.
        engine.documentProperty().addListener { _, _, document -> if (document != null) installBridge() }
        engine.loadWorker.stateProperty().addListener { _, _, state -> if (state == Worker.State.SUCCEEDED) installBridge() }
        engine.locationProperty().addListener { _, _, location ->
            // The page moved itself to another document: the host follows the link, the island
            // goes back to its own. WebKit is in the middle of that navigation here, and loading
            // from inside it crashes WebKit, so the island loads its document after it.
            if (location != null && location.substringBefore('#') != url.substringBefore('#') && location != "about:blank" && !closed) {
                host.link(location)
                FxToolkit.later { if (!closed) engine.load(url) }
            }
        }
        window = EmbeddedWindow(this)
        window.scene = Scene(view).apply { fill = Color.TRANSPARENT }
        engine.load(url)
    }

    override fun resize(width: Int, height: Int, scale: Float) {
        if (width <= 0 || height <= 0 || scale <= 0f) return
        this.width = width
        this.height = height
        this.scale = scale
        FxToolkit.later {
            if (closed) return@later
            if (!shown) {
                // Showing hands the window its stage and scene, which take the size from the fields.
                shown = true
                window.show()
                return@later
            }
            val rescaled = appliedScale != scale
            appliedScale = scale
            stage?.setSize(width, height)
            scene?.setPixelScaleFactors(scale, scale)
            // The scene takes a new scale with a new size only, so a scale alone passes through a
            // size one pixel taller on its way.
            if (rescaled) scene?.setSize(width, height + 1)
            scene?.setSize(width, height)
        }
    }

    override fun mouse(action: KiteMouseAction, x: Float, y: Float, button: Int, buttons: Int, modifiers: Int) {
        val target = scene ?: return
        val type = when (action) {
            KiteMouseAction.PRESS -> AbstractEvents.MOUSEEVENT_PRESSED
            KiteMouseAction.RELEASE -> AbstractEvents.MOUSEEVENT_RELEASED
            KiteMouseAction.MOVE -> if (buttons != 0) AbstractEvents.MOUSEEVENT_DRAGGED else AbstractEvents.MOUSEEVENT_MOVED
            KiteMouseAction.ENTER -> AbstractEvents.MOUSEEVENT_ENTERED
            KiteMouseAction.EXIT -> AbstractEvents.MOUSEEVENT_EXITED
        }
        val ix = x.toInt()
        val iy = y.toInt()
        target.mouseEvent(
            type, fxButton(button),
            buttons and KiteDesktopInput.BUTTON_PRIMARY != 0,
            buttons and KiteDesktopInput.BUTTON_MIDDLE != 0,
            buttons and KiteDesktopInput.BUTTON_SECONDARY != 0,
            false, false, ix, iy, ix, iy,
            modifiers and KiteDesktopInput.SHIFT != 0, modifiers and KiteDesktopInput.CONTROL != 0,
            modifiers and KiteDesktopInput.ALT != 0, modifiers and KiteDesktopInput.META != 0,
            action == KiteMouseAction.PRESS && button == KiteDesktopInput.BUTTON_SECONDARY,
        )
        // The embedded scene makes a click of a press and a release itself, so none is sent.
    }

    override fun wheel(x: Float, y: Float, deltaX: Float, deltaY: Float, modifiers: Int) {
        val target = scene ?: return
        val ix = x.toDouble()
        val iy = y.toDouble()
        // A notch of a wheel scrolls forty pixels, as JavaFX's Swing panel sends it.
        target.scrollEvent(
            AbstractEvents.MOUSEEVENT_VERTICAL_WHEEL, -deltaX.toDouble(), -deltaY.toDouble(), 0.0, 0.0, NOTCH, NOTCH,
            ix, iy, ix, iy,
            modifiers and KiteDesktopInput.SHIFT != 0, modifiers and KiteDesktopInput.CONTROL != 0,
            modifiers and KiteDesktopInput.ALT != 0, modifiers and KiteDesktopInput.META != 0, false,
        )
    }

    override fun key(action: KiteKeyAction, keyCode: Int, char: Char, modifiers: Int) {
        val target = scene ?: return
        val type = when (action) {
            KiteKeyAction.PRESS -> AbstractEvents.KEYEVENT_PRESSED
            KiteKeyAction.RELEASE -> AbstractEvents.KEYEVENT_RELEASED
            KiteKeyAction.TYPE -> AbstractEvents.KEYEVENT_TYPED
        }
        val chars = if (char == 0.toChar()) CharArray(0) else charArrayOf(char)
        target.keyEvent(type, if (action == KiteKeyAction.TYPE) 0 else keyCode, chars, modifiers)
    }

    override fun focus(focused: Boolean) {
        FxToolkit.later {
            stage?.setFocused(focused, if (focused) AbstractEvents.FOCUSEVENT_ACTIVATED else AbstractEvents.FOCUSEVENT_DEACTIVATED)
        }
    }

    /** Gives the page's window the host bridge that the island script calls. */
    private fun installBridge() {
        runCatching { (view.engine.executeScript("window") as JSObject).setMember("kitepdfHost", bridge) }
    }

    override fun close() {
        if (closed) return
        closed = true
        reader.shutdownNow()
        FxToolkit.later {
            runCatching { window.hide() }
        }
    }

    // ── HostInterface: what JavaFX's embedded window asks of its host ──

    override fun setEmbeddedStage(embeddedStage: EmbeddedStageInterface?) {
        stage = embeddedStage
        if (embeddedStage != null && width > 0) {
            embeddedStage.setSize(width, height)
            embeddedStage.setLocation(0, 0)
        }
    }

    override fun setEmbeddedScene(embeddedScene: EmbeddedSceneInterface?) {
        scene = embeddedScene
        if (embeddedScene != null && width > 0) {
            appliedScale = scale
            embeddedScene.setPixelScaleFactors(scale, scale)
            embeddedScene.setSize(width, height)
        }
    }

    override fun repaint() {
        if (closed || !reading.compareAndSet(false, true)) return
        runCatching { reader.execute(::readFrame) }.onFailure { reading.set(false) }
    }

    /** Reads the frame JavaFX drew, and hands it to the host. */
    private fun readFrame() {
        try {
            val target = scene ?: return
            val w = ceil(width * scale).toInt()
            val h = ceil(height * scale).toInt()
            if (w <= 0 || h <= 0) return
            if (buffer.capacity() != w * h) buffer = IntBuffer.allocate(w * h)
            buffer.clear()
            // The embedded scene takes the size in CSS pixels and scales it by its own factor.
            if (target.getPixels(buffer, width, height)) host.frame(buffer.array(), w, h)
        } finally {
            reading.set(false)
        }
    }

    override fun requestFocus(): Boolean = true
    override fun traverseFocusOut(forward: Boolean): Boolean = false
    override fun setPreferredSize(width: Int, height: Int) {}
    override fun setEnabled(enabled: Boolean) {}
    override fun setCursor(cursorFrame: CursorFrame?) {}
    override fun grabFocus(): Boolean = false
    override fun ungrabFocus() {}

    companion object {
        private const val NOTCH = 40.0

        fun open(url: String, host: KiteDesktopWebHost): FxWebSurface {
            val surface = FxWebSurface(url, host)
            FxToolkit.run { surface.create() }
            return surface
        }

        private fun fxButton(button: Int): Int = when (button) {
            KiteDesktopInput.BUTTON_PRIMARY -> AbstractEvents.MOUSEEVENT_PRIMARY_BUTTON
            KiteDesktopInput.BUTTON_SECONDARY -> AbstractEvents.MOUSEEVENT_SECONDARY_BUTTON
            KiteDesktopInput.BUTTON_MIDDLE -> AbstractEvents.MOUSEEVENT_MIDDLE_BUTTON
            else -> AbstractEvents.MOUSEEVENT_NONE_BUTTON
        }
    }
}

/**
 * What a page calls as `window.kitepdfHost`. JavaFX reaches it by reflection, which an internal
 * class allows: it is public in the class file, and so is its method.
 */
internal class LinkBridge(private val host: KiteDesktopWebHost) {
    /** The island script hands over a link the page opened. */
    fun link(href: String) {
        host.link(href)
    }
}
