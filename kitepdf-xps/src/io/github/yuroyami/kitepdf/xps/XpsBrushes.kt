package io.github.yuroyami.kitepdf.xps

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.css.CssValues
import io.github.yuroyami.kitepdf.core.kiteWarn
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteColorSpace
import io.github.yuroyami.kitepdf.core.render.KiteFunction
import io.github.yuroyami.kitepdf.core.render.KiteGradientSpread
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.KiteShading
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.render.SoftMask
import io.github.yuroyami.kitepdf.core.render.spreadOver
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

internal data class XpsColor(val color: RgbColor, val alpha: Double)

/** Solid, image, visual and gradient brushes, ECMA-388 §13; colours, §15. */
internal class XpsBrushes(
    private val packageData: XpsPackage,
    private val budget: XpsRenderBudget,
    private val renderVisual: (XpsResource, KiteCanvas, KiteMatrix, XpsResources, Int) -> Unit,
) {
    /** A decoded image brush, and its size in XPS units at the image's own resolution. */
    internal data class Image(val data: KiteImageData, val width: Double, val height: Double)

    /** Image parts that did not decode in this render, so a brush used again does not try again. */
    private val unreadable = HashSet<String>()

    fun solid(resource: XpsResource): XpsColor? {
        if (resource.node.tag != "solidcolorbrush") return null
        val color = xpsColor(resource.node.attrs["color"].orEmpty()) ?: return null
        return color.copy(alpha = color.alpha * resource.node.number("opacity", 1.0).coerceIn(0.0, 1.0))
    }

    /** One colour for [resource] where only a solid colour can paint: its first gradient stop, or black for an image. */
    fun representative(resource: XpsResource): XpsColor {
        solid(resource)?.let { return it }
        val node = resource.node
        val alpha = node.number("opacity", 1.0).coerceIn(0.0, 1.0)
        val holder = node.elements().firstOrNull { it.tag == "${node.tag}.gradientstops" }
        val first = (holder?.elements() ?: node.elements()).filter { it.tag == "gradientstop" }
            .minByOrNull { it.number("offset", 0.0) }?.let { xpsColor(it.attrs["color"].orEmpty()) }
        return first?.let { it.copy(alpha = it.alpha * alpha) } ?: XpsColor(RgbColor.BLACK, alpha)
    }

    fun fill(
        resource: XpsResource, path: KitePath, evenOdd: Boolean, canvas: KiteCanvas,
        ctm: KiteMatrix, scope: XpsResources, depth: Int,
    ) {
        if (depth > 64 || path.isEmpty() || !budget.take()) return
        solid(resource)?.let { canvas.fillPath(path, ctm, it.color, evenOdd, it.alpha); return }
        canvas.pushClip(path, ctm, evenOdd)
        try {
            val node = resource.node
            val brushTransform = transform(node, scope)
            val brushCtm = ctm.concat(brushTransform)
            val brushBox = brushTransform.invert()?.let { path.bounds(it) } ?: path.bounds() ?: return
            val alpha = node.number("opacity", 1.0).coerceIn(0.0, 1.0)
            when (node.tag) {
                "imagebrush", "visualbrush" -> tiles(resource, brushBox, canvas, brushCtm, scope, alpha, depth)
                "lineargradientbrush", "radialgradientbrush" -> gradient(resource, brushBox, canvas, brushCtm, alpha)
            }
        } finally { canvas.popClip() }
    }

    private fun image(resource: XpsResource): Image? {
        var source = resource.node.attrs["imagesource"] ?: return null
        // ECMA-388: {ColorConvertedBitmap image profile} names the ICC profile of the image's
        // samples, which takes the place of a profile in the image file (#895).
        var profilePart: String? = null
        if (source.startsWith("{ColorConvertedBitmap ")) {
            val operands = source.removePrefix("{ColorConvertedBitmap ").removeSuffix("}").trim().split(Regex("\\s+"))
            source = operands[0]
            profilePart = operands.getOrNull(1)?.let { resolvePart(resource.base, it) }
        }
        val part = resolvePart(resource.base, source) ?: return null
        val key = if (profilePart == null) part else "$part $profilePart"
        if (key in unreadable) return null
        // The package keeps decoded brushes across renders, so a page drawn again does not decode again (#385).
        return packageData.image(key) {
            runCatching {
                val bytes = packageData.read(part) ?: return@runCatching null
                // An XPS image brush places its image by its own viewbox, with the samples as stored.
                val stored = KiteImageData.fromEncodedImage(bytes, applyOrientation = false) ?: return@runCatching null
                val profile = profilePart?.let { packageData.read(it) }
                if (profilePart != null && profile == null) kiteWarn { "xps: the colour profile $profilePart of $part is not in the package" }
                val decoded = profile?.let { stored.withIccProfile(it) } ?: stored
                val dpi = imageDpi(bytes)
                Image(decoded, decoded.width * 96.0 / dpi.first, decoded.height * 96.0 / dpi.second)
            }.getOrNull()
        } ?: run {
            unreadable += key
            null
        }
    }

    private fun tiles(
        resource: XpsResource, box: KiteRectangle, canvas: KiteCanvas, ctm: KiteMatrix,
        scope: XpsResources, alpha: Double, depth: Int,
    ) {
        val node = resource.node
        val image = if (node.tag == "imagebrush") image(resource) else null
        if (node.tag == "imagebrush" && image == null) {
            kiteWarn { "xps: unreadable image brush" }
            canvas.fillPath(rectangle(box), ctm, RgbColor(0.9, 0.9, 0.9), false, alpha)
            return
        }
        val visual = if (node.tag == "visualbrush") {
            node.attrs["visual"]?.let(::resourceKey)?.let(scope::get)
                ?: node.elements().firstOrNull { it.tag == "visualbrush.visual" }?.elements()?.firstOrNull()
                    ?.let { XpsResource(it, resource.base) }
        } else null
        val vb = node.attrs["viewbox"]?.let(::numbers)?.takeIf { it.size == 4 }
            ?: listOf(0.0, 0.0, image?.width ?: box.width, image?.height ?: box.height)
        val vp = node.attrs["viewport"]?.let(::numbers)?.takeIf { it.size == 4 }
            ?: listOf(box.left, box.bottom, box.width, box.height)
        if (vb[2] <= 0 || vb[3] <= 0 || vp[2] <= 0 || vp[3] <= 0) return
        val mode = node.attrs["tilemode"] ?: "None"
        val minX = if (mode == "None") 0 else floor((box.left - vp[0]) / vp[2]).toInt().coerceIn(-4096, 4096)
        val minY = if (mode == "None") 0 else floor((box.bottom - vp[1]) / vp[3]).toInt().coerceIn(-4096, 4096)
        val maxX = if (mode == "None") 0 else ceil((box.right - vp[0]) / vp[2]).toInt().coerceIn(-4096, 4096)
        val maxY = if (mode == "None") 0 else ceil((box.top - vp[1]) / vp[3]).toInt().coerceIn(-4096, 4096)
        var tiles = 0
        for (y in minY..maxY) for (x in minX..maxX) {
            if (++tiles > 4096 || !budget.take()) { kiteWarn { "xps: image/visual brush tile limit" }; return }
            val left = vp[0] + x * vp[2]
            val top = vp[1] + y * vp[3]
            val flipX = (mode == "FlipX" || mode == "FlipXY") && x and 1 != 0
            val flipY = (mode == "FlipY" || mode == "FlipXY") && y and 1 != 0
            val sx = vp[2] / vb[2] * if (flipX) -1 else 1
            val sy = vp[3] / vb[3] * if (flipY) -1 else 1
            val mapping = KiteMatrix(sx, 0.0, 0.0, sy,
                left + (if (flipX) vp[2] else 0.0) - vb[0] * sx,
                top + (if (flipY) vp[3] else 0.0) - vb[1] * sy)
            val tileBox = KiteRectangle(left, top, left + vp[2], top + vp[3])
            canvas.pushClip(rectangle(tileBox), ctm, false)
            try {
                if (image != null) {
                    canvas.drawImage(image.data, ctm.concat(mapping).concat(
                        KiteMatrix(image.width, 0.0, 0.0, -image.height, 0.0, image.height)), alpha)
                } else if (visual != null) {
                    if (alpha < 1) canvas.beginTransparencyGroup(tileBox, ctm, isolated = true, alpha = alpha)
                    try { renderVisual(visual, canvas, ctm.concat(mapping), scope, depth + 1) }
                    finally { if (alpha < 1) canvas.endTransparencyGroup() }
                }
            } finally { canvas.popClip() }
        }
    }

    private data class Stop(val offset: Double, val color: XpsColor)

    private fun gradient(resource: XpsResource, box: KiteRectangle, canvas: KiteCanvas, ctm: KiteMatrix, alpha: Double) {
        val node = resource.node
        val holder = node.elements().firstOrNull { it.tag == "${node.tag}.gradientstops" }
        var stops = (holder?.elements() ?: node.elements()).filter { it.tag == "gradientstop" }.mapNotNull {
            val color = xpsColor(it.attrs["color"].orEmpty()) ?: return@mapNotNull null
            Stop(it.number("offset", 0.0).coerceIn(0.0, 1.0), color)
        // Stops at one offset stay in markup order, so two of them make a hard edge, as WPF and
        // SVG 1.1, 13.2.4 draw it (#749). sortedBy is stable.
        }.sortedBy { it.offset }
        if (stops.isEmpty()) return
        if (stops.first().offset > 0) stops = listOf(stops.first().copy(offset = 0.0)) + stops
        if (stops.last().offset < 1) stops = stops + stops.last().copy(offset = 1.0)
        if (stops.size == 1) {
            canvas.fillPath(rectangle(box), ctm, stops[0].color.color, false, alpha * stops[0].color.alpha)
            return
        }
        val spread = when (node.attrs["spreadmethod"]) {
            "Reflect" -> KiteGradientSpread.Reflect
            "Repeat" -> KiteGradientSpread.Repeat
            else -> KiteGradientSpread.Pad
        }
        // ScRGB interpolation is linear light. Sample back into display sRGB;
        // native canvas gradients otherwise interpolate encoded components.
        if (node.attrs["colorinterpolationmode"] == "ScRgbLinearInterpolation") {
            val originals = stops
            stops = (0..128).map { i ->
                val offset = i / 128.0
                val next = originals.indexOfFirst { it.offset >= offset }.coerceAtLeast(1)
                val a = originals[next - 1]; val b = originals[next]
                val span = b.offset - a.offset
                val t = if (span > 0.0) ((offset - a.offset) / span).coerceIn(0.0, 1.0) else 1.0
                fun channel(from: Double, to: Double) = encodeSrgb(decodeSrgb(from) * (1 - t) + decodeSrgb(to) * t)
                Stop(offset, XpsColor(RgbColor(channel(a.color.color.r, b.color.color.r),
                    channel(a.color.color.g, b.color.color.g), channel(a.color.color.b, b.color.color.b)),
                    a.color.alpha * (1 - t) + b.color.alpha * t))
            }
        }
        fun function(mask: Boolean): KiteFunction {
            fun channels(stop: Stop): DoubleArray = if (mask) DoubleArray(3) { stop.color.alpha } else {
                val c = stop.color.color; doubleArrayOf(c.r, c.g, c.b)
            }
            return KiteFunction.Type3(doubleArrayOf(0.0, 1.0), null,
                stops.zipWithNext { a, b -> KiteFunction.Type2(doubleArrayOf(0.0, 1.0), null, channels(a), channels(b), 1.0) },
                stops.drop(1).dropLast(1).map { it.offset }.toDoubleArray(),
                DoubleArray((stops.size - 1) * 2) { if (it % 2 == 0) 0.0 else 1.0 })
        }
        var gradientCtm = ctm
        // The painted region in the space of the gradient's coordinates, for the spread method.
        var region = box
        val coords = if (node.tag == "lineargradientbrush") {
            val start = numbers(node.attrs["startpoint"].orEmpty()).takeIf { it.size == 2 } ?: listOf(0.0, 0.0)
            val end = numbers(node.attrs["endpoint"].orEmpty()).takeIf { it.size == 2 } ?: listOf(1.0, 1.0)
            doubleArrayOf(start[0], start[1], end[0], end[1])
        } else {
            val center = numbers(node.attrs["center"].orEmpty()).takeIf { it.size == 2 } ?: listOf(0.0, 0.0)
            val origin = numbers(node.attrs["gradientorigin"].orEmpty()).takeIf { it.size == 2 } ?: center
            val rx = node.number("radiusx", 1.0); val ry = node.number("radiusy", 1.0)
            if (rx <= 0 || ry <= 0) return
            val radiusScale = ry / rx
            gradientCtm = ctm.concat(KiteMatrix.scaling(1.0, radiusScale))
            region = KiteRectangle(box.left, box.bottom / radiusScale, box.right, box.top / radiusScale)
            doubleArrayOf(origin[0], origin[1] / radiusScale, 0.0, center[0], center[1] / radiusScale, rx)
        }
        fun shading(mask: Boolean): KiteShading = if (coords.size == 4) {
            KiteShading.Axial(KiteColorSpace.DeviceRGB, null, null, coords, doubleArrayOf(0.0, 1.0), function(mask), true, true)
        } else {
            KiteShading.Radial(KiteColorSpace.DeviceRGB, null, null, coords, doubleArrayOf(0.0, 1.0), function(mask), true, true)
        }.spreadOver(spread, region)
        val color = shading(false)
        if (stops.any { it.color.alpha < 1 }) {
            // Luminosity converts the grayscale alpha ramp to a soft mask.
            canvas.applySoftMask(SoftMask.Kind.Luminosity, box, ctm,
                { canvas.fillShading(color, gradientCtm, null, alpha) },
                { it.fillShading(shading(true), gradientCtm, null) })
        } else canvas.fillShading(color, gradientCtm, null, alpha)
    }
}

