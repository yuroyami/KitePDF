package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * feTurbulence (Filter Effects 1, 15.24): Perlin noise from the reference code of the spec,
 * seeded as it seeds it, so a seed gives the pattern every other renderer gives.
 */
internal class Turbulence(seed: Double) {
    private val lattice = IntArray(SIZE + SIZE + 2)

    /** The gradients of the four channels, two values each, channel after channel. */
    private val gradient = DoubleArray(4 * (SIZE + SIZE + 2) * 2)

    private fun g(channel: Int, index: Int, axis: Int): Double = gradient[(channel * (SIZE + SIZE + 2) + index) * 2 + axis]

    init {
        // The seed is truncated towards zero before it starts the generator.
        var s = setupSeed(if (seed.isFinite()) seed.toLong() else 0L)
        for (k in 0 until 4) {
            for (i in 0 until SIZE) {
                lattice[i] = i
                val at = (k * (SIZE + SIZE + 2) + i) * 2
                for (j in 0 until 2) {
                    s = random(s)
                    gradient[at + j] = ((s % (SIZE + SIZE)) - SIZE).toDouble() / SIZE
                }
                val length = sqrt(gradient[at] * gradient[at] + gradient[at + 1] * gradient[at + 1])
                if (length > 0.0) { gradient[at] /= length; gradient[at + 1] /= length }
            }
        }
        for (i in SIZE - 1 downTo 1) {
            val k = lattice[i]
            s = random(s)
            val j = (s % SIZE).toInt()
            lattice[i] = lattice[j]
            lattice[j] = k
        }
        for (i in 0 until SIZE + 2) {
            lattice[SIZE + i] = lattice[i]
            for (k in 0 until 4) for (j in 0 until 2) {
                gradient[(k * (SIZE + SIZE + 2) + SIZE + i) * 2 + j] = gradient[(k * (SIZE + SIZE + 2) + i) * 2 + j]
            }
        }
    }

    /**
     * The noise of one primitive: its base frequencies, adjusted when [tile] (x, y, width and
     * height in user space) is stitched so that the tile's borders meet, as the spec adjusts them.
     */
    inner class Field(baseX: Double, baseY: Double, private val octaves: Int, private val fractal: Boolean, tile: DoubleArray?) {
        private var fx = baseX
        private var fy = baseY
        private val stitched = tile != null
        private var width0 = 0
        private var height0 = 0
        private var wrapX0 = 0
        private var wrapY0 = 0

        init {
            if (tile != null) {
                val tw = tile[2]
                val th = tile[3]
                if (fx != 0.0) {
                    val lo = floor(tw * fx) / tw
                    val hi = ceil(tw * fx) / tw
                    fx = if (lo > 0.0 && fx / lo < hi / fx) lo else hi
                }
                if (fy != 0.0) {
                    val lo = floor(th * fy) / th
                    val hi = ceil(th * fy) / th
                    fy = if (lo > 0.0 && fy / lo < hi / fy) lo else hi
                }
                width0 = (tw * fx + 0.5).toInt()
                height0 = (th * fy + 0.5).toInt()
                wrapX0 = (tile[0] * fx + PERLIN_N + width0).toInt()
                wrapY0 = (tile[1] * fy + PERLIN_N + height0).toInt()
            }
        }

        /** The four channels of the noise at the point ([x], [y]) of user space into [out], each from 0 to 1. */
        fun at(x: Double, y: Double, out: DoubleArray) {
            out.fill(0.0)
            var vx = x * fx
            var vy = y * fy
            var ratio = 1.0
            var width = width0
            var height = height0
            var wrapX = wrapX0
            var wrapY = wrapY0
            for (octave in 0 until octaves) {
                var t = vx + PERLIN_N
                var bx0 = t.toInt() and MASK
                var bx1 = (bx0 + 1) and MASK
                val rx0 = t - t.toInt()
                val rx1 = rx0 - 1.0
                t = vy + PERLIN_N
                var by0 = t.toInt() and MASK
                var by1 = (by0 + 1) and MASK
                val ry0 = t - t.toInt()
                val ry1 = ry0 - 1.0
                if (stitched) {
                    if (bx0 >= wrapX) bx0 -= width
                    if (bx1 >= wrapX) bx1 -= width
                    if (by0 >= wrapY) by0 -= height
                    if (by1 >= wrapY) by1 -= height
                }
                bx0 = bx0 and MASK; bx1 = bx1 and MASK; by0 = by0 and MASK; by1 = by1 and MASK
                val i = lattice[bx0]
                val j = lattice[bx1]
                val b00 = lattice[i + by0]
                val b10 = lattice[j + by0]
                val b01 = lattice[i + by1]
                val b11 = lattice[j + by1]
                val sx = rx0 * rx0 * (3.0 - 2.0 * rx0)
                val sy = ry0 * ry0 * (3.0 - 2.0 * ry0)
                for (channel in 0 until 4) {
                    var u = rx0 * g(channel, b00, 0) + ry0 * g(channel, b00, 1)
                    var v = rx1 * g(channel, b10, 0) + ry0 * g(channel, b10, 1)
                    val a = u + sx * (v - u)
                    u = rx0 * g(channel, b01, 0) + ry1 * g(channel, b01, 1)
                    v = rx1 * g(channel, b11, 0) + ry1 * g(channel, b11, 1)
                    val b = u + sx * (v - u)
                    val n = a + sy * (b - a)
                    out[channel] += if (fractal) n / ratio else abs(n) / ratio
                }
                vx *= 2; vy *= 2; ratio *= 2
                width *= 2; wrapX = 2 * wrapX - PERLIN_N
                height *= 2; wrapY = 2 * wrapY - PERLIN_N
            }
            if (fractal) for (c in 0 until 4) out[c] = (out[c] + 1.0) / 2.0
        }
    }

