package io.github.yuroyami.kitepdf.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import java.awt.EventQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.skia.Image

/**
 * Runs only a UI operation on the same EDT as desktop exports (#428). Test control, latches and
 * polling stay on the test thread, so a suspended export never waits behind a blocked UI thread.
 */
internal fun <T> onTestUiThread(block: () -> T): T {
    if (EventQueue.isDispatchThread()) return block()
    val task = FutureTask(block)
    EventQueue.invokeLater(task)
    return try {
        task.get(30, TimeUnit.SECONDS)
    } catch (failure: java.util.concurrent.ExecutionException) {
        throw failure.cause ?: failure
    } catch (failure: java.util.concurrent.TimeoutException) {
        task.cancel(false)
        throw AssertionError("the EDT did not finish a test UI operation within 30 seconds", failure)
    }
}

/** Host calls may suspend while the driver keeps producing UI frames. */
internal object TestUiDispatcher : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !EventQueue.isDispatchThread()
    override fun dispatch(context: CoroutineContext, block: Runnable): Unit = EventQueue.invokeLater(block)
}

/** A scene with the real desktop thread contract, preserving its chosen effect order. */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
internal class EdtImageComposeScene(
    width: Int,
    height: Int,
    density: Density = Density(1f),
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    coroutineContext: CoroutineContext = Dispatchers.Unconfined,
    content: @Composable () -> Unit = {},
) {
    private val scene = onTestUiThread {
        androidx.compose.ui.ImageComposeScene(width, height, density, layoutDirection, coroutineContext, content)
    }

    val semanticsOwners: Collection<SemanticsOwner>
        get() = onTestUiThread { scene.semanticsOwners.toList() }

    var constraints: Constraints
        get() = onTestUiThread { scene.constraints }
        set(value) { onTestUiThread { scene.constraints = value } }

    var layoutDirection: LayoutDirection
        get() = onTestUiThread { scene.layoutDirection }
        set(value) { onTestUiThread { scene.layoutDirection = value } }

    fun calculateContentSize(): IntSize = onTestUiThread { scene.calculateContentSize() }

    fun setContent(content: @Composable () -> Unit): Unit = onTestUiThread { scene.setContent(content) }

    fun hasInvalidations(): Boolean = onTestUiThread { scene.hasInvalidations() }

    fun render(nanoTime: Long = 0): Image = onTestUiThread { scene.render(nanoTime) }

    fun sendPointerEvent(
        eventType: PointerEventType,
        position: Offset,
        scrollDelta: Offset = Offset.Zero,
        timeMillis: Long = System.nanoTime() / 1_000_000L,
        type: PointerType = PointerType.Mouse,
        buttons: PointerButtons? = null,
        keyboardModifiers: PointerKeyboardModifiers? = null,
        nativeEvent: Any? = null,
        button: PointerButton? = null,
    ): Unit = onTestUiThread {
        scene.sendPointerEvent(eventType, position, scrollDelta, timeMillis, type, buttons, keyboardModifiers, nativeEvent, button)
    }

    fun sendPointerEvent(
        eventType: PointerEventType,
        pointers: List<ComposeScenePointer>,
        buttons: PointerButtons = PointerButtons(),
        keyboardModifiers: PointerKeyboardModifiers = PointerKeyboardModifiers(),
        scrollDelta: Offset = Offset.Zero,
        timeMillis: Long = System.nanoTime() / 1_000_000L,
        nativeEvent: Any? = null,
        button: PointerButton? = null,
        scaleGestureFactor: Float = 1f,
        panGestureOffset: Offset = Offset.Zero,
    ): Unit = onTestUiThread {
        scene.sendPointerEvent(eventType, pointers, buttons, keyboardModifiers, scrollDelta, timeMillis,
            nativeEvent, button, scaleGestureFactor, panGestureOffset)
    }

    fun sendKeyEvent(event: KeyEvent): Boolean = onTestUiThread { scene.sendKeyEvent(event) }

    fun close(): Unit = onTestUiThread { scene.close() }

    inline fun <T> use(block: (EdtImageComposeScene) -> T): T = try { block(this) } finally { close() }
}

/** Direct canvas fixtures use the same thread as real scene text and synchronous exports. */
internal fun CanvasDrawScope.drawOnTestUiThread(
    density: Density,
    layoutDirection: LayoutDirection,
    canvas: Canvas,
    size: Size,
    block: DrawScope.() -> Unit,
): Unit = onTestUiThread { draw(density, layoutDirection, canvas, size, block) }
