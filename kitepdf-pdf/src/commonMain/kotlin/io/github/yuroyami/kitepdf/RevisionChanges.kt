package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRawApi
import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.parser.PdfString
import io.github.yuroyami.kitepdf.parser.XrefEntry

/**
 * The changes between the revision that a signature covers and the document now, and whether
 * a certification level and the field locks allow each one (ISO 32000-1, 12.8.2.2 and
 * 12.8.2.4; #448). Only objects whose cross-reference entries differ are compared.
 */
@OptIn(KiteRawApi::class)
internal object RevisionChanges {

    /** The fields that a FieldMDP lock covers (ISO 32000-1, Table 233): all, the listed ones, or all but the listed ones. */
    class FieldLock(private val action: String, private val fields: List<String>) {
        fun covers(field: String): Boolean {
            val listed = fields.any { field == it || field.startsWith("$it.") }
            return when (action) {
                "All" -> true
                "Include" -> listed
                "Exclude" -> !listed
                else -> false
            }
        }

        companion object {
            /** The lock that [dict] describes, a `/Lock` or the `/TransformParams` of a FieldMDP reference, or null. */
            fun of(dict: PdfDictionary?, refs: IndirectResolver): FieldLock? {
                val action = dict?.getName("Action") ?: return null
                val fields = dict.getArray("Fields", refs).orEmpty().mapNotNull { (it.resolve(refs) as? PdfString)?.asText() }
                return FieldLock(action, fields)
            }
        }
    }

    private enum class Role { CATALOG, PAGE, PAGE_TREE, ANNOTS, ANNOTATION, ANNOTATION_LOOK, FIELD, WIDGET, WIDGET_LOOK, FIELDS, FORM, SIGNATURE, TIMESTAMP, STORE, INFO, METADATA }

    /** What an object is: its role, what a reader calls it, and the field it belongs to, if any. */
    private class Place(val role: Role, val subject: String, val field: String? = null)

    /** The changes after [old], the signed revision, up to [new], judged at certification [level] (null: an approval signature) and [lock]. */
    fun between(old: PdfDocument, new: PdfDocument, level: Int?, lock: FieldLock?): List<PdfRevisionChange> {
        val newPlaces = placesOf(new)
        val oldPlaces = placesOf(old)
        var reachable: Set<Long>? = null
        val out = ArrayList<PdfRevisionChange>()
        for (number in (old.xref.keys + new.xref.keys).sorted()) {
            val oldEntry = old.xref[number]
            val newEntry = new.xref[number]
            if (oldEntry == newEntry) continue
            val before = objectOf(old, number, oldEntry)
            val after = objectOf(new, number, newEntry)
            // A cross-reference stream or an object stream is a container that nothing references, so
            // the orphan rule below skips it too; skipping it here spares the walk of the whole document.
            if (before == after || isContainer(before) || isContainer(after)) continue
            val place = newPlaces[number] ?: oldPlaces[number]
            if (place == null && before == null) {
                // A new object that nothing reaches changes nothing a reader sees.
                val seen = reachable ?: reachableFrom(new).also { reachable = it }
                if (number !in seen) continue
            }
            val (kind, subject) = classify(place, number, before, after) ?: continue
            out += PdfRevisionChange(kind, subject, number, permitted(kind, place, level, lock, subject))
        }
        return out
    }

    private fun permitted(kind: PdfRevisionChange.Kind, place: Place?, level: Int?, lock: FieldLock?, subject: String): Boolean {
        // Without a certification signature, the permissions of level 3 apply, as Acrobat applies them.
        val effective = level ?: 3
        return when (kind) {
            PdfRevisionChange.Kind.SecurityStore -> true
            PdfRevisionChange.Kind.Signature -> place?.role == Role.TIMESTAMP || effective >= 2
            PdfRevisionChange.Kind.FieldValue -> effective >= 2 && !(lock != null && place?.field != null && lock.covers(place.field))
            PdfRevisionChange.Kind.Annotation -> effective >= 3
            PdfRevisionChange.Kind.Other -> false
        }
    }

