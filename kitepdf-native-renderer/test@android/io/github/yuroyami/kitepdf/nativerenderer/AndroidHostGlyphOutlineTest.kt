package io.github.yuroyami.kitepdf.nativerenderer

import android.graphics.Bitmap
import android.graphics.Canvas
import io.github.yuroyami.kitepdf.core.font.FontSpec
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `hostGlyphOutline` gives the outline of host text in the logical font the canvas draws it in,
 * at 1000 units to the em with y up, from API 34, which can walk a path. Before API 34 it gives
 * none (#85, #893).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29, 35])
class AndroidHostGlyphOutlineTest {

    private val canvas = AndroidNativeCanvas(Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)))

    @Test
    fun a_letter_outlines_from_api_34_with_y_up() {
        val outline = canvas.hostGlyphOutline("H", FontSpec.SansSerif)
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertNull(outline, "API ${android.os.Build.VERSION.SDK_INT} cannot walk a path")
            return
        }
        val box = assertNotNull(outline?.bounds(), "the sans-serif face outlines H")
        // An H stands on the baseline and is about 0.7 em tall: y runs up, so its top is positive.
        assertTrue(box.bottom in -10.0..10.0 && box.top in 600.0..800.0, "the H runs from ${box.bottom} to ${box.top}")
        val two = assertNotNull(canvas.hostGlyphOutline("HH", FontSpec.SansSerif)?.bounds())
        // The second H starts one advance, about 0.7 em, after the first.
        assertTrue(two.right - box.right in 550.0..850.0, "the second H ends at ${two.right}, the first at ${box.right}")
    }
}
