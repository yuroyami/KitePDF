package io.github.yuroyami.kitepdf.epub.script

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

/**
 * The sine and cosine of [radians], as a pair, with the angle reduced by multiples of pi/2 in three
 * parts before the platform's functions see it, as fdlibm's `__ieee754_rem_pio2` reduces a medium
 * angle. mingw-w64 computes `sin` and `cos` with the x87 `fsin` and `fcos`, which reduce by a 66-bit
 * pi, so on Windows `rotate(Math.PI / 2)` gave a canvas the cosine 6.1230318e-17, where Chromium and
 * the other targets give 6.1232340e-17, the distance from the double nearest pi/2 to pi/2. A script
 * reads that back through `getTransform()`.
 */
internal fun canvasSinCos(radians: Double): Pair<Double, Double> {
    val size = abs(radians)
    if (size <= PI / 4 || size > MEDIUM || radians.isNaN()) return sin(radians) to cos(radians)
    val n = round(radians * TWO_OVER_PI)
    // Each part has at most 33 significant bits, so n times a part is exact while n < 2^20.
    val r = (((radians - n * PIO2_1) - n * PIO2_2) - n * PIO2_3) - n * PIO2_3T
    val s = sin(r)
    val c = cos(r)
    return when (((n.toLong() % 4) + 4) % 4) {
        0L -> s to c
        1L -> c to -s
        2L -> -s to -c
        else -> -c to s
    }
}

/** 2/pi, and pi/2 in four parts, as fdlibm has them. */
private val TWO_OVER_PI = Double.fromBits(0x3FE45F306DC9C883)
private val PIO2_1 = Double.fromBits(0x3FF921FB54400000)
private val PIO2_2 = Double.fromBits(0x3DD0B4611A600000)
private val PIO2_3 = Double.fromBits(0x3BA3198A2E000000)
private val PIO2_3T = Double.fromBits(0x397B839A252049C1)

/** Past this, n reaches 2^19 and a product of n and a part may round, so the platform reduces. */
private const val MEDIUM = 823_549.6
