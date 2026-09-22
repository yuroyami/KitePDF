package io.github.yuroyami.kitepdf.xps

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.css.CssValues
import io.github.yuroyami.kitepdf.core.kiteWarn
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteColorSpace
import io.github.yuroyami.kitepdf.core.render.KiteFunction
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.KiteShading
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.render.SoftMask
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
    private data class Image(val data: KiteImageData, val width: Double, val height: Double)
    private val images = HashMap<String, Image?>()

    fun solid(resource: XpsResource): XpsColor? {
        if (resource.node.tag != "solidcolorbrush") return null
        val color = xpsColor(resource.node.attrs["color"].orEmpty()) ?: return null
        return color.copy(alpha = color.alpha * resource.node.number("opacity", 1.0).coerceIn(0.0, 1.0))
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
            val brushBox = brushTransform.invert()?.let { bounds(transformPath(path, it)) } ?: bounds(path) ?: return
            val alpha = node.number("opacity", 1.0).coerceIn(0.0, 1.0)
            when (node.tag) {
                "imagebrush", "visualbrush" -> tiles(resource, brushBox, canvas, brushCtm, scope, alpha, depth)
                "lineargradientbrush", "radialgradientbrush" -> gradient(resource, brushBox, canvas, brushCtm, alpha)
            }
        } finally { canvas.popClip() }
    }

    private fun image(resource: XpsResource): Image? {
        var source = resource.node.attrs["imagesource"] ?: return null
        if (source.startsWith("{ColorConvertedBitmap ")) source = source.removePrefix("{ColorConvertedBitmap ").substringBefore(' ')
        val part = resolvePart(resource.base, source) ?: return null
        if (images.containsKey(part)) return images[part]
        val image = runCatching {
            val bytes = packageData.read(part) ?: return@runCatching null
            val decoded = KiteImageData.fromEncodedImage(bytes) ?: return@runCatching null
            val dpi = imageDpi(bytes)
            Image(decoded, decoded.width * 96.0 / dpi.first, decoded.height * 96.0 / dpi.second)
        }.getOrNull()
        images[part] = image
        return image
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
        }.sortedBy { it.offset }.distinctBy { it.offset }
        if (stops.isEmpty()) return
        if (stops.first().offset > 0) stops = listOf(stops.first().copy(offset = 0.0)) + stops
        if (stops.last().offset < 1) stops = stops + stops.last().copy(offset = 1.0)
        if (stops.size == 1) {
            canvas.fillPath(rectangle(box), ctm, stops[0].color.color, false, alpha * stops[0].color.alpha)
            return
        }
        if (node.attrs["spreadmethod"] !in listOf(null, "Pad")) {
            kiteWarn { "xps: gradient repeat/reflect uses pad fallback" }
        }
        // ScRGB interpolation is linear light. Sample back into display sRGB;
        // native canvas gradients otherwise interpolate encoded components.
        if (node.attrs["colorinterpolationmode"] == "ScRgbLinearInterpolation") {
            val originals = stops
            stops = (0..128).map { i ->
                val offset = i / 128.0
                val next = originals.indexOfFirst { it.offset >= offset }.coerceAtLeast(1)
                val a = originals[next - 1]; val b = originals[next]
                val t = ((offset - a.offset) / (b.offset - a.offset)).coerceIn(0.0, 1.0)
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
            doubleArrayOf(origin[0], origin[1] / radiusScale, 0.0, center[0], center[1] / radiusScale, rx)
        }
        fun shading(mask: Boolean): KiteShading = if (coords.size == 4) {
            KiteShading.Axial(KiteColorSpace.DeviceRGB, null, null, coords, doubleArrayOf(0.0, 1.0), function(mask), true, true)
        } else KiteShading.Radial(KiteColorSpace.DeviceRGB, null, null, coords, doubleArrayOf(0.0, 1.0), function(mask), true, true)
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
    return CssValues.color(value)?.let { XpsColor(it, 1.0) }
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
    } else if (bytes.size > 20 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
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
            at += size + 2
        }
    }
    return 96.0 to 96.0
}
