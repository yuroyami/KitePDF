package io.github.yuroyami.kitepdf.render

import io.github.yuroyami.kitepdf.parser.IndirectResolver
import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfName
import io.github.yuroyami.kitepdf.parser.PdfObject
import io.github.yuroyami.kitepdf.parser.PdfReal
import io.github.yuroyami.kitepdf.parser.PdfStream
import io.github.yuroyami.kitepdf.parser.PdfString
import io.github.yuroyami.kitepdf.filters.FilterChain
import kotlin.math.pow

/**
 * Colour-space resolution + sample-to-RGB conversion (ISO 32000-1 §8.6).
 *
 * Supported families:
 *   - DeviceGray / DeviceRGB / DeviceCMYK (the device families)
 *   - Indexed (palette lookup; base is one of the device families)
 *   - ICCBased — *falls back* to DeviceRGB / DeviceCMYK / DeviceGray based
 *     on `/N` component count (the ICC profile is not applied).
 *   - CalGray, CalRGB, Lab — treated as their device equivalent (no
 *     gamma / whitepoint correction); good enough for visual approximation.
 *
 * Not yet handled: DeviceN, Separation, Pattern. Those degrade to grey for
 * stroke / fill operands.
 *
 * Conversion always lands in [RgbColor] for the renderer; sRGB component
 * values 0..1. Out-of-gamut colours are clamped, never thrown.
 */
public sealed class ColorSpace {

    /** Number of input components (1 for Gray, 3 for RGB/Lab, 4 for CMYK). */
    public abstract val componentCount: Int

    /** Convert a sample (one float per component, all in [0,1]) to RGB. */
    public abstract fun toRgb(components: DoubleArray): RgbColor

    /** Default fill colour at "the colour space is set to me" — black-equivalent. */
    public open fun defaultColor(): RgbColor = RgbColor.BLACK

    public object DeviceGray : ColorSpace() {
        override val componentCount: Int = 1
        override fun toRgb(components: DoubleArray): RgbColor {
            val g = components.getOrElse(0) { 0.0 }.coerceIn(0.0, 1.0)
            return RgbColor(g, g, g)
        }
    }

    public object DeviceRGB : ColorSpace() {
        override val componentCount: Int = 3
        override fun toRgb(components: DoubleArray): RgbColor = RgbColor(
            components.getOrElse(0) { 0.0 }.coerceIn(0.0, 1.0),
            components.getOrElse(1) { 0.0 }.coerceIn(0.0, 1.0),
            components.getOrElse(2) { 0.0 }.coerceIn(0.0, 1.0),
        )
    }

    public object DeviceCMYK : ColorSpace() {
        override val componentCount: Int = 4
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
    public class Indexed(
        public val base: ColorSpace,
        public val hival: Int,
        public val palette: ByteArray,
    ) : ColorSpace() {
        override val componentCount: Int = 1

        // Resolve the whole palette to RGB once, then every lookup is an array
        // index — no per-pixel/per-sample DoubleArray alloc or base conversion.
        private val lut: Array<RgbColor> by lazy {
            val comp = base.componentCount
            Array(hival + 1) { idx ->
                val off = idx * comp
                if (off + comp > palette.size) RgbColor.BLACK
                else base.toRgb(DoubleArray(comp) { i -> (palette[off + i].toInt() and 0xFF) / 255.0 })
            }
        }

        override fun toRgb(components: DoubleArray): RgbColor {
            val idx = (components.getOrElse(0) { 0.0 } * hival).toInt().coerceIn(0, hival)
            return lut[idx]
        }

        /** Direct palette lookup by integer index (for image samples, which are
         *  raw indices rather than normalised fractions). Clamped to the palette. */
        public fun colorAt(index: Int): RgbColor = lut[index.coerceIn(0, hival)]
    }

    /**
     * Separation / DeviceN colour space (§8.6.6.4). [componentCount] tint values
     * are fed through [tintTransform] to produce colours in [alternate], which
     * converts them to RGB. Separation is the n=1 special case.
     */
    public class DeviceN(
        override val componentCount: Int,
        public val alternate: ColorSpace,
        public val tintTransform: PdfFunction,
        /** Colorant names; a single "None" Separation paints nothing. */
        public val names: List<String>,
    ) : ColorSpace() {
        private val isNone = names.size == 1 && names[0] == "None"
        override fun toRgb(components: DoubleArray): RgbColor {
            if (isNone) return RgbColor.WHITE
            val tint = tintTransform.evaluate(components)
            return alternate.toRgb(tint)
        }
        // A separation at full tint (1.0) is its "solid" colour; default to that.
        override fun defaultColor(): RgbColor =
            if (isNone) RgbColor.WHITE else alternate.toRgb(tintTransform.evaluate(DoubleArray(componentCount) { 1.0 }))
    }