    private companion object {
        const val SIZE = 0x100
        const val MASK = 0xff
        const val PERLIN_N = 0x1000
        const val RAND_M = 2147483647L
        const val RAND_A = 16807L
        const val RAND_Q = 127773L
        const val RAND_R = 2836L

        fun setupSeed(seed: Long): Long {
            var s = seed
            if (s <= 0) s = -(s % (RAND_M - 1)) + 1
            if (s > RAND_M - 1) s = RAND_M - 1
            return s
        }

        fun random(seed: Long): Long {
            var result = RAND_A * (seed % RAND_Q) - RAND_R * (seed / RAND_Q)
            if (result <= 0) result += RAND_M
            return result
        }
    }
}

/** A light source of feDiffuseLighting and feSpecularLighting (Filter Effects 1, 15.21). */
internal sealed class FilterLight {
    /** feDistantLight: light from [azimuth] and [elevation] degrees, the same at every pixel. */
    class Distant(val azimuth: Double, val elevation: Double) : FilterLight()

    /** fePointLight at ([x], [y], [z]) in pixels, z above the surface. */
    class Point(val x: Double, val y: Double, val z: Double) : FilterLight()

    /** feSpotLight at ([x], [y], [z]) pointing at ([px], [py], [pz]), in pixels. */
    class Spot(
        val x: Double, val y: Double, val z: Double, val px: Double, val py: Double, val pz: Double,
        val exponent: Double, val coneAngle: Double?,
    ) : FilterLight()
}

internal object Lighting {

