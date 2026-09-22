package io.github.yuroyami.kitepdf.core.css

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** CSS Color 4, 4.2, 5.1, 5.2 and 6.3: alpha is part of the colour (#253). */
class CssColorAlphaTest {
    @Test
    fun alpha_comes_from_every_form_that_carries_it() {
        for ((raw, alpha) in listOf(
            "transparent" to 0.0,
            "rgba(0,0,0,0)" to 0.0,
            "rgba(27, 31, 35, .05)" to 0.05,
            "rgb(0 0 0 / 50%)" to 0.5,
            "rgba(0,0,0,2)" to 1.0,
            "#ff000080" to 128 / 255.0,
            "#f008" to 136 / 255.0,
            "#ff0000" to 1.0,
            "rgb(255,0,0)" to 1.0,
            "red" to 1.0,
        )) {
            assertEquals(alpha, CssValues.alpha(raw)!!, 1e-9, raw)
        }
    }

    @Test
    fun alpha_is_null_where_the_colour_is_unreadable() {
        for (raw in listOf("currentcolor", "inherit", "none", "#12", "#ff00zz80", "rgba(0,0)", "nonsense")) {
            assertNull(CssValues.alpha(raw), raw)
        }
    }
}
