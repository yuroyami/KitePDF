package io.github.yuroyami.kitepdf.core.css

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The colour functions and named colours of CSS Color 4 (#606). Each expected value is what
 * headless Chromium gives for the same colour, serialized by an `<input type=color>` as
 * `#rrggbb` in sRGB, which clips a colour outside sRGB.
 */
class CssColorFunctionTest {

    private fun hexOf(raw: String): String? = CssValues.color(raw)?.let { c ->
        "#" + listOf(c.r, c.g, c.b).joinToString("") { (it * 255).roundToInt().toString(16).padStart(2, '0') }
    }

    @Test
    fun each_function_converts_to_srgb_as_chromium_does() {
        val chromium = listOf(
            "hsl(120 100% 25%)" to "#008000",
            "hsl(120, 100%, 25%)" to "#008000",
            "hsla(240deg 100% 50% / 0.5)" to "#0000ff",
            "hsl(0.5turn 50% 50%)" to "#40bfbf",
            "hsl(none 0% 50%)" to "#808080",
            "hsl(120deg, 100%, 25%, 0.5)" to "#008000",
            "hwb(60 20% 30%)" to "#b3b333",
            "hwb(0 60% 60%)" to "#808080",
            "lab(50 20 -30)" to "#856caa",
            "lab(100 0 0)" to "#ffffff",
            "lab(50% 20% -30%)" to "#8769b7",
            "lch(60 40 120)" to "#7d9a51",
            "oklab(0.6 0.1 -0.1)" to "#9f63ba",
            "oklab(60% 25% -25%)" to "#9f63ba",
            "oklch(0.7 0.15 30)" to "#ed7665",
            "oklch(0.9 0.4 150)" to "#00ff00",
            "color(display-p3 1 0 0)" to "#ff0000",
            "color(srgb-linear 0.5 0.5 0.5)" to "#bcbcbc",
            "color(xyz 0.3 0.3 0.3)" to "#a2918f",
            "color(xyz-d50 0.3 0.3 0.3)" to "#9793a5",
            "color(rec2020 0.5 0.6 0.2)" to "#7fa627",
            "color(a98-rgb 0.3 0.6 0.9)" to "#009ae9",
            "color(prophoto-rgb 0.3 0.6 0.9)" to "#00b5f3",
            "rgb(10% 20% 30%)" to "#1a334d",
            "rgb(300 -5 128)" to "#ff0080",
            "rgb(1.5 2.5 3.5)" to "#020304",
            "rgb(none 50 50)" to "#003232",
            "rgba(255,0,0,0.5)" to "#ff0000",
            "#ABCDEF80" to "#abcdef",
            "#abcd" to "#aabbcc",
            "RED" to "#ff0000",
            "rebeccapurple" to "#663399",
            "hsl(120 100 25)" to "#008000",
            "rgb(1e2 +5 0)" to "#640500",
            "hsl(120 100% 25%" to "#008000",
        )
        for ((raw, hex) in chromium) assertEquals(hex, hexOf(raw), raw)
    }

    @Test
    fun a_function_carries_its_alpha() {
        assertEquals(0.5, CssValues.alpha("hsla(240deg 100% 50% / 0.5)")!!, 1e-9)
        assertEquals(0.25, CssValues.alpha("oklch(0.7 0.15 30 / 25%)")!!, 1e-9)
        assertEquals(0.5, CssValues.alpha("hsl(120deg, 100%, 25%, 0.5)")!!, 1e-9)
        assertEquals(1.0, CssValues.alpha("lab(50 20 -30)")!!, 1e-9)
    }

    @Test
    fun what_chromium_rejects_is_no_colour() {
        for (raw in listOf("rgb(10, 20 30)", "rgb(255, 50%, 0)", "hsl(120, 100, 25)", "rgb(none, 0, 0)", "hwb(0, 20%, 30%)",
            "color(nonsense 1 0 0)", "lab(50 20)", "rgb(1 2 3 / 1 / 2)", "rgb(1px 2 3)")) {
            assertNull(CssValues.color(raw), raw)
        }
    }

    @Test
    fun every_named_colour_is_read() {
        assertEquals(148, CssColors.names.size)
        for (name in CssColors.names) assertEquals(1.0, CssValues.alpha(name), name)
    }
}
