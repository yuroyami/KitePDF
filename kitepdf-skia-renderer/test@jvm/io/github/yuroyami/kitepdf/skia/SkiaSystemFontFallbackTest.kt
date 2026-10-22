package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import org.jetbrains.skia.Typeface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A host that does not have the preferred family must still draw Standard-14
 * text.
 *
 * Skia hands back null for a family the host does not have, and a Font built
 * on a null typeface draws nothing at all without reporting a failure. Asking
 * only for "Helvetica" therefore rendered every base-14 page blank on Linux,
 * while passing on macOS, where that family exists.
 */
class SkiaSystemFontFallbackTest {

    private val absent = "NoSuchFamily_ZZQQ"

    private fun inkPixels(typeface: Typeface?): Int {
        val surface = Surface.makeRasterN32Premul(300, 100)
        surface.canvas.clear(WHITE)
        surface.canvas.drawString("Standard 14", 10f, 60f, Font(typeface, 24f), Paint().apply { color = BLACK })
        val bitmap = org.jetbrains.skia.Bitmap().apply { allocN32Pixels(300, 100) }
        surface.makeImageSnapshot().readPixels(bitmap)
        var ink = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) if (bitmap.getColor(x, y) != WHITE) ink++
        }
        return ink
    }

    @Test
    fun a_family_the_host_lacks_falls_back_to_one_it_has() {
        assertNotNull(
            SkiaSystemFonts.resolveCandidates(listOf(absent), FontStyle.NORMAL),
            "no fallback typeface, so base-14 text renders blank",
        )
    }

    @Test
    fun the_fallback_paints_ink() {
        val fallback = SkiaSystemFonts.resolveCandidates(listOf(absent), FontStyle.NORMAL)
        assertTrue(inkPixels(fallback) > 20, "the fallback typeface drew nothing")
    }

    @Test
    fun a_candidate_the_host_lacks_is_skipped_for_the_next_one() {
        val present = FontMgr.default.getFamilyName(0)
        val direct = SkiaSystemFonts.resolveCandidates(listOf(present), FontStyle.NORMAL)
        val afterMiss = SkiaSystemFonts.resolveCandidates(listOf(absent, present), FontStyle.NORMAL)
        assertNotNull(direct, "the host did not resolve its own first family")
        assertEquals(direct.familyName, afterMiss?.familyName, "a missing candidate was not skipped")
    }

    @Test
    fun every_generic_family_resolves_on_this_host() {
        for (family in KiteFontFamily.entries) {
            assertNotNull(SkiaSystemFonts.resolve(family, FontStyle.NORMAL), "$family did not resolve")
        }
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}
