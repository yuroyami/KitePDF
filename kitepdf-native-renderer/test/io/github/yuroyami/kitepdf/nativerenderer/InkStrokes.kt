package io.github.yuroyami.kitepdf.nativerenderer

/**
 * The 8-connected strokes of the dark pixels of [red], a [w] by [h] channel of a raster, that
 * hold a tenth of its ink or more. A joined Arabic word is one such stroke, with its dots apart
 * from it, where its letters drawn alone are one stroke each (#588).
 */
internal fun inkStrokes(red: IntArray, w: Int, h: Int): Int {
    val seen = BooleanArray(red.size)
    val sizes = ArrayList<Int>()
    val stack = ArrayDeque<Int>()
    for (start in red.indices) {
        if (seen[start] || red[start] >= 128) continue
        seen[start] = true
        stack.addLast(start)
        var size = 0
        while (stack.isNotEmpty()) {
            val p = stack.removeLast()
            size++
            val x = p % w
            val y = p / w
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                val q = ny * w + nx
                if (!seen[q] && red[q] < 128) {
                    seen[q] = true
                    stack.addLast(q)
                }
            }
        }
        sizes += size
    }
    val total = sizes.sum()
    return sizes.count { it * 10 >= total }
}
