package io.github.yuroyami.kitepdf.epub

/** The code point at [index] of [text]: a surrogate pair reads as one character (#319). */
internal fun codePointAt(text: CharSequence, index: Int): Int {
    val high = text[index]
    if (high.isHighSurrogate() && index + 1 < text.length) {
        val low = text[index + 1]
        if (low.isLowSurrogate()) return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
    }
    return high.code
}

/** The number of UTF-16 chars that [cp] takes. */
internal fun charCount(cp: Int): Int = if (cp >= 0x10000) 2 else 1

/** The code points of [text], a surrogate pair as one. */
internal fun codePointsOf(text: CharSequence): IntArray {
    val out = IntArray(text.length)
    var n = 0
    var i = 0
    while (i < text.length) {
        val cp = codePointAt(text, i)
        out[n++] = cp
        i += charCount(cp)
    }
    return if (n == out.size) out else out.copyOf(n)
}