    /** What changed in object [number], or null when the change needs no report of its own. */
    private fun classify(place: Place?, number: Long, before: PdfObject?, after: PdfObject?): Pair<PdfRevisionChange.Kind, String>? {
        place ?: return PdfRevisionChange.Kind.Other to "object $number"
        val changed = changedKeys(before, after)
        return when (place.role) {
            Role.STORE -> PdfRevisionChange.Kind.SecurityStore to place.subject
            Role.TIMESTAMP -> PdfRevisionChange.Kind.Signature to place.subject
            Role.SIGNATURE ->
                if (before == null) PdfRevisionChange.Kind.Signature to place.subject
                else PdfRevisionChange.Kind.Other to "a change to ${place.subject}"
            Role.FIELD -> when {
                before == null && isSignatureField(after) -> PdfRevisionChange.Kind.Signature to "the new signature field ${place.field}"
                before == null -> PdfRevisionChange.Kind.Other to "the new form field ${place.field}"
                after == null -> PdfRevisionChange.Kind.Other to "the removal of form field ${place.field}"
                isSignatureField(after) && changed.all { it in SIGNING_KEYS } -> PdfRevisionChange.Kind.Signature to "the signing of field ${place.field}"
                changed.all { it in FILLING_KEYS } -> PdfRevisionChange.Kind.FieldValue to "the value of field ${place.field}"
                else -> PdfRevisionChange.Kind.Other to "field ${place.field}"
            }
            Role.WIDGET -> when {
                before != null && after != null && changed.all { it in WIDGET_KEYS } -> PdfRevisionChange.Kind.FieldValue to "the value of field ${place.field}"
                before == null && isSignatureField(after) -> PdfRevisionChange.Kind.Signature to "a widget of signature field ${place.field}"
                else -> PdfRevisionChange.Kind.Other to "a widget of field ${place.field}"
            }
            Role.WIDGET_LOOK -> PdfRevisionChange.Kind.FieldValue to "the appearance of field ${place.field}"
            Role.ANNOTATION, Role.ANNOTATION_LOOK -> PdfRevisionChange.Kind.Annotation to place.subject
            Role.ANNOTS, Role.FIELDS -> null
            Role.FORM -> if (changed.all { it in FORM_KEYS }) null else PdfRevisionChange.Kind.Other to place.subject
            Role.PAGE -> if (changed.all { it == "Annots" }) null else PdfRevisionChange.Kind.Other to place.subject
            Role.CATALOG -> {
                val allowed = changed.all { key ->
                    key in CATALOG_KEYS || (key == "AcroForm" && changedKeys((before as? PdfDictionary)?.get("AcroForm"), (after as? PdfDictionary)?.get("AcroForm")).all { it in FORM_KEYS })
                }
                if (allowed) null else PdfRevisionChange.Kind.Other to place.subject
            }
            Role.PAGE_TREE, Role.INFO, Role.METADATA -> PdfRevisionChange.Kind.Other to place.subject
        }
    }

    /** The keys whose values differ between two dictionaries or two streams' dictionaries, and `stream` for other stream data. */
    private fun changedKeys(before: PdfObject?, after: PdfObject?): Set<String> {
        val a = (before as? PdfStream)?.dict ?: before as? PdfDictionary
        val b = (after as? PdfStream)?.dict ?: after as? PdfDictionary
        if (a == null || b == null) return if (before == after) emptySet() else setOf("*")
        val keys = (a.keys + b.keys).filter { a[it] != b[it] }.toMutableSet()
        if (before is PdfStream && after is PdfStream && !before.rawBytes.contentEquals(after.rawBytes)) keys += "stream"
        return keys
    }

    private fun isSignatureField(obj: PdfObject?): Boolean = (obj as? PdfDictionary)?.getName("FT") == "Sig"

    private fun isContainer(obj: PdfObject?): Boolean = (obj as? PdfStream)?.dict?.getName("Type").let { it == "XRef" || it == "ObjStm" }

    private fun objectOf(doc: PdfDocument, number: Long, entry: XrefEntry?): PdfObject? = when (entry) {
        is XrefEntry.InUse -> runCatching { (doc as IndirectResolver).resolve(PdfReference(number, entry.generation)) }.getOrNull()
        is XrefEntry.Compressed -> runCatching { (doc as IndirectResolver).resolve(PdfReference(number, 0)) }.getOrNull()
        else -> null
    }

