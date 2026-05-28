package com.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.toPixelMap
import kotlin.test.Test
import kotlin.test.assertTrue

class ImageDecoderTest {
    @Test
    fun decodeRaw_preserves_straight_alpha() {
        // 1×2 RGBA: pixel0 opaque red, pixel1 fully transparent. The decoder must keep the
        // alpha (UNPREMUL), not force it opaque — otherwise transparent image regions (e.g. a
        // logo's /SMask background) render as their opaque base RGB (the grey-box bug).
        val rgba = byteArrayOf(
            255.toByte(), 0, 0, 255.toByte(), // opaque red
            0, 0, 0, 0,                        // fully transparent
        )
        val bmp = ImageDecoder.decodeRaw(rgba, 1, 2) ?: error("decodeRaw returned null")
        val px = bmp.toPixelMap()
        assertTrue(px[0, 0].alpha > 0.9f, "opaque pixel lost its alpha: ${px[0, 0]}")
        assertTrue(px[0, 1].alpha < 0.1f, "transparent pixel forced opaque: ${px[0, 1]}")
    }
}