internal fun xpsColor(raw: String): XpsColor? {
    val value = raw.trim()
    if (value.startsWith("sc#", true)) {
        val c = numbers(value.substring(3))
        if (c.size !in 3..4) return null
        val offset = if (c.size == 4) 1 else 0
        return XpsColor(RgbColor(encodeSrgb(c[offset]), encodeSrgb(c[offset + 1]), encodeSrgb(c[offset + 2])),
            if (offset == 1) c[0].coerceIn(0.0, 1.0) else 1.0)
    }
    if (value.startsWith('#') && value.length in listOf(5, 9)) {
        val hex = value.substring(1)
        val alpha = if (hex.length == 4) hex.take(1).repeat(2) else hex.take(2)
        val color = CssValues.color("#" + hex.drop(if (hex.length == 4) 1 else 2)) ?: return null
        return XpsColor(color, (alpha.toIntOrNull(16) ?: return null) / 255.0)
    }
    if (value.startsWith("ContextColor", ignoreCase = true)) return contextColor(value)
    return CssValues.color(value)?.let { XpsColor(it, 1.0) }
}

/**
 * `ContextColor <profile> <alpha>,<c1>,...` (ECMA-388, 15.1): without colour management the values
 * map by their count, as MuPDF maps them: one is gray, three RGB and four CMYK (#743).
 */
