package io.github.yuroyami.kitepdf.nativerenderer

/** Android requires even intervals; PDF repeats odd arrays (ISO 32000-1, 8.4.3.6). */
internal fun scaledDashIntervals(dashes: List<Double>, scale: Double): FloatArray {
    val size = if (dashes.size % 2 == 0) dashes.size else dashes.size * 2
    return FloatArray(size) { (dashes[it % dashes.size] * scale).toFloat() }
}
