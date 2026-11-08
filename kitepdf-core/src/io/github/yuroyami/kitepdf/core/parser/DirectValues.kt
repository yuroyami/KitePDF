package io.github.yuroyami.kitepdf.core.parser

/**
 * This dictionary with each entry that is a reference to a number, a name, a boolean, a string or
 * an array replaced by that object, and each such reference inside an array too. ISO 32000-1,
 * 7.3.10: any of these entries may be an indirect object, and the parsers of shadings, patterns,
 * functions and graphics states read them with no resolver, so an indirect `/Coords` read as
 * absent (#777). A reference to a dictionary or a stream stays a reference, so the cycle guards
 * that key on references still see it.
 */
internal fun PdfDictionary.withDirectValues(refs: IndirectResolver): PdfDictionary {
    var changed = false
    val out = LinkedHashMap<String, PdfObject>(map.size)
    for ((k, v) in map) {
        val direct = directValue(v, refs, depth = 0)
        if (direct !== v) changed = true
        out[k] = direct
    }
    return if (changed) PdfDictionary(out) else this
}

private fun directValue(v: PdfObject, refs: IndirectResolver, depth: Int): PdfObject = when (v) {
    is PdfReference -> {
        val r = runCatching { refs.resolve(v) }.getOrNull()
        when (r) {
            is PdfInt, is PdfReal, is PdfName, is PdfBoolean, is PdfString -> r
            is PdfArray -> if (depth < 2) directArray(r, refs, depth + 1) else r
            else -> v
        }
    }
    is PdfArray -> if (depth < 2) directArray(v, refs, depth + 1) else v
    else -> v
}

private fun directArray(a: PdfArray, refs: IndirectResolver, depth: Int): PdfArray {
    var changed = false
    val items = a.items.map { item ->
        val d = if (item is PdfReference || item is PdfArray) directValue(item, refs, depth) else item
        if (d !== item) changed = true
        d
    }
    return if (changed) PdfArray(items) else a
}
