package com.yuroyami.kitepdf.render

import com.yuroyami.kitepdf.parser.IndirectResolver
import com.yuroyami.kitepdf.parser.PdfArray
import com.yuroyami.kitepdf.parser.PdfInt
import com.yuroyami.kitepdf.parser.PdfName
import com.yuroyami.kitepdf.parser.PdfObject
import com.yuroyami.kitepdf.parser.PdfReal
import com.yuroyami.kitepdf.parser.PdfStream
import com.yuroyami.kitepdf.parser.PdfString
import com.yuroyami.kitepdf.filters.FilterChain

/**
 * Colour-space resolution + sample-to-RGB conversion (ISO 32000-1 §8.6).
 *
 * v0.0.4 supported families:
 *   - DeviceGray / DeviceRGB / DeviceCMYK (the device families)
 *   - Indexed (palette lookup; base is one of the device families)
 *   - ICCBased — *falls back* to DeviceRGB / DeviceCMYK / DeviceGray based
 *     on `/N` component count (we don't apply the ICC profile yet).
 *   - CalGray, CalRGB, Lab — treated as their device equivalent (no
 *     gamma / whitepoint correction); good enough for visual approximation.
 *
 * Not yet handled: DeviceN, Separation, Pattern. Those degrade to grey for
 * stroke / fill operands.
 *
 * Conversion always lands in [RgbColor] for the renderer; sRGB component
 * values 0..1. Out-of-gamut colours are clamped, never thrown.
 */
sealed class ColorSpace {

    /** Number of input components (1 for Gray, 3 for RGB/Lab, 4 for CMYK). */
    abstract val componentCount: Int

    /** Convert a sample (one float per component, all in [0,1]) to RGB. */
    abstract fun toRgb(components: DoubleArray): RgbColor

    /** Default fill colour at "the colour space is set to me" — black-equivalent. */
    open fun defaultColor(): RgbColor = RgbColor.BLACK

    object DeviceGray : ColorSpace() {
        override val componentCount = 1
        override fun toRgb(components: DoubleArray): RgbColor {
            val g = components.getOrElse(0) { 0.0 }.coerceIn(0.0, 1.0)
            return RgbColor(g, g, g)
        }
    }

    object DeviceRGB : ColorSpace() {
        override val componentCount = 3
        override fun toRgb(components: DoubleArray): RgbColor = RgbColor(
            components.getOrElse(0) { 0.0 }.coerceIn(0.0, 1.0),
            components.getOrElse(1) { 0.0 }.coerceIn(0.0, 1.0),
            components.getOrElse(2) { 0.0 }.coerceIn(0.0, 1.0),
        )
    }

    object DeviceCMYK : ColorSpace() {
        override val componentCount = 4
        override fun toRgb(components: DoubleArray): RgbColor {
            val c = components.getOrElse(0) { 0.0 }.coerceIn(0.0, 1.0)
            val m = components.getOrElse(1) { 0.0 }.coerceIn(0.0, 1.0)
            val y = components.getOrElse(2) { 0.0 }.coerceIn(0.0, 1.0)
            val k = components.getOrElse(3) { 0.0 }.coerceIn(0.0, 1.0)
            // Process-CMYK→RGB polynomial (Firefox/pdf.js; approximates US Web
            // Coated SWOP). Far closer to how mainstream viewers render CMYK than
            // a naïve (1-c)(1-k) subtractive map, which yields oversaturated
            // primaries (pure cyan/red) instead of muted process colours.
            val r = 255.0 +
                c * (-4.387332384609988 * c + 54.48615194189176 * m + 18.82290502165302 * y + 212.25662451639585 * k - 285.2331026137004) +
                m * (1.7149763477362134 * m - 5.6096736904047315 * y - 17.873870861415444 * k - 5.497006427196366) +
                y * (-2.5217340131683033 * y - 21.248923337353073 * k + 17.5119270841813) +
                k * (-21.86122147463605 * k - 189.48180835922747)
            val g = 255.0 +
                c * (8.841041422036149 * c + 60.118027045597366 * m + 6.871425592049007 * y + 31.159100130055922 * k - 79.2970844816548) +
                m * (-15.310361306967817 * m + 17.575251261109482 * y + 131.35250912493976 * k - 190.9453302588951) +
                y * (4.444339102852739 * y + 9.8632861493405 * k - 24.86741582555878) +
                k * (-20.737325471181034 * k - 187.80453709719578)
            val b = 255.0 +
                c * (0.8842522430003296 * c + 8.078677503112928 * m + 30.89978309703729 * y - 0.23883238689178934 * k - 14.183576799673286) +
                m * (10.49593273432072 * m + 63.02378494754052 * y + 50.606957656360734 * k - 112.23884253719248) +
                y * (0.03296041114873217 * y + 115.60384449646641 * k - 193.58209356861505) +
                k * (-22.33816807309886 * k - 180.12613974708367)
            return RgbColor(
                (r / 255.0).coerceIn(0.0, 1.0),
                (g / 255.0).coerceIn(0.0, 1.0),
                (b / 255.0).coerceIn(0.0, 1.0),
            )
        }
        override fun defaultColor(): RgbColor = RgbColor.BLACK
    }