private fun contextColor(value: String): XpsColor? {
    val rest = value.substring("ContextColor".length).trim()
    val space = rest.indexOfFirst { it == ' ' || it == '\t' }
    if (space < 0) return null
    val parts = rest.substring(space + 1).split(',').map { it.trim().toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null }
    if (parts.size < 2) return null
    val alpha = parts[0].coerceIn(0.0, 1.0)
    val c = parts.drop(1).map { it.coerceIn(0.0, 1.0) }.toDoubleArray()
    val rgb = when (c.size) {
        1 -> RgbColor(c[0], c[0], c[0])
        3 -> RgbColor(c[0], c[1], c[2])
        4 -> KiteColorSpace.DeviceCMYK.toRgb(c)
        else -> return null
    }
    return XpsColor(rgb, alpha)
}

private fun encodeSrgb(v: Double): Double = (if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1.0 / 2.4) - 0.055).coerceIn(0.0, 1.0)
private fun decodeSrgb(v: Double): Double = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

/** PNG pHYs and JPEG JFIF density, ECMA-388 §13.4.1; absent density means 96 dpi. */
internal fun imageDpi(bytes: ByteArray): Pair<Double, Double> {
    fun u16(at: Int) = ((bytes[at].toInt() and 255) shl 8) or (bytes[at + 1].toInt() and 255)
    fun u32(at: Int): Long {
        var value = 0L
        for (i in at until at + 4) value = (value shl 8) or (bytes[i].toLong() and 255)
        return value
    }
    if (bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes.decodeToString(1, 4) == "PNG") {
        var at = 8
        while (at + 12 <= bytes.size) {
            val size = u32(at)
            if (size > bytes.size - at - 12) break
            if (bytes.decodeToString(at + 4, at + 8) == "pHYs" && size == 9L && bytes[at + 16] == 1.toByte()) {
                val x = u32(at + 8) * 0.0254; val y = u32(at + 12) * 0.0254
                if (x > 0 && y > 0) return x to y
            }
            at += size.toInt() + 12
        }
    } else if (bytes.size > 8 && (bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() || bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte())) {
        // A TIFF's XResolution, YResolution and ResolutionUnit tags (#752).
        tiffDpi(bytes, 0, bytes.size)?.let { return it }
    } else if (bytes.size > 20 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
        // The JFIF density wins; a camera JPEG has only the TIFF tags of its EXIF segment (#752).
        var exif: Pair<Double, Double>? = null
        var at = 2
        while (at + 4 <= bytes.size && bytes[at] == 0xFF.toByte()) {
            val marker = bytes[at + 1].toInt() and 255
            if (marker == 0xDA || marker == 0xD9) break
            val size = u16(at + 2)
            if (size < 2 || at + 2L + size > bytes.size) break
            if (marker == 0xE0 && size >= 16 && bytes.decodeToString(at + 4, at + 8) == "JFIF") {
                val unit = bytes[at + 11].toInt()
                val factor = when (unit) { 1 -> 1.0; 2 -> 2.54; else -> 0.0 }
                val x = u16(at + 12) * factor; val y = u16(at + 14) * factor
                if (x > 0 && y > 0) return x to y
            }
            if (marker == 0xE1 && exif == null && size >= 16 && bytes.decodeToString(at + 4, at + 8) == "Exif") {
                exif = tiffDpi(bytes, at + 10, at + 2 + size)
            }
            at += size + 2
        }
        exif?.let { return it }
    }
    return 96.0 to 96.0
}

