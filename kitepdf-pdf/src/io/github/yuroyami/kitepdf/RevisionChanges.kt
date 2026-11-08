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

    private enum class Role { CATALOG, PAGE, PAGE_TREE, PAGE_CONTENT, ANNOTS, ANNOTATION, ANNOTATION_LOOK, FIELD, WIDGET, WIDGET_LOOK, FIELDS, FORM, SIGNATURE, TIMESTAMP, STORE, INFO, METADATA }

    /** What an object is: its role, what a reader calls it, and the field it belongs to, if any. */
    private class Place(val role: Role, val subject: String, val field: String? = null)

    /**
     * Where each annotation and field of a revision is listed: the page whose `/Annots` names an
     * annotation, the field that a field or widget belongs to through `/Fields` and `/Kids`, and
     * the direct annotations of each page (#1004).
     */
    private class Listing {
        val onPage = HashMap<Long, String>()
        val inForm = HashMap<Long, String>()
        val direct = HashMap<String, Pair<Long, List<PdfDictionary>>>()
    }

    /** The changes after [old], the signed revision, up to [new], judged at certification [level] (null: an approval signature) and [lock]. */
    fun between(old: PdfDocument, new: PdfDocument, level: Int?, lock: FieldLock?): List<PdfRevisionChange> {
        val newListing = Listing()
        val oldListing = Listing()
        val newPlaces = placesOf(new, newListing)
        val oldPlaces = placesOf(old, oldListing)
        var reachable: Set<Long>? = null
        val out = ArrayList<PdfRevisionChange>()
        fun report(kind: PdfRevisionChange.Kind, subject: String, number: Long, place: Place?) {
            out += PdfRevisionChange(kind, subject, number, permitted(kind, place, level, lock, subject))
        }
        val moved = listingChanges(old, new, oldListing, newListing, newPlaces, oldPlaces, ::report)
        val numbers = (old.xref.keys + new.xref.keys).sorted()
        // An object stream written again under its own number changes its members, whose entries stay
        // the same (ISO 32000-1, 7.5.7 and 7.5.8.3), so a member of a changed stream is compared too (#1003).
        val changedStreams = numbers.filterTo(HashSet()) { old.xref[it] != new.xref[it] }
        for (number in numbers) {
            val oldEntry = old.xref[number]
            val newEntry = new.xref[number]
            if (oldEntry == newEntry && (newEntry as? XrefEntry.Compressed)?.containingObjectStream !in changedStreams) continue
            if (number in moved) continue
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
            report(kind, subject, number, place)
        }
        return out.sortedBy { it.objectNumber }
    }

    /**
     * The annotations and fields that joined or left a page or the form, as [report] gets them,
     * and the numbers of those objects. The rules of [classify] leave a changed `/Annots`,
     * `/Fields` or `/Kids` to the objects they list, but an object that leaves a list, or one
     * that the signed revision held unlisted, does not change itself (#1004). ISO 32000-1,
     * 12.8.2.2, Table 254: only level 3 allows an annotation to come or go, and no level allows
     * a form field to, except a signature field that comes.
     */
    private fun listingChanges(
        old: PdfDocument,
        new: PdfDocument,
        oldListing: Listing,
        newListing: Listing,
        newPlaces: Map<Long, Place>,
        oldPlaces: Map<Long, Place>,
        report: (PdfRevisionChange.Kind, String, Long, Place?) -> Unit,
    ): Set<Long> {
        val moved = HashSet<Long>()
        // An object new in this revision, or one it deleted, reports itself through its own entry.
        fun inBoth(number: Long) = objectOf(old, number, old.xref[number]) != null && objectOf(new, number, new.xref[number]) != null
        for (number in (oldListing.inForm.keys + newListing.inForm.keys).sorted()) {
            val before = oldListing.inForm[number]
            val after = newListing.inForm[number]
            if (before == after || !inBoth(number)) continue
            moved += number
            val place = newPlaces[number] ?: oldPlaces[number]
            when {
                before == null && isSignature(new, number) -> report(PdfRevisionChange.Kind.Signature, "the signature field $after", number, place)
                before == null -> report(PdfRevisionChange.Kind.Other, "form field $after, listed in the form", number, place)
                after == null -> report(PdfRevisionChange.Kind.Other, "the removal of form field $before", number, place)
                else -> report(PdfRevisionChange.Kind.Other, "form field $before, moved to $after", number, place)
            }
        }
        for (number in (oldListing.onPage.keys + newListing.onPage.keys).sorted()) {
            val before = oldListing.onPage[number]
            val after = newListing.onPage[number]
            if (before == after || number in moved || !inBoth(number)) continue
            moved += number
            val dict = objectOf(new, number, new.xref[number]) as? PdfDictionary
            val subtype = dict?.getName("Subtype") ?: "plain"
            val where = when {
                before == null -> "listed on $after"
                after == null -> "taken off $before"
                else -> "moved from $before to $after"
            }
            val kind = when {
                subtype != "Widget" -> PdfRevisionChange.Kind.Annotation
                before == null && isSignature(new, number) -> PdfRevisionChange.Kind.Signature
                else -> PdfRevisionChange.Kind.Other
            }
            report(kind, "a $subtype annotation $where", number, newPlaces[number] ?: oldPlaces[number])
        }
        for (page in (oldListing.direct.keys + newListing.direct.keys).sorted()) {
            val before = oldListing.direct[page]
            val after = newListing.direct[page] ?: continue
            if (before == null || before.second == after.second) continue
            val gone = before.second.filter { it !in after.second }
            val come = after.second.filter { it !in before.second }
            val widget = (gone + come).any { it.getName("Subtype") == "Widget" }
            val kind = if (widget) PdfRevisionChange.Kind.Other else PdfRevisionChange.Kind.Annotation
            report(kind, "the direct annotations of $page", after.first, newPlaces[after.first])
        }
        return moved
    }

    /** Whether object [number] of [doc] is a signature field, or a widget of one. */
    private fun isSignature(doc: PdfDocument, number: Long): Boolean {
        val dict = objectOf(doc, number, doc.xref[number]) as? PdfDictionary ?: return false
        return isSignatureField(dict) || isSignatureField(dict["Parent"]?.resolve(doc))
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
                // A field signed in the signed revision is signed once: a new /V, /Lock or /SV is not signing it (#1002).
                isSignatureField(before) && (before as? PdfDictionary)?.get("V") != null && changed.any { it in SIGNED_FIELD_KEYS } ->
                    PdfRevisionChange.Kind.Other to "a change to signed field ${place.field}"
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
            Role.PAGE_TREE, Role.PAGE_CONTENT, Role.INFO, Role.METADATA -> PdfRevisionChange.Kind.Other to place.subject
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
    private fun placesOf(doc: PdfDocument, listing: Listing): Map<Long, Place> {
        val out = HashMap<Long, Place>()
        fun mark(obj: PdfObject?, place: Place) {
            (obj as? PdfReference)?.let { out.getOrPut(it.objectNumber) { place } }
        }
        val catalog = runCatching { doc.catalog }.getOrNull() ?: return out
        mark(doc.trailer["Root"], Place(Role.CATALOG, "the document catalog"))
        mark(doc.trailer["Info"], Place(Role.INFO, "the document information"))
        mark(catalog["Metadata"], Place(Role.METADATA, "the document metadata"))
        catalog["DSS"]?.let { reach(doc, it, Place(Role.STORE, "the document security store"), out) }

        // What a page draws with is page content, also where an appearance reaches it too, so the
        // pages' content and resources are marked before any appearance can claim them (#1005).
        markPageContent(doc, catalog["Pages"], out)

        val form = catalog["AcroForm"]
        mark(form, Place(Role.FORM, "the form"))
        val formDict = form?.resolve(doc) as? PdfDictionary
        mark(formDict?.get("Fields"), Place(Role.FIELDS, "the list of form fields"))
        // Each walk enters an object once (ISO 32000-1, 12.7.3.1 and 7.7.3.2 make these trees). The
        // depth cap alone let a node that lists itself twice double the calls at each level (#967).
        val entered = HashSet<PdfReference>()
        fun walkField(node: PdfObject, parent: String?, depth: Int) {
            if (depth > MAX_DEPTH) return
            if (node is PdfReference && !entered.add(node)) return
            val dict = node.resolve(doc) as? PdfDictionary ?: return
            val partial = (dict["T"] as? PdfString)?.asText()
            if (partial == null && dict.getName("Subtype") == "Widget" && parent != null) {
                if (node is PdfReference) listing.inForm[node.objectNumber] = parent
                mark(node, Place(Role.WIDGET, "a widget of field $parent", parent))
                dict["AP"]?.let { reach(doc, it, Place(Role.WIDGET_LOOK, "the appearance of field $parent", parent), out) }
                return
            }
            val name = listOfNotNull(parent, partial).joinToString(".").ifEmpty { "(unnamed)" }
            if (node is PdfReference) listing.inForm[node.objectNumber] = name
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
        val enteredPages = HashSet<PdfReference>()
        fun walkPages(node: PdfObject?, depth: Int) {
            if (node == null || depth > MAX_DEPTH) return
            if (node is PdfReference && !enteredPages.add(node)) return
            val dict = node.resolve(doc) as? PdfDictionary ?: return
            val kids = dict.getArray("Kids", doc)
            if (kids == null) {
                pageNumber++
                val page = "page $pageNumber"
                mark(node, Place(Role.PAGE, page))
                mark(dict["Annots"], Place(Role.ANNOTS, "the annotations of $page"))
                val direct = ArrayList<PdfDictionary>()
                (node as? PdfReference)?.let { listing.direct[page] = it.objectNumber to direct }
                for (annotation in dict.getArray("Annots", doc).orEmpty()) {
                    val a = annotation.resolve(doc) as? PdfDictionary ?: continue
                    if (annotation is PdfReference) listing.onPage.getOrPut(annotation.objectNumber) { page } else direct += a
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

    /**
     * Marks the content streams and the resources of each page, its own or inherited, and all
     * they reach, as page content (ISO 32000-1, 7.7.3.3 and 7.7.3.4).
     */
    private fun markPageContent(doc: PdfDocument, pages: PdfObject?, out: HashMap<Long, Place>) {
        var pageNumber = 0
        val entered = HashSet<PdfReference>()
        fun walk(node: PdfObject?, depth: Int) {
            if (node == null || depth > MAX_DEPTH) return
            if (node is PdfReference && !entered.add(node)) return
            val dict = node.resolve(doc) as? PdfDictionary ?: return
            val kids = dict.getArray("Kids", doc)
            if (kids == null) {
                val place = Place(Role.PAGE_CONTENT, "the content of page ${++pageNumber}")
                dict["Contents"]?.let { reach(doc, it, place, out) }
                dict["Resources"]?.let { reach(doc, it, place, out) }
            } else {
                dict["Resources"]?.let { reach(doc, it, Place(Role.PAGE_CONTENT, "the resources of the page tree"), out) }
                for (kid in kids) walk(kid, depth + 1)
            }
        }
        walk(pages, 0)
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

    /**
     * Keys that filling in a field changes: the value and how the widget shows it. A choice
     * field's selected indices (`/I`) and first visible option (`/TI`, ISO 32000-1, 12.7.4.4,
     * Table 231) and a rich text value (`/RV`, 12.7.3.3, Table 222) are part of filling it (#997).
     */
    private val FILLING_KEYS = setOf("V", "AS", "AP", "I", "TI", "RV")

    /** Keys that signing an empty signature field changes. */
    private val SIGNING_KEYS = setOf("V", "AP", "AS", "Lock", "SV")

    /** Keys of a signed field that no later revision may change (ISO 32000-1, 12.8.2.4 and Table 232). */
    private val SIGNED_FIELD_KEYS = setOf("V", "Lock", "SV")

    private val WIDGET_KEYS = setOf("AS", "AP", "MK")
    private val FORM_KEYS = setOf("Fields", "SigFlags", "NeedAppearances")
    private val CATALOG_KEYS = setOf("DSS", "Extensions")
}