    /**
     * CIE 1976 L*a*b* colour space (§8.6.5.4). Components are actual L (0..100)
     * and a/b (per /Range), converted via XYZ to sRGB using the /WhitePoint.
     */
    public class Lab(
        private val whitePoint: DoubleArray,
        private val rangeAB: DoubleArray,
    ) : ColorSpace() {
        override val componentCount: Int = 3
        override fun toRgb(components: DoubleArray): RgbColor {
            val L = components.getOrElse(0) { 0.0 }.coerceIn(0.0, 100.0)
            val a = components.getOrElse(1) { 0.0 }.coerceIn(rangeAB[0], rangeAB[1])
            val bb = components.getOrElse(2) { 0.0 }.coerceIn(rangeAB[2], rangeAB[3])
            val fy = (L + 16.0) / 116.0
            val fx = fy + a / 500.0
            val fz = fy - bb / 200.0
            fun g(t: Double): Double { val t3 = t * t * t; return if (t3 > 0.008856) t3 else (t - 16.0 / 116.0) / 7.787 }
            val x = whitePoint[0] * g(fx)
            val y = whitePoint[1] * g(fy)
            val z = whitePoint[2] * g(fz)
            // XYZ (D65-ish) → linear sRGB → gamma.
            var r = x * 3.2406 - y * 1.5372 - z * 0.4986
            var gr = -x * 0.9689 + y * 1.8758 + z * 0.0415
            var b = x * 0.0557 - y * 0.2040 + z * 1.0570
            fun gamma(c: Double): Double {
                val cc = c.coerceIn(0.0, 1.0)
                return if (cc <= 0.0031308) 12.92 * cc else 1.055 * cc.pow(1.0 / 2.4) - 0.055
            }
            r = gamma(r); gr = gamma(gr); b = gamma(b)
            return RgbColor(r.coerceIn(0.0, 1.0), gr.coerceIn(0.0, 1.0), b.coerceIn(0.0, 1.0))
        }
    }

    /** Fallback for spaces we don't fully model. Routes to grey. */
    public class Unsupported(public val name: String, override val componentCount: Int) : ColorSpace() {
        override fun toRgb(components: DoubleArray): RgbColor {
            // Average the components as a rough grey approximation.
            val avg = components.take(componentCount).average().coerceIn(0.0, 1.0)
            return RgbColor(avg, avg, avg)
        }
    }

    public companion object {

        public fun resolve(obj: PdfObject?, refs: IndirectResolver): ColorSpace {
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
                "Lab" -> resolveLab(arr, refs)
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
                "Separation" -> resolveSeparation(arr, refs)
                "DeviceN" -> resolveDeviceN(arr, refs)
                "Pattern" -> Unsupported(tag, 1)
                else -> DeviceGray
            }
        }

        private fun resolveSeparation(arr: PdfArray, refs: IndirectResolver): ColorSpace {
            // [/Separation name alternateSpace tintTransform]
            val name = (arr.getOrNull(1)?.resolve(refs) as? PdfName)?.value ?: ""
            val alternate = resolve(arr.getOrNull(2), refs)
            val tint = PdfFunction.parse(arr.getOrNull(3), refs) ?: return Unsupported("Separation", 1)
            return DeviceN(1, alternate, tint, listOf(name))
        }

        private fun resolveDeviceN(arr: PdfArray, refs: IndirectResolver): ColorSpace {
            // [/DeviceN names alternateSpace tintTransform (attributes)]
            val namesArr = arr.getOrNull(1)?.resolve(refs) as? PdfArray ?: return Unsupported("DeviceN", 1)
            val names = namesArr.mapNotNull { (it as? PdfName)?.value }
            if (names.isEmpty()) return Unsupported("DeviceN", 1)
            val alternate = resolve(arr.getOrNull(2), refs)
            val tint = PdfFunction.parse(arr.getOrNull(3), refs) ?: return Unsupported("DeviceN", names.size)
            return DeviceN(names.size, alternate, tint, names)
        }

        private fun resolveLab(arr: PdfArray, refs: IndirectResolver): ColorSpace {
            val params = arr.getOrNull(1)?.resolve(refs) as? PdfDictionary
            val wp = params?.getArray("WhitePoint")?.let { wpArr ->
                DoubleArray(3) { i -> (wpArr.getOrNull(i) as? PdfReal)?.value
                    ?: (wpArr.getOrNull(i) as? PdfInt)?.value?.toDouble() ?: 1.0 }
            } ?: doubleArrayOf(0.9505, 1.0, 1.089)   // D65 default
            val range = params?.getArray("Range")?.let { rArr ->
                DoubleArray(4) { i -> (rArr.getOrNull(i) as? PdfReal)?.value
                    ?: (rArr.getOrNull(i) as? PdfInt)?.value?.toDouble() ?: 0.0 }
            } ?: doubleArrayOf(-100.0, 100.0, -100.0, 100.0)
            return Lab(wp, range)
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
