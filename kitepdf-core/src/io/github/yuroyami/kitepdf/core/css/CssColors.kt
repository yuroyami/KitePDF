package io.github.yuroyami.kitepdf.core.css

import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * The `<color>` syntax of CSS Color 4: hex, the named colours, and the functions `rgb()`,
 * `hsl()`, `hwb()`, `lab()`, `lch()`, `oklab()`, `oklch()` and `color()` (#606). Each colour
 * converts to sRGB, and a colour outside sRGB is clipped to it, as Chromium paints it.
 */
internal object CssColors {

    /** A colour in sRGB, each channel from 0 to 1, with its alpha. */
    class Parsed(val rgb: RgbColor, val alpha: Double)

    /** [raw] as a colour, or null when it is not one this reads. */
    fun parse(raw: String): Parsed? {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        if (s == "transparent") return Parsed(RgbColor(0.0, 0.0, 0.0), 0.0)
        if (s.startsWith("#")) return hex(s.substring(1))
        NAMED[s]?.let { return Parsed(rgb(it), 1.0) }
        val open = s.indexOf('(')
        if (open <= 0) return null
        val name = s.substring(0, open).trim()
        // The end of the value closes a function whose `)` is missing, as CSS parsing does.
        val args = Args.of(s.substring(open + 1, if (s.endsWith(")")) s.length - 1 else s.length)) ?: return null
        return when (name) {
            "rgb", "rgba" -> rgbFunction(args)
            "hsl", "hsla" -> hslFunction(args)
            "hwb" -> hwbFunction(args)
            "lab" -> labLike(args, oklab = false, polar = false)
            "lch" -> labLike(args, oklab = false, polar = true)
            "oklab" -> labLike(args, oklab = true, polar = false)
            "oklch" -> labLike(args, oklab = true, polar = true)
            "color" -> colorFunction(args)
            else -> null
        }
    }

    // ---- arguments ----

    /**
     * A function's arguments: the components, each a token, and the alpha after a slash or, in
     * the legacy syntax, as the fourth comma separated value.
     */
    private class Args(val parts: List<String>, val alpha: String?, val legacy: Boolean) {
        companion object {
            fun of(inner: String): Args? {
                val text = inner.trim()
                if (',' in text) {
                    if ('/' in text) return null
                    val parts = text.split(',').map { it.trim() }
                    if (parts.any { it.isEmpty() || it.any(Char::isWhitespace) || it == "none" }) return null
                    return when (parts.size) {
                        3 -> Args(parts, null, legacy = true)
                        4 -> Args(parts.subList(0, 3), parts[3], legacy = true)
                        else -> null
                    }
                }
                val slash = text.split('/')
                if (slash.size > 2) return null
                val parts = slash[0].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                val alpha = slash.getOrNull(1)?.trim()?.also { if (it.isEmpty() || it.any(Char::isWhitespace)) return null }
                return Args(parts, alpha, legacy = false)
            }
        }
    }

    /** A number, or a percentage scaled so that 100% is [percentOf]; 0 for `none`; null otherwise. */
    private fun number(token: String, percentOf: Double): Double? = when {
        token == "none" -> 0.0
        token.endsWith("%") -> plain(token.dropLast(1))?.let { it / 100.0 * percentOf }
        else -> plain(token)
    }

    /** A CSS `<number>`, which has no unit. */
    private fun plain(token: String): Double? =
        token.takeIf { NUMBER.matches(it) }?.toDoubleOrNull()?.takeIf { it.isFinite() }

    /** A hue in degrees: a number or an angle; 0 for `none`. */
    private fun hue(token: String): Double? {
        if (token == "none") return 0.0
        plain(token)?.let { return it }
        val unit = ANGLE_UNITS.firstOrNull { token.endsWith(it.first) } ?: return null
        return plain(token.dropLast(unit.first.length))?.let { it * unit.second }
    }

    /** The alpha of [args], clamped to 0..1: 1 when it has none. */
    private fun alpha(args: Args): Double? {
        val a = args.alpha ?: return 1.0
        return number(a, 1.0)?.coerceIn(0.0, 1.0)
    }

    // ---- the functions ----

    private fun rgbFunction(args: Args): Parsed? {
        if (args.parts.size != 3) return null
        // The legacy syntax takes three numbers or three percentages, not a mix (CSS Color 4, 5.1).
        if (args.legacy && args.parts.map { it.endsWith("%") }.distinct().size != 1) return null
        val c = args.parts.map { (number(it, 255.0) ?: return null) / 255.0 }
        return Parsed(clip(c[0], c[1], c[2]), alpha(args) ?: return null)
    }

    private fun hslFunction(args: Args): Parsed? {
        if (args.parts.size != 3) return null
        if (args.legacy && args.parts.drop(1).any { !it.endsWith("%") }) return null
        val h = hue(args.parts[0]) ?: return null
        val s = (number(args.parts[1], 100.0) ?: return null).coerceIn(0.0, 100.0) / 100.0
        val l = (number(args.parts[2], 100.0) ?: return null).coerceIn(0.0, 100.0) / 100.0
        val (r, g, b) = hslToRgb(h, s, l)
        return Parsed(clip(r, g, b), alpha(args) ?: return null)
    }

    private fun hwbFunction(args: Args): Parsed? {
        if (args.legacy || args.parts.size != 3) return null
        val h = hue(args.parts[0]) ?: return null
        val w = (number(args.parts[1], 100.0) ?: return null).coerceIn(0.0, 100.0) / 100.0
        val k = (number(args.parts[2], 100.0) ?: return null).coerceIn(0.0, 100.0) / 100.0
        val a = alpha(args) ?: return null
        if (w + k >= 1.0) {
            val gray = w / (w + k)
            return Parsed(clip(gray, gray, gray), a)
        }
        val (r, g, b) = hslToRgb(h, 1.0, 0.5)
        fun mix(v: Double) = v * (1 - w - k) + w
        return Parsed(clip(mix(r), mix(g), mix(b)), a)
    }

    /** `lab()`, `lch()`, `oklab()` and `oklch()` (CSS Color 4, 8 and 9). */
    private fun labLike(args: Args, oklab: Boolean, polar: Boolean): Parsed? {
        if (args.legacy || args.parts.size != 3) return null
        val lightness = number(args.parts[0], if (oklab) 1.0 else 100.0) ?: return null
        val l = lightness.coerceIn(0.0, if (oklab) 1.0 else 100.0)
        val axis = if (oklab) 0.4 else if (polar) 150.0 else 125.0
        val (a, b) = if (polar) {
            val c = (number(args.parts[1], axis) ?: return null).coerceAtLeast(0.0)
            val h = (hue(args.parts[2]) ?: return null) * PI / 180.0
            c * cos(h) to c * sin(h)
        } else {
            (number(args.parts[1], axis) ?: return null) to (number(args.parts[2], axis) ?: return null)
        }
        val linear = if (oklab) oklabToLinearSrgb(l, a, b) else xyzD65ToLinearSrgb(d50ToD65(labToXyzD50(l, a, b)))
        return Parsed(encodeSrgb(linear), alpha(args) ?: return null)
    }

    /** `color()` over the predefined colour spaces (CSS Color 4, 10). */
    private fun colorFunction(args: Args): Parsed? {
        if (args.legacy || args.parts.size != 4) return null
        val c = args.parts.drop(1).map { number(it, 1.0) ?: return null }
        val a = alpha(args) ?: return null
        val linear = when (args.parts[0]) {
            "srgb" -> return Parsed(clip(c[0], c[1], c[2]), a)
            "srgb-linear" -> c
            "display-p3" -> xyzD65ToLinearSrgb(multiply(P3_TO_XYZ, c.map(::decodeSrgb)))
            "a98-rgb" -> xyzD65ToLinearSrgb(multiply(A98_TO_XYZ, c.map { sign(it) * abs(it).pow(563.0 / 256.0) }))
            "prophoto-rgb" -> xyzD65ToLinearSrgb(d50ToD65(multiply(PROPHOTO_TO_XYZ_D50, c.map(::decodeProphoto))))
            "rec2020" -> xyzD65ToLinearSrgb(multiply(REC2020_TO_XYZ, c.map(::decodeRec2020)))
            "xyz", "xyz-d65" -> xyzD65ToLinearSrgb(c)
            "xyz-d50" -> xyzD65ToLinearSrgb(d50ToD65(c))
            else -> return null
        }
        return Parsed(encodeSrgb(linear), a)
    }

    private fun hex(h: String): Parsed? {
        if (h.any { hexDigit(it) < 0 }) return null
        fun d(i: Int) = hexDigit(h[i])
        return when (h.length) {
            3, 4 -> Parsed(
                RgbColor(d(0) * 17 / 255.0, d(1) * 17 / 255.0, d(2) * 17 / 255.0),
                if (h.length == 4) d(3) * 17 / 255.0 else 1.0,
            )
            6, 8 -> Parsed(
                RgbColor((d(0) * 16 + d(1)) / 255.0, (d(2) * 16 + d(3)) / 255.0, (d(4) * 16 + d(5)) / 255.0),
                if (h.length == 8) (d(6) * 16 + d(7)) / 255.0 else 1.0,
            )
            else -> null
        }
    }

    private fun hexDigit(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        else -> -1
    }

    // ---- conversions, after the sample code of CSS Color 4, 18 ----

    private fun hslToRgb(hueDegrees: Double, s: Double, l: Double): Triple<Double, Double, Double> {
        val h = ((hueDegrees % 360.0) + 360.0) % 360.0
        fun f(n: Double): Double {
            val k = (n + h / 30.0) % 12.0
            val a = s * minOf(l, 1 - l)
            return l - a * maxOf(-1.0, minOf(k - 3, 9 - k, 1.0))
        }
        return Triple(f(0.0), f(8.0), f(4.0))
    }

    private fun labToXyzD50(l: Double, a: Double, b: Double): List<Double> {
        val kappa = 24389.0 / 27.0
        val epsilon = 216.0 / 24389.0
        val f1 = (l + 16) / 116
        val f0 = a / 500 + f1
        val f2 = f1 - b / 200
        val x = if (f0 * f0 * f0 > epsilon) f0 * f0 * f0 else (116 * f0 - 16) / kappa
        val y = if (l > kappa * epsilon) f1 * f1 * f1 else l / kappa
        val z = if (f2 * f2 * f2 > epsilon) f2 * f2 * f2 else (116 * f2 - 16) / kappa
        return listOf(x * D50[0], y * D50[1], z * D50[2])
    }

    private fun oklabToLinearSrgb(l: Double, a: Double, b: Double): List<Double> {
        val lp = l + 0.3963377773761749 * a + 0.2158037573099136 * b
        val mp = l - 0.1055613458156586 * a - 0.0638541728258133 * b
        val sp = l - 0.0894841775298119 * a - 1.2914855480194092 * b
        val lms = listOf(lp * lp * lp, mp * mp * mp, sp * sp * sp)
        return xyzD65ToLinearSrgb(multiply(LMS_TO_XYZ, lms))
    }

    private fun d50ToD65(xyz: List<Double>): List<Double> = multiply(D50_TO_D65, xyz)

    private fun xyzD65ToLinearSrgb(xyz: List<Double>): List<Double> = multiply(XYZ_TO_SRGB, xyz)

    private fun decodeSrgb(v: Double): Double = if (abs(v) <= 0.04045) v / 12.92 else sign(v) * ((abs(v) + 0.055) / 1.055).pow(2.4)

    private fun encodeSrgb(v: Double): Double = if (abs(v) > 0.0031308) sign(v) * (1.055 * abs(v).pow(1 / 2.4) - 0.055) else 12.92 * v

    private fun encodeSrgb(linear: List<Double>): RgbColor = clip(encodeSrgb(linear[0]), encodeSrgb(linear[1]), encodeSrgb(linear[2]))

    private fun decodeProphoto(v: Double): Double = if (abs(v) <= 16.0 / 512.0) v / 16.0 else sign(v) * abs(v).pow(1.8)

    private fun decodeRec2020(v: Double): Double {
        val alpha = 1.09929682680944
        val beta = 0.018053968510807
        return if (abs(v) < beta * 4.5) v / 4.5 else sign(v) * ((abs(v) + alpha - 1) / alpha).pow(1 / 0.45)
    }

    private fun clip(r: Double, g: Double, b: Double) = RgbColor(r.coerceIn(0.0, 1.0), g.coerceIn(0.0, 1.0), b.coerceIn(0.0, 1.0))

    private fun multiply(m: Array<DoubleArray>, v: List<Double>): List<Double> =
        m.map { row -> row[0] * v[0] + row[1] * v[1] + row[2] * v[2] }

    private fun rgb(packed: Int) = RgbColor((packed shr 16 and 0xFF) / 255.0, (packed shr 8 and 0xFF) / 255.0, (packed and 0xFF) / 255.0)

    private val NUMBER = Regex("[+-]?(\\d+\\.?\\d*|\\.\\d+)(e[+-]?\\d+)?")

    private val ANGLE_UNITS = listOf("grad" to 0.9, "turn" to 360.0, "deg" to 1.0, "rad" to 180.0 / PI)

    private val D50 = doubleArrayOf(0.3457 / 0.3585, 1.0, (1.0 - 0.3457 - 0.3585) / 0.3585)

    private val XYZ_TO_SRGB = arrayOf(
        doubleArrayOf(12831.0 / 3959.0, -329.0 / 214.0, -1974.0 / 3959.0),
        doubleArrayOf(-851781.0 / 878810.0, 1648619.0 / 878810.0, 36519.0 / 878810.0),
        doubleArrayOf(705.0 / 12673.0, -2585.0 / 12673.0, 705.0 / 667.0),
    )

    private val D50_TO_D65 = arrayOf(
        doubleArrayOf(0.955473421488075, -0.02309845494876471, 0.06325924320057072),
        doubleArrayOf(-0.0283697093338637, 1.0099953980813041, 0.021041441191917323),
        doubleArrayOf(0.012314014864481998, -0.020507649298898964, 1.330365926242124),
    )

    private val LMS_TO_XYZ = arrayOf(
        doubleArrayOf(1.2268798758459243, -0.5578149944602171, 0.2813910456659647),
        doubleArrayOf(-0.0405757452148008, 1.1122868032803170, -0.0717110580655164),
        doubleArrayOf(-0.0763729366746601, -0.4214933324022432, 1.5869240198367816),
    )

    private val P3_TO_XYZ = arrayOf(
        doubleArrayOf(608311.0 / 1250200.0, 189793.0 / 714400.0, 198249.0 / 1000160.0),
        doubleArrayOf(35783.0 / 156275.0, 247089.0 / 357200.0, 198249.0 / 2500400.0),
        doubleArrayOf(0.0, 32229.0 / 714400.0, 5220557.0 / 5000800.0),
    )

    private val A98_TO_XYZ = arrayOf(
        doubleArrayOf(573536.0 / 994567.0, 263643.0 / 1420810.0, 187206.0 / 994567.0),
        doubleArrayOf(591459.0 / 1989134.0, 6239551.0 / 9945670.0, 374412.0 / 4972835.0),
        doubleArrayOf(53769.0 / 1989134.0, 351524.0 / 4972835.0, 4929758.0 / 4972835.0),
    )

    private val PROPHOTO_TO_XYZ_D50 = arrayOf(
        doubleArrayOf(0.7977666449006423, 0.13518129740053308, 0.0313477341283922858),
        doubleArrayOf(0.2880748288194013, 0.711835234241873, 0.00008993693872564),
        doubleArrayOf(0.0, 0.0, 0.8251046025104602),
    )

    private val REC2020_TO_XYZ = arrayOf(
        doubleArrayOf(63426534.0 / 99577255.0, 20160776.0 / 139408157.0, 47086771.0 / 278816314.0),
        doubleArrayOf(26158966.0 / 99577255.0, 472592308.0 / 697040785.0, 8267143.0 / 139408157.0),
        doubleArrayOf(0.0, 19567812.0 / 697040785.0, 295819943.0 / 278816314.0),
    )

    /** The 148 named colours of CSS Color 4, 6.1. */
    private val NAMED: Map<String, Int> = mapOf(
        "aliceblue" to 0xf0f8ff, "antiquewhite" to 0xfaebd7, "aqua" to 0x00ffff, "aquamarine" to 0x7fffd4, "azure" to 0xf0ffff,
        "beige" to 0xf5f5dc, "bisque" to 0xffe4c4, "black" to 0x000000, "blanchedalmond" to 0xffebcd, "blue" to 0x0000ff,
        "blueviolet" to 0x8a2be2, "brown" to 0xa52a2a, "burlywood" to 0xdeb887, "cadetblue" to 0x5f9ea0, "chartreuse" to 0x7fff00,
        "chocolate" to 0xd2691e, "coral" to 0xff7f50, "cornflowerblue" to 0x6495ed, "cornsilk" to 0xfff8dc, "crimson" to 0xdc143c,
        "cyan" to 0x00ffff, "darkblue" to 0x00008b, "darkcyan" to 0x008b8b, "darkgoldenrod" to 0xb8860b, "darkgray" to 0xa9a9a9,
        "darkgreen" to 0x006400, "darkgrey" to 0xa9a9a9, "darkkhaki" to 0xbdb76b, "darkmagenta" to 0x8b008b, "darkolivegreen" to 0x556b2f,
        "darkorange" to 0xff8c00, "darkorchid" to 0x9932cc, "darkred" to 0x8b0000, "darksalmon" to 0xe9967a, "darkseagreen" to 0x8fbc8f,
        "darkslateblue" to 0x483d8b, "darkslategray" to 0x2f4f4f, "darkslategrey" to 0x2f4f4f, "darkturquoise" to 0x00ced1,
        "darkviolet" to 0x9400d3, "deeppink" to 0xff1493, "deepskyblue" to 0x00bfff, "dimgray" to 0x696969, "dimgrey" to 0x696969,
        "dodgerblue" to 0x1e90ff, "firebrick" to 0xb22222, "floralwhite" to 0xfffaf0, "forestgreen" to 0x228b22, "fuchsia" to 0xff00ff,
        "gainsboro" to 0xdcdcdc, "ghostwhite" to 0xf8f8ff, "gold" to 0xffd700, "goldenrod" to 0xdaa520, "gray" to 0x808080,
        "green" to 0x008000, "greenyellow" to 0xadff2f, "grey" to 0x808080, "honeydew" to 0xf0fff0, "hotpink" to 0xff69b4,
        "indianred" to 0xcd5c5c, "indigo" to 0x4b0082, "ivory" to 0xfffff0, "khaki" to 0xf0e68c, "lavender" to 0xe6e6fa,
        "lavenderblush" to 0xfff0f5, "lawngreen" to 0x7cfc00, "lemonchiffon" to 0xfffacd, "lightblue" to 0xadd8e6, "lightcoral" to 0xf08080,
        "lightcyan" to 0xe0ffff, "lightgoldenrodyellow" to 0xfafad2, "lightgray" to 0xd3d3d3, "lightgreen" to 0x90ee90,
        "lightgrey" to 0xd3d3d3, "lightpink" to 0xffb6c1, "lightsalmon" to 0xffa07a, "lightseagreen" to 0x20b2aa,
        "lightskyblue" to 0x87cefa, "lightslategray" to 0x778899, "lightslategrey" to 0x778899, "lightsteelblue" to 0xb0c4de,
        "lightyellow" to 0xffffe0, "lime" to 0x00ff00, "limegreen" to 0x32cd32, "linen" to 0xfaf0e6, "magenta" to 0xff00ff,
        "maroon" to 0x800000, "mediumaquamarine" to 0x66cdaa, "mediumblue" to 0x0000cd, "mediumorchid" to 0xba55d3,
        "mediumpurple" to 0x9370db, "mediumseagreen" to 0x3cb371, "mediumslateblue" to 0x7b68ee, "mediumspringgreen" to 0x00fa9a,
        "mediumturquoise" to 0x48d1cc, "mediumvioletred" to 0xc71585, "midnightblue" to 0x191970, "mintcream" to 0xf5fffa,
        "mistyrose" to 0xffe4e1, "moccasin" to 0xffe4b5, "navajowhite" to 0xffdead, "navy" to 0x000080, "oldlace" to 0xfdf5e6,
        "olive" to 0x808000, "olivedrab" to 0x6b8e23, "orange" to 0xffa500, "orangered" to 0xff4500, "orchid" to 0xda70d6,
        "palegoldenrod" to 0xeee8aa, "palegreen" to 0x98fb98, "paleturquoise" to 0xafeeee, "palevioletred" to 0xdb7093,
        "papayawhip" to 0xffefd5, "peachpuff" to 0xffdab9, "peru" to 0xcd853f, "pink" to 0xffc0cb, "plum" to 0xdda0dd,
        "powderblue" to 0xb0e0e6, "purple" to 0x800080, "rebeccapurple" to 0x663399, "red" to 0xff0000, "rosybrown" to 0xbc8f8f,
        "royalblue" to 0x4169e1, "saddlebrown" to 0x8b4513, "salmon" to 0xfa8072, "sandybrown" to 0xf4a460, "seagreen" to 0x2e8b57,
        "seashell" to 0xfff5ee, "sienna" to 0xa0522d, "silver" to 0xc0c0c0, "skyblue" to 0x87ceeb, "slateblue" to 0x6a5acd,
        "slategray" to 0x708090, "slategrey" to 0x708090, "snow" to 0xfffafa, "springgreen" to 0x00ff7f, "steelblue" to 0x4682b4,
        "tan" to 0xd2b48c, "teal" to 0x008080, "thistle" to 0xd8bfd8, "tomato" to 0xff6347, "turquoise" to 0x40e0d0,
        "violet" to 0xee82ee, "wheat" to 0xf5deb3, "white" to 0xffffff, "whitesmoke" to 0xf5f5f5, "yellow" to 0xffff00,
        "yellowgreen" to 0x9acd32,
    )

    /** The names of [NAMED], for a test that checks each against a browser. */
    val names: Set<String> get() = NAMED.keys
}
