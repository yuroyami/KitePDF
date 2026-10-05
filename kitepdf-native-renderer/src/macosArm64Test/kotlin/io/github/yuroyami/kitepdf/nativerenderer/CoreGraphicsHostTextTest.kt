package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGGlyphVar
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGRectMake
import platform.CoreText.CTFontCreateWithName
import platform.CoreText.CTFontGetGlyphsForCharacters
import kotlinx.cinterop.UShortVar
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Host-font text draws through CoreText, which takes each character that Times, Helvetica or
 * Courier lacks from a face that has it, and shapes a part whose letters join. The canvas drew one
 * character at a time in one of those faces, so an Arabic word, a letter outside the BMP and an
 * emoji drew nothing, and letters that it did draw never joined (#589).
 */
@OptIn(ExperimentalForeignApi::class)
class CoreGraphicsHostTextTest {

    private val w = 300
    private val h = 120
    private val spec = FontSpec(KiteFontFamily.Serif, bold = false, italic = false)

    /** Each of [runs] drawn as host text at 60 px, a run at its own x, as RGBA bytes. */
    private fun draw(vararg runs: Pair<List<TextGlyph>, Double>): UByteArray {
        val pixels = UByteArray(w * h * 4)
        val space = CGColorSpaceCreateDeviceRGB()
        pixels.usePinned { pinned ->
            val ctx = CGBitmapContextCreate(
                pinned.addressOf(0), w.toULong(), h.toULong(), 8u, (w * 4).toULong(), space,
                CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            )!!
            CGContextSetRGBFillColor(ctx, 1.0, 1.0, 1.0, 1.0)
            CGContextFillRect(ctx, CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()))
            // A device space that runs down, as ApplePdfRasterizer gives the canvas.
            CGContextTranslateCTM(ctx, 0.0, h.toDouble())
            CGContextScaleCTM(ctx, 1.0, -1.0)
            val canvas = CoreGraphicsCanvas(ctx)
            for ((glyphs, x) in runs) {
                canvas.drawGlyphs(glyphs, 60.0, 1000, false, spec, KiteMatrix(1.0, 0.0, 0.0, -1.0, x, 80.0), RgbColor.BLACK)
            }
            CGContextRelease(ctx)
        }
        CGColorSpaceRelease(space)
        return pixels
    }

    private fun UByteArray.red(): IntArray = IntArray(w * h) { this[it * 4].toInt() }

    private fun glyphs(vararg letters: String) = letters.map { TextGlyph(0, 1, -1, it, 1000.0, null, false) }

    /** True when the face named [postScriptName] has a glyph for [text], as the canvas looked one up before. */
    private fun faceHas(postScriptName: String, text: String): Boolean {
        val name = CFStringCreateWithCString(null, postScriptName, kCFStringEncodingUTF8)!!
        val font = CTFontCreateWithName(name, 12.0, null)!!
        CFRelease(name)
        return try {
            memScoped {
                val chars = allocArray<UShortVar>(text.length)
                for (i in text.indices) chars[i] = text[i].code.toUShort()
                CTFontGetGlyphsForCharacters(font, chars, allocArray<CGGlyphVar>(text.length), text.length.toLong())
            }
        } finally {
            CFRelease(font)
        }
    }

    @Test
    fun an_arabic_word_draws_and_joins() {
        // Times, the face of a serif host font, has no Arabic, so a lookup in it alone drew nothing.
        assertFalse(faceHas("Times-Roman", "ت"), "Times has Arabic, so this test no longer needs the cascade")
        // The word بيت given in visual order, left to right: teh, yeh, beh, an em apart. Joined,
        // the three letters are one stroke with dots apart from it; drawn alone, they are three.
        val red = draw(glyphs("ت", "ي", "ب") to 20.0).red()
        assertTrue(red.any { it < 128 }, "the Arabic letters drew nothing")
        assertEquals(1, inkStrokes(red, w, h), "the letters drew apart")
    }

    @Test
    fun a_letter_outside_the_bmp_draws() {
        // MATHEMATICAL BOLD CAPITAL A, a surrogate pair, which the canvas looked up by its high half.
        val letter = "𝐀"
        assertFalse(faceHas("Times-Roman", letter), "Times has the letter, so this test no longer needs the cascade")
        val red = draw(glyphs(letter) to 20.0).red()
        assertTrue(red.any { it < 128 }, "the letter drew nothing")
    }

    @Test
    fun an_emoji_draws_in_colour() {
        // GRINNING FACE, whose glyph in Apple Color Emoji is a bitmap with no outline to fill.
        val pixels = draw(glyphs("😀") to 20.0)
        val coloured = (0 until w * h).count {
            val r = pixels[it * 4].toInt()
            val b = pixels[it * 4 + 2].toInt()
            r > 180 && b < 100
        }
        assertTrue(coloured > 50, "the emoji drew no yellow: $coloured pixels")
    }

    @Test
    fun latin_letters_keep_their_own_pens() {
        val run = draw(glyphs("a", "b", "c") to 20.0)
        val alone = draw(glyphs("a") to 20.0, glyphs("b") to 80.0, glyphs("c") to 140.0)
        assertContentEquals(alone, run)
    }
}