/**
 * The resolution of the TIFF structure at [base] up to [end] of [bytes], from its first image file
 * directory: XResolution (282) and YResolution (283), rationals, in the ResolutionUnit (296) of 2,
 * inches, the default, or 3, centimetres. Null when it says none.
 */
private fun tiffDpi(bytes: ByteArray, base: Int, end: Int): Pair<Double, Double>? {
    if (base < 0 || end > bytes.size || end - base < 8) return null
    val le = bytes[base] == 'I'.code.toByte()
    fun u16(at: Int): Int {
        val a = bytes[at].toInt() and 255; val b = bytes[at + 1].toInt() and 255
        return if (le) a or (b shl 8) else (a shl 8) or b
    }
    fun u32(at: Int): Long {
        var v = 0L
        for (k in 0 until 4) v = if (le) v or ((bytes[at + k].toLong() and 255) shl (8 * k)) else (v shl 8) or (bytes[at + k].toLong() and 255)
        return v
    }
    val ifd = base + u32(base + 4)
    if (ifd < base + 8 || ifd + 2 > end) return null
    val count = u16(ifd.toInt())
    var x = 0.0; var y = 0.0; var unit = 2
    for (i in 0 until count) {
        val e = ifd.toInt() + 2 + i * 12
        if (e + 12 > end) break
        val tag = u16(e)
        when (tag) {
            282, 283 -> {
                val at = base + u32(e + 8)
                if (at < base || at + 8 > end) continue
                val den = u32(at.toInt() + 4)
                val v = if (den == 0L) 0.0 else u32(at.toInt()).toDouble() / den
                if (tag == 282) x = v else y = v
            }
            296 -> unit = u16(e + 8)
        }
    }
    val factor = when (unit) { 2 -> 1.0; 3 -> 2.54; else -> return null }
    return if (x > 0 && y > 0) (x * factor) to (y * factor) else null
}
