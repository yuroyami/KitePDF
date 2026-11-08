package io.github.yuroyami.kitepdf.render

import io.github.yuroyami.kitepdf.PdfOptionalContent
import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.missingAsNull

/**
 * Whether an optional content group or membership dictionary is visible in the default
 * configuration (ISO 32000-1, 8.11.2), one rule for the renderer and the text extractor (#822).
 */
internal object OptionalContentVisibility {

    private const val MAX_VISIBILITY_DEPTH = 32

    private class VisibilityTooDeep : RuntimeException()

    /** Resolve an OCG or OCMD object and decide if it is visible by default. */
    fun isVisible(obj: PdfObject, oc: PdfOptionalContent, resolver: IndirectResolver): Boolean {
        // A missing object is null (ISO 32000-1, 7.3.10), so it hides nothing (#252).
        val dict = missingAsNull { obj.resolve(resolver) } as? PdfDictionary
            ?: return true
        if (dict.getName("Type") == "OCMD") return evalOcmd(dict, oc, resolver)
        // Plain OCG: hidden only if explicitly OFF in the default configuration.
        val id = (obj as? PdfReference)?.objectNumber?.toString()
            ?: return true
        return id !in oc.offByDefault
    }

    /**
     * Evaluate an OCMD (§8.11.2.2). A /VE visibility expression, when present,
     * takes precedence over the /OCGs + /P membership dictionary; only if there
     * is no /VE do we fall back to /OCGs and the /P policy.
     */
    private fun evalOcmd(dict: PdfDictionary, oc: PdfOptionalContent, resolver: IndirectResolver): Boolean {
        (missingAsNull { dict["VE"]?.resolve(resolver) } as? PdfArray)?.let { ve ->
            // ISO 32000-1, 8.11.2.2 bounds no expression, so a cyclic or absurdly
            // deep one counts as visible instead of overflowing the stack (#55).
            return try { evalVisibilityExpr(ve, oc, 0, resolver) } catch (_: VisibilityTooDeep) { true }
        }
        val ocgsRaw = dict["OCGs"]
        val refs: List<PdfReference> = when (
            val r = missingAsNull { ocgsRaw?.resolve(resolver) }
        ) {
            is PdfArray -> r.mapNotNull { it as? PdfReference }
            else -> listOfNotNull(ocgsRaw as? PdfReference)
        }
        if (refs.isEmpty()) return true
        val visible = refs.map { it.objectNumber.toString() !in oc.offByDefault }
        return when (dict.getName("P")) {
            "AllOn" -> visible.all { it }
            "AnyOff" -> visible.any { !it }
            "AllOff" -> visible.all { !it }
            else -> visible.any { it }   // AnyOn (default)
        }
    }

    /**
     * Evaluate an OCMD /VE visibility expression (§8.11.2.2): an array whose
     * first element is /And, /Or, or /Not and whose remaining elements are
     * either OCG references or nested /VE arrays. Returns whether the expression
     * is currently satisfied (i.e. the content is visible).
     */
    private fun evalVisibilityExpr(expr: PdfArray, oc: PdfOptionalContent, depth: Int, resolver: IndirectResolver): Boolean {
        if (depth > MAX_VISIBILITY_DEPTH) throw VisibilityTooDeep()
        val opName = (expr.getOrNull(0) as? PdfName)?.value ?: return true
        val operands = (1 until expr.size).mapNotNull { expr.getOrNull(it) }
        fun evalOperand(o: PdfObject): Boolean = when (val r = missingAsNull { o.resolve(resolver) }) {
            is PdfArray -> evalVisibilityExpr(r, oc, depth + 1, resolver)
            else -> {
                // A bare OCG reference: visible unless OFF in the default config.
                val id = (o as? PdfReference)?.objectNumber?.toString()
                id == null || id !in oc.offByDefault
            }
        }
        return when (opName) {
            "Not" -> operands.firstOrNull()?.let { !evalOperand(it) } ?: true
            "Or" -> operands.any { evalOperand(it) }
            "And" -> operands.all { evalOperand(it) }
            else -> true
        }
    }

}