    /**
     * Indexed colour space. Each sample is a single byte 0..hival that indexes
     * into [palette]; the palette stores [base.componentCount] bytes per entry.
     */
    class Indexed(
        val base: ColorSpace,
        val hival: Int,
        val palette: ByteArray,
    ) : ColorSpace() {
        override val componentCount = 1
        override fun toRgb(components: DoubleArray): RgbColor {
            val idx = (components.getOrElse(0) { 0.0 } * hival).toInt().coerceIn(0, hival)
            val off = idx * base.componentCount
            if (off + base.componentCount > palette.size) return RgbColor.BLACK
            val tmp = DoubleArray(base.componentCount) { i ->
                (palette[off + i].toInt() and 0xFF) / 255.0
            }
            return base.toRgb(tmp)
        }
    }

    /** Fallback for spaces we don't fully model. Routes to grey. */
    class Unsupported(val name: String, override val componentCount: Int) : ColorSpace() {
        override fun toRgb(components: DoubleArray): RgbColor {
            // Average the components as a rough grey approximation.
            val avg = components.take(componentCount).average().coerceIn(0.0, 1.0)
            return RgbColor(avg, avg, avg)
        }
    }

    companion object {

        fun resolve(obj: PdfObject?, refs: IndirectResolver): ColorSpace {
            val resolved = obj?.resolve(refs) ?: return DeviceGray
            return when (resolved) {
                is PdfName -> resolveByName(resolved.value)
                is PdfArray -> resolveArray(resolved, refs)
                else -> DeviceGray
            }
        }

        private fun resolveByName(name: String): ColorSpace = when (name) {
            "DeviceGray", "G" -> DeviceGray
            "DeviceRGB", "RGB" -> DeviceRGB
            "DeviceCMYK", "CMYK" -> DeviceCMYK
            // Pattern / CalGray / CalRGB / Lab without alternates collapse to grey.
            else -> DeviceGray
        }

        private fun resolveArray(arr: PdfArray, refs: IndirectResolver): ColorSpace {
            val tag = (arr.firstOrNull() as? PdfName)?.value ?: return DeviceGray
            return when (tag) {
                "DeviceGray", "G" -> DeviceGray
                "DeviceRGB", "RGB" -> DeviceRGB
                "DeviceCMYK", "CMYK" -> DeviceCMYK
                "CalGray" -> DeviceGray
                "CalRGB" -> DeviceRGB
                "Lab" -> DeviceRGB
                "ICCBased" -> {
                    // /ICCBased [/ICCBased <stream>] — stream dict carries /N.
                    val streamObj = arr.getOrNull(1)?.resolve(refs) as? PdfStream
                    val n = streamObj?.dict?.getInt("N")?.toInt() ?: 3
                    when (n) {
                        1 -> DeviceGray
                        4 -> DeviceCMYK
                        else -> DeviceRGB
                    }
                }
                "Indexed" -> resolveIndexed(arr, refs)
                "Pattern", "DeviceN", "Separation" -> Unsupported(tag, 1)
                else -> DeviceGray
            }
        }

        private fun resolveIndexed(arr: PdfArray, refs: IndirectResolver): ColorSpace {
            // [/Indexed <base> <hival> <lookup>]
            val baseObj = arr.getOrNull(1)
            val base = resolve(baseObj, refs)
            val hival = (arr.getOrNull(2)?.let {
                when (it) {
                    is PdfInt -> it.value.toInt()
                    is PdfReal -> it.value.toInt()
                    else -> 0
                }
            }) ?: 0
            val lookup = when (val lookupObj = arr.getOrNull(3)?.resolve(refs)) {
                is PdfString -> lookupObj.bytes
                is PdfStream -> FilterChain.decode(lookupObj)
                else -> ByteArray(0)
            }
            return Indexed(base, hival, lookup)
        }
    }
}
