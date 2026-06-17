package io.github.yuroyami.kitepdf.parser

/**
 * Walker for PDF name trees (ISO 32000-1 §7.9.6). A name tree is a
 * balanced B-tree whose leaves are `/Names` arrays of `[key1 value1 key2
 * value2 …]` pairs. Non-leaf nodes carry `/Kids` arrays plus an optional
 * `/Limits [min max]` for skip-search; we don't bother with limits since
 * full-tree walks are cheap and correctness beats micro-optimisation here.
 *
 * Used for /Names /Dests, /Names /JavaScript, /Names /EmbeddedFiles, etc.
 */
internal object NameTreeWalker {

    /**
     * Walk a name-tree root and collect every leaf pair into a
     * key-preserving map. Insertion order is preserved (LinkedHashMap), so
     * callers that care about author-supplied order get it for free.
     */
    fun collect(root: PdfDictionary, refs: IndirectResolver): LinkedHashMap<String, PdfObject> {
        val out = LinkedHashMap<String, PdfObject>()
        walk(root, refs, out, depth = 0)
        return out
    }

    private fun walk(
        node: PdfDictionary,
        refs: IndirectResolver,
        out: MutableMap<String, PdfObject>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        val names = node.getArray("Names", refs)
        if (names != null) {
            var i = 0
            while (i + 1 < names.size) {
                val key = (names[i] as? PdfString)?.asText()
                if (key != null) out[key] = names[i + 1]
                i += 2
            }
        }
        val kids = node.getArray("Kids", refs) ?: return
        for (kid in kids) {
            val kidDict = when (kid) {
                is PdfReference -> refs.resolve(kid) as? PdfDictionary
                is PdfDictionary -> kid
                else -> null
            } ?: continue
            walk(kidDict, refs, out, depth + 1)
        }
    }

    private const val MAX_DEPTH = 64
}

/**
 * Walker for PDF *number* trees (ISO 32000-1 §7.9.7). Same shape as a name
 * tree, but keys are integers (used for /PageLabels, /StructTreeRoot /ParentTree).
 */
internal object NumberTreeWalker {

    fun collect(root: PdfDictionary, refs: IndirectResolver): LinkedHashMap<Int, PdfObject> {
        val out = LinkedHashMap<Int, PdfObject>()
        walk(root, refs, out, depth = 0)
        return out
    }

    private fun walk(
        node: PdfDictionary,
        refs: IndirectResolver,
        out: MutableMap<Int, PdfObject>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        val nums = node.getArray("Nums", refs)
        if (nums != null) {
            var i = 0
            while (i + 1 < nums.size) {
                val key = (nums[i] as? PdfInt)?.value?.toInt()
                if (key != null) out[key] = nums[i + 1]
                i += 2
            }
        }
        val kids = node.getArray("Kids", refs) ?: return
        for (kid in kids) {
            val kidDict = when (kid) {
                is PdfReference -> refs.resolve(kid) as? PdfDictionary
                is PdfDictionary -> kid
                else -> null
            } ?: continue
            walk(kidDict, refs, out, depth + 1)
        }
    }

    private const val MAX_DEPTH = 64
}