    /** What each object of [doc] is, by object number. The form comes first, so a field merged with its widget stays a field. */
    private fun placesOf(doc: PdfDocument): Map<Long, Place> {
        val out = HashMap<Long, Place>()
        fun mark(obj: PdfObject?, place: Place) {
            (obj as? PdfReference)?.let { out.getOrPut(it.objectNumber) { place } }
        }
        val catalog = runCatching { doc.catalog }.getOrNull() ?: return out
        mark(doc.trailer["Root"], Place(Role.CATALOG, "the document catalog"))
        mark(doc.trailer["Info"], Place(Role.INFO, "the document information"))
        mark(catalog["Metadata"], Place(Role.METADATA, "the document metadata"))
        catalog["DSS"]?.let { reach(doc, it, Place(Role.STORE, "the document security store"), out) }

        val form = catalog["AcroForm"]
        mark(form, Place(Role.FORM, "the form"))
        val formDict = form?.resolve(doc) as? PdfDictionary
        mark(formDict?.get("Fields"), Place(Role.FIELDS, "the list of form fields"))
        fun walkField(node: PdfObject, parent: String?, depth: Int) {
            if (depth > MAX_DEPTH) return
            val dict = node.resolve(doc) as? PdfDictionary ?: return
            val partial = (dict["T"] as? PdfString)?.asText()
            if (partial == null && dict.getName("Subtype") == "Widget" && parent != null) {
                mark(node, Place(Role.WIDGET, "a widget of field $parent", parent))
                dict["AP"]?.let { reach(doc, it, Place(Role.WIDGET_LOOK, "the appearance of field $parent", parent), out) }
                return
            }
            val name = listOfNotNull(parent, partial).joinToString(".").ifEmpty { "(unnamed)" }
            mark(node, Place(Role.FIELD, "field $name", name))
            dict["AP"]?.let { reach(doc, it, Place(Role.WIDGET_LOOK, "the appearance of field $name", name), out) }
            dict["V"]?.let { value ->
                val signature = value.resolve(doc) as? PdfDictionary
                if (signature != null && dict.getName("FT") == "Sig") {
                    val role = if (signature.getName("Type") == "DocTimeStamp") Role.TIMESTAMP else Role.SIGNATURE
                    mark(value, Place(role, if (role == Role.TIMESTAMP) "a document timestamp" else "the signature of field $name", name))
                }
            }
            for (kid in dict.getArray("Kids", doc).orEmpty()) walkField(kid, name, depth + 1)
        }
        for (field in formDict?.getArray("Fields", doc).orEmpty()) walkField(field, null, 0)

        var pageNumber = 0
        fun walkPages(node: PdfObject?, depth: Int) {
            if (node == null || depth > MAX_DEPTH) return
            val dict = node.resolve(doc) as? PdfDictionary ?: return
            val kids = dict.getArray("Kids", doc)
            if (kids == null) {
                pageNumber++
                val page = "page $pageNumber"
                mark(node, Place(Role.PAGE, page))
                mark(dict["Annots"], Place(Role.ANNOTS, "the annotations of $page"))
                for (annotation in dict.getArray("Annots", doc).orEmpty()) {
                    val a = annotation.resolve(doc) as? PdfDictionary ?: continue
                    if (a.getName("Subtype") == "Widget") {
                        mark(annotation, Place(Role.WIDGET, "a form widget on $page"))
                        a["AP"]?.let { reach(doc, it, Place(Role.WIDGET_LOOK, "the appearance of a form widget on $page"), out) }
                    } else {
                        val subject = "a ${a.getName("Subtype") ?: "plain"} annotation on $page"
                        mark(annotation, Place(Role.ANNOTATION, subject))
                        a["AP"]?.let { reach(doc, it, Place(Role.ANNOTATION_LOOK, "the appearance of $subject"), out) }
                        a["Popup"]?.let { mark(it, Place(Role.ANNOTATION, "a popup on $page")) }
                    }
                }
            } else {
                mark(node, Place(Role.PAGE_TREE, "the page tree"))
                for (kid in kids) walkPages(kid, depth + 1)
            }
        }
        walkPages(catalog["Pages"], 0)
        return out
    }

    /** Marks every object that [start] reaches as [place], except back links to a page or a parent. */
    private fun reach(doc: PdfDocument, start: PdfObject, place: Place, out: HashMap<Long, Place>) {
        val stack = ArrayDeque<PdfObject>().apply { add(start) }
        var budget = MAX_REACH
        while (stack.isNotEmpty() && budget-- > 0) {
            when (val item = stack.removeLast()) {
                is PdfReference -> {
                    if (out.containsKey(item.objectNumber)) continue
                    out[item.objectNumber] = place
                    runCatching { (doc as IndirectResolver).resolve(item) }.getOrNull()?.let { stack.add(it) }
                }
                is PdfDictionary -> for ((key, value) in item) if (key != "P" && key != "Parent") stack.add(value)
                is PdfStream -> for ((key, value) in item.dict) if (key != "P" && key != "Parent") stack.add(value)
                is PdfArray -> stack.addAll(item)
                else -> {}
            }
        }
    }

    /** The object numbers that the trailer reaches, for telling a new orphan from a new object in use. */
    private fun reachableFrom(doc: PdfDocument): Set<Long> {
        val seen = HashMap<Long, Place>()
        val place = Place(Role.INFO, "")
        for (key in listOf("Root", "Info")) doc.trailer[key]?.let { reach(doc, it, place, seen) }
        return seen.keys
    }

    private const val MAX_DEPTH = 64
    private const val MAX_REACH = 1_000_000

    /** Keys that filling in a field changes: the value and how the widget shows it. */
    private val FILLING_KEYS = setOf("V", "AS", "AP")

    /** Keys that signing an empty signature field changes. */
    private val SIGNING_KEYS = setOf("V", "AP", "AS", "Lock", "SV")

    private val WIDGET_KEYS = setOf("AS", "AP", "MK")
    private val FORM_KEYS = setOf("Fields", "SigFlags", "NeedAppearances")
    private val CATALOG_KEYS = setOf("DSS", "Extensions")
}