    /**
     * The lit surface whose height is [surfaceScale] times the alpha of [src] (15.21 and 15.22).
     * [color] is the straight light colour in the image's space. Diffuse light gives an opaque
     * image; specular light takes the greatest of its channels as alpha.
     */
    fun light(
        src: FilterImage, light: FilterLight, color: FloatArray, surfaceScale: Double,
        specular: Boolean, constant: Double, exponent: Double,
    ): FilterImage {
        val w = src.width
        val h = src.height
        val out = FilterImage(w, h, src.linear)
        val s = src.data
        fun alpha(x: Int, y: Int) = s[(y * w + x) * 4 + 3].toDouble()
        val distant = (light as? FilterLight.Distant)?.let {
            val az = it.azimuth * kotlin.math.PI / 180
            val el = it.elevation * kotlin.math.PI / 180
            doubleArrayOf(cos(az) * cos(el), sin(az) * cos(el), sin(el))
        }
        val spotAxis = (light as? FilterLight.Spot)?.let {
            val dx = it.px - it.x; val dy = it.py - it.y; val dz = it.pz - it.z
            val length = sqrt(dx * dx + dy * dy + dz * dz)
            if (length > 0.0) doubleArrayOf(dx / length, dy / length, dz / length) else doubleArrayOf(0.0, 0.0, 0.0)
        }
        for (y in 0 until h) for (x in 0 until w) {
            // The Sobel normal of the spec: at an edge the missing side is the pixel itself, with the matching factor.
            val left = maxOf(x - 1, 0); val right = minOf(x + 1, w - 1)
            val up = maxOf(y - 1, 0); val down = minOf(y + 1, h - 1)
            var gx = 0.0; var wy = 0.0
            for (yy in up..down) {
                val weight = if (yy == y) 2.0 else 1.0
                gx += weight * (alpha(right, yy) - alpha(left, yy)); wy += weight
            }
            var gy = 0.0; var wx = 0.0
            for (xx in left..right) {
                val weight = if (xx == x) 2.0 else 1.0
                gy += weight * (alpha(xx, down) - alpha(xx, up)); wx += weight
            }
            val dX = (right - left).coerceAtLeast(1)
            val dY = (down - up).coerceAtLeast(1)
            var nx = -surfaceScale * 2.0 / (wy * dX) * gx
            var ny = -surfaceScale * 2.0 / (wx * dY) * gy
            var nz = 1.0
            val nLength = sqrt(nx * nx + ny * ny + nz * nz)
            nx /= nLength; ny /= nLength; nz /= nLength
            val surfaceZ = surfaceScale * alpha(x, y)
            var lx: Double; var ly: Double; var lz: Double
            var lr = color[0].toDouble(); var lg = color[1].toDouble(); var lb = color[2].toDouble()
            when (light) {
                is FilterLight.Distant -> { lx = distant!![0]; ly = distant[1]; lz = distant[2] }
                is FilterLight.Point -> { lx = light.x - x; ly = light.y - y; lz = light.z - surfaceZ }
                is FilterLight.Spot -> { lx = light.x - x; ly = light.y - y; lz = light.z - surfaceZ }
            }
            val lLength = sqrt(lx * lx + ly * ly + lz * lz)
            if (lLength > 0.0) { lx /= lLength; ly /= lLength; lz /= lLength }
            if (light is FilterLight.Spot) {
                val axis = spotAxis!!
                val minusLDotS = -(lx * axis[0] + ly * axis[1] + lz * axis[2])
                var k = if (minusLDotS <= 0.0) 0.0 else minusLDotS.pow(light.exponent)
                light.coneAngle?.let {
                    // The spec asks for a smoothed cone edge: the light fades over a band of
                    // CONE_EDGE in the cosine inside the cone, as in Skia and Chromium.
                    val outer = cos(it * kotlin.math.PI / 180)
                    k = when {
                        minusLDotS < outer -> 0.0
                        minusLDotS < outer + CONE_EDGE -> k * (minusLDotS - outer) / CONE_EDGE
                        else -> k
                    }
                }
                lr *= k; lg *= k; lb *= k
            }
            val p = (y * w + x) * 4
            if (!specular) {
                val d = constant * (nx * lx + ny * ly + nz * lz)
                out.data[p] = (d * lr).toFloat().coerceIn(0f, 1f)
                out.data[p + 1] = (d * lg).toFloat().coerceIn(0f, 1f)
                out.data[p + 2] = (d * lb).toFloat().coerceIn(0f, 1f)
                out.data[p + 3] = 1f
            } else {
                var hx = lx; var hy = ly; var hz = lz + 1.0
                val hLength = sqrt(hx * hx + hy * hy + hz * hz)
                if (hLength > 0.0) { hx /= hLength; hy /= hLength; hz /= hLength }
                val nDotH = (nx * hx + ny * hy + nz * hz).coerceAtLeast(0.0)
                val k = constant * nDotH.pow(exponent)
                val r = (k * lr).toFloat().coerceIn(0f, 1f)
                val g = (k * lg).toFloat().coerceIn(0f, 1f)
                val b = (k * lb).toFloat().coerceIn(0f, 1f)
                val a = maxOf(r, g, b)
                // Straight r, g and b over an alpha that is their greatest, premultiplied.
                out.data[p] = r; out.data[p + 1] = g; out.data[p + 2] = b; out.data[p + 3] = a
            }
        }
        return out
    }

    /** The width of the band, in the cosine of the angle, over which a spot light fades out at the edge of its cone. */
    private const val CONE_EDGE = 0.016

    /** The pixel ([x], [y]) of [toPixels] for a point of user space, and a height scaled as the map scales area. */
    fun toPixels(toPixels: KiteMatrix, x: Double, y: Double, z: Double): DoubleArray {
        val scale = sqrt(abs(toPixels.a * toPixels.d - toPixels.b * toPixels.c))
        return doubleArrayOf(toPixels.transformX(x, y), toPixels.transformY(x, y), z * scale)
    }
}
