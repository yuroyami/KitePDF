package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.content.ContentStreamParser
import io.github.yuroyami.kitepdf.content.Operation
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.font.PdfFont
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfStream

/*
 * The redaction machinery behind [PdfEditor.redactRegions], split out of
 * PdfEditor.kt for size (ledger 14.8). Extensions rather than members; the
 * editor state they touch is internal to this module.
 */

/**
 * Reset the per-call form bookkeeping.
 *
 * All three maps describe ONE redaction call: inside a call they accumulate
 * across the hit loop, and between calls they have to be forgotten. Carrying
 * [claimedForms] over would make a second call find the form already claimed
 * and clone it rather than rewriting it, leaving the original (which still
 * holds the second region's content) named in `/XObject` and so kept alive by
 * the reachability GC. Cleared, the second call re-claims the form and composes
 * on the snapshot of the already-redacted version (D-1).
 *
 * The clone-name cache is NOT here: it lives inside [recurseIntoForms] itself,
 * scoped to one PARENT rather than one call. See that function's doc for why.
 *
 * [redactedRegionsByPage] is deliberately not one of these three: see its own doc.
 */
internal fun PdfEditor.beginRedactionCall() {
    formSources.clear()
    claimedForms.clear()
    redactedFormCache.clear()
}

/**
 * Identity of one redaction OF one form. Two invocations of the same form
 * that map a region to the same place AND inherit the same pen can share a
 * rewrite; two that differ in either cannot, because one rewritten stream
 * cannot be right for both: a stroke's padding depends on [lineWidth] and
 * [miterLimit] (8.4.3.2, 8.4.3.5) the same way its position depends on
 * [rectangles], and both travel into a form from the invoking `Do` (8.10.2).
 * Coordinates and pen are quantised to 1/1000 pt so float noise in the
 * inverted CTM does not manufacture clones that are the same rewrite twice.
 */
internal fun PdfEditor.formKey(
    objectNumber: Long,
    rectangles: List<KiteRectangle>,
    lineWidth: Double,
    miterLimit: Double,
): String = buildString {
    append(objectNumber)
    val sorted = rectangles.sortedWith(
        compareBy({ it.left }, { it.bottom }, { it.right }, { it.top }),
    )
    for (r in sorted) {
        append('|')
        append(quantise(r.left)); append(',')
        append(quantise(r.bottom)); append(',')
        append(quantise(r.right)); append(',')
        append(quantise(r.top))
    }
    append('|'); append(quantise(lineWidth))
    append(','); append(quantise(miterLimit))
}

/**
 * One coordinate as a key component, in thousandths of a point.
 *
 * Each degenerate value gets its OWN sentinel. Folding NaN, the two infinities
 * and an overflow into one bucket would make two mappings that differ look
 * identical, so they would share one rewrite when they need two.
 */
internal fun PdfEditor.quantise(value: Double): Long = when {
    value.isNaN() -> Long.MIN_VALUE
    value == Double.POSITIVE_INFINITY -> Long.MAX_VALUE
    value == Double.NEGATIVE_INFINITY -> Long.MIN_VALUE + 1
    // 2^53 / 1000: past here `value * 1000` can no longer separate adjacent
    // integers, so rounding it would merge distinct coordinates.
    value >= 9.007199254740992E12 -> Long.MAX_VALUE - 1
    value <= -9.007199254740992E12 -> Long.MIN_VALUE + 2
    else -> kotlin.math.round(value * 1000.0).toLong()
}

/** The name a `Do` operand is expected to carry, and the one it must carry instead. */
internal class Rename(val from: String, val to: String)

/** What a descent into a parent's forms asks the parent to change about itself. */
internal class FormRedaction {
    /** Index of a `Do` in the parent's filtered stream, to the rename it must take. */
    val renames = LinkedHashMap<Int, Rename>()

    /** Index of a `Do` that must GO, to the name it is expected to carry. */
    val drops = LinkedHashMap<Int, String>()

    /** Resource entries the parent must add to its own `/XObject` dictionary. */
    val additions = LinkedHashMap<String, PdfReference>()

    /** Names a drop took out, so the parent can prune the ones nothing draws now. */
    val droppedNames = LinkedHashSet<String>()
}

/**
 * Redact every form XObject an outer stream invoked over a region.
 *
 * A form drawn twice under different transforms maps the same page region to
 * a DIFFERENT rectangle in its own space each time. One rewritten stream
 * cannot serve both: rewriting against the union deletes content from an
 * invocation that never overlapped a region, and rewriting against only the
 * first leaves the second invocation's content intact. Each distinct
 * (form, rectangles, pen) triple therefore gets its own object, and the
 * caller repoints that invocation's `Do` at it.
 *
 * The clone-name cache is a LOCAL, so it lives for one call to this function,
 * that is one PARENT resource dictionary. A name is a string meaningful only
 * inside the `/XObject` dict it is added to (two different parents can use
 * the same string, or different strings, for the same shared clone, and
 * neither is wrong), so nothing is lost by minting it fresh per parent. The
 * opposite used to be true: the cache was a field shared across the WHOLE
 * call, so a clone target minted here for THIS parent's `usedNames` could be
 * reused verbatim for a different parent reaching the same clone (the shared
 * [redactedFormCache] deliberately allows that sharing), silently colliding
 * with an unrelated entry already using that exact name in the SECOND
 * parent's own `/XObject` dict.
 *
 * @return what the caller must change about its own stream and resources.
 */
internal fun PdfEditor.recurseIntoForms(
    resources: PdfDictionary?,
    hits: List<RedactionEngine.FormHit>,
): FormRedaction {
    val result = FormRedaction()
    val xobjects = resources?.getDict("XObject", effective) ?: return result
    val usedNames = HashSet(xobjects.keys)
    val formCloneNames = LinkedHashMap<Long, String>()

    // A form with even one invocation that paints into no region must keep its
    // original object as it is, because that invocation goes on drawing it.
    // Every redacting invocation of such a form gets a clone instead, so a
    // stamp repeated across the page costs nothing when no region touches it.
    val pristine = HashSet<Long>()
    for (hit in hits) {
        if (hit.intersects) continue
        xobjects.getRef(hit.name)?.let { pristine.add(it.objectNumber) }
    }

    for (hit in hits) {
        if (!hit.intersects) continue // draws into no region: nothing to redact, nothing to repoint
        if (hit.formRects.isEmpty()) continue
        val formRef = xobjects.getRef(hit.name) ?: continue
        // Already on the descent: the outer invocation that put it there owns the
        // rewrite, so this one keeps pointing at whatever that produces.
        if (formRef.objectNumber in formDescent) continue
        val key = formKey(formRef.objectNumber, hit.formRects, hit.lineWidth, hit.miterLimit)
        val claimable = formRef.objectNumber !in pristine
        val target = redactedFormCache[key]
            ?: redactFormXObject(formRef, hit.formRects, key, claimable, hit.lineWidth, hit.miterLimit)
        if (target == null) {
            // The form paints into a region and we could not read it. Redaction is
            // destructive, so a skip here would ship content we never inspected
            // inside a file the caller believes is clean. The invocation goes.
            result.drops[hit.opIndex] = hit.name
            result.droppedNames.add(hit.name)
            continue
        }
        if (target.objectNumber == formRef.objectNumber) continue // redacted in place, name still fits
        val name = formCloneNames.getOrPut(target.objectNumber) {
            var i = 1
            while ("${hit.name}R$i" in usedNames) i++
            "${hit.name}R$i".also { usedNames.add(it) }
        }
        result.renames[hit.opIndex] = Rename(from = hit.name, to = name)
        result.additions[name] = target
    }
    return result
}

/**
 * Redact [rectangles] (in the form's OWN space) inside the form at [formRef],
 * recursing into any form it invokes in turn.
 *
 * The first distinct rectangle set claims the original object, unless
 * [claimable] is false because some invocation of this form paints into no
 * region and still needs the original as it stands. A set that cannot claim,
 * and every later set, gets a fresh object, so no two rewrites collide.
 *
 * Nothing is claimed, allocated or cached until the source stream has decoded,
 * because everything after that point stages state: a cache entry pointing at
 * an object that was never written would hand a later hit a dangling reference.
 *
 * The caller screens out cycles before calling, so null means ONE thing here:
 * the form could not be read, and the caller must drop the invocation rather
 * than leave content it could not inspect in a file it calls redacted.
 *
 * [lineWidth] and [miterLimit] seed the nested engine's pen (ISO 32000-1,
 * 8.10.2: a `Do` is a save/restore around the form, so the invoking stream's
 * graphics state, including the pen, is what a form's own unset `w`/`M`
 * fall back to). [formKey] already folds both into [key], so a second
 * invocation that inherits a different pen never reuses this rewrite.
 *
 * @return the object holding this redaction, or null when the form's stream is
 *   missing or will not decode.
 */
internal fun PdfEditor.redactFormXObject(
    formRef: PdfReference,
    rectangles: List<KiteRectangle>,
    key: String,
    claimable: Boolean,
    lineWidth: Double,
    miterLimit: Double,
): PdfReference? {
    val stream = formSources[formRef.objectNumber]
        ?: (effectiveObject(formRef.objectNumber) as? PdfStream)
            ?.also { formSources[formRef.objectNumber] = it }
        ?: return null
    val content = runCatching {
        io.github.yuroyami.kitepdf.core.filters.FilterChain.decode(stream)
    }.getOrNull() ?: return null

    val claimed = claimable && claimedForms.add(formRef.objectNumber)
    val target = if (claimed) formRef else allocateReference()
    redactedFormCache[key] = target

    formDescent.addLast(formRef.objectNumber)
    try {
        // A form may omit /Resources and take the page's (7.8.3). The renderer
        // does the same, which is what keeps the mirror invariant true.
        val formResources = stream.dict.getDict("Resources", effective) ?: redactionPageResources
        val ops = ContentStreamParser.parse(content)
        val engine = RedactionEngine(
            loadPageFonts(formResources),
            loadImageXObjectNames(formResources),
            loadFormXObjectNames(formResources),
            rectangles,
            lineWidth,
            miterLimit,
        )
        engine.formMatrices = loadFormMatrices(formResources)
        engine.formBBoxes = loadFormBBoxes(formResources)
        val filtered = engine.run(ops)

        val nested = recurseIntoForms(formResources, engine.formXObjectHits)
        val finalOps = applyFormRedaction(filtered, nested)
        val body = ContentStreamWriter.serialize(finalOps)

        // Keep every non-encoding dict entry (/BBox, /Matrix, /Group, /Type,
        // /Subtype) intact, prune the XObjects nothing draws any more so the GC
        // takes their streams, and add the clones the nested descent minted.
        var newDict = prunePageResourceXObjects(
            stream.dict, engine.droppedImageNames, engine.survivingImageNames,
        )
        newDict = prunePageResourceXObjects(newDict, nested.droppedNames, namesStillDrawn(finalOps))
        val newResources = withFormAdditions(newDict.getDict("Resources", effective), nested)
        if (newResources != null) newDict = withEntry(newDict, "Resources", newResources)
        updateObject(target, PdfStreams.flate(body, extraFrom(newDict)))
    } catch (e: Throwable) {
        // Nothing was staged for [target]. Leaving the cache entry would let a
        // later hit with this key rename a `Do` to an object that never exists.
        redactedFormCache.remove(key)
        if (claimed) claimedForms.remove(formRef.objectNumber)
        throw e
    } finally {
        formDescent.removeLast()
    }
    return target
}

/**
 * Repoint the `Do` operands a descent asked us to clone, and remove the ones it
 * asked us to drop.
 *
 * Both the operator AND the operand are checked against what the hit recorded.
 * The index came from the engine's own output list, so it is right by
 * construction, but if anything ever shifts that list the check is what stops
 * this from rewriting or deleting some other invocation.
 *
 * Removals come last, after every index-based rewrite, so no index moves under
 * an operation that still needs it.
 */
internal fun PdfEditor.applyFormRedaction(ops: List<Operation>, redaction: FormRedaction): List<Operation> {
    if (redaction.renames.isEmpty() && redaction.drops.isEmpty()) return ops
    val out = ops.toMutableList()
    for ((index, rename) in redaction.renames) {
        if (!isDoNamed(out.getOrNull(index), rename.from)) continue
        out[index] = Operation("Do", listOf(PdfName(rename.to)))
    }
    val remove = redaction.drops.filter { (index, name) -> isDoNamed(out.getOrNull(index), name) }.keys
    if (remove.isEmpty()) return out
    return out.filterIndexed { i, _ -> i !in remove }
}

/** Is [op] a `Do` invoking exactly [name]? */
internal fun PdfEditor.isDoNamed(op: Operation?, name: String): Boolean =
    op != null && op.operator == "Do" && (op.operands.firstOrNull() as? PdfName)?.value == name

/** Names a `Do` still invokes after the rewrite, so a resource nothing draws can go. */
internal fun PdfEditor.namesStillDrawn(ops: List<Operation>): Set<String> {
    val out = HashSet<String>()
    for (op in ops) {
        if (op.operator != "Do") continue
        (op.operands.firstOrNull() as? PdfName)?.value?.let { out.add(it) }
    }
    return out
}

/** Add cloned forms to a resource dictionary's `/XObject`, or null when there is nothing to add. */
internal fun PdfEditor.withFormAdditions(resources: PdfDictionary?, redaction: FormRedaction): PdfDictionary? {
    if (redaction.additions.isEmpty()) return null
    val xobjects = LinkedHashMap(resources?.getDict("XObject", effective)?.map ?: emptyMap())
    for ((name, ref) in redaction.additions) xobjects[name] = ref
    val merged = LinkedHashMap(resources?.map ?: emptyMap())
    merged["XObject"] = PdfDictionary(xobjects)
    return PdfDictionary(merged)
}

/** Carry a form stream dict's non-stream entries onto a fresh /FlateDecode stream. */
internal fun PdfEditor.extraFrom(dict: PdfDictionary): Map<String, PdfObject> {
    val extra = LinkedHashMap<String, PdfObject>()
    for ((k, v) in dict.map) {
        if (k == "Length" || k == "Filter" || k == "DecodeParms" || k == "DL") continue
        extra[k] = v
    }
    return extra
}

/**
 * Remove entries in the dict's `/Resources /XObject` for XObjects that were
 * dropped from the content AND are not still drawn elsewhere on the page.
 * Without this the stream stays reachable and its data survives the GC. Called
 * once for dropped images and once for dropped forms, since the two have
 * different notions of what "still drawn" means.
 */
internal fun PdfEditor.prunePageResourceXObjects(
    dict: PdfDictionary,
    droppedNames: Set<String>,
    survivingNames: Set<String>,
): PdfDictionary {
    val toPrune = droppedNames - survivingNames
    if (toPrune.isEmpty()) return dict
    val resources = dict.getDict("Resources", effective) ?: return dict
    val xobjects = resources.getDict("XObject", effective) ?: return dict
    val remaining = LinkedHashMap(xobjects.map)
    var changed = false
    for (n in toPrune) if (remaining.remove(n) != null) changed = true
    if (!changed) return dict

    val newResources = LinkedHashMap(resources.map)
    newResources["XObject"] = PdfDictionary(remaining)
    return withEntry(dict, "Resources", PdfDictionary(newResources))
}

/**
 * Drop annotations from the page `/Annots` whose `/Rect` intersects any
 * redaction rectangle, reporting the ones dropped in [dropped] so the caller
 * can detach their form fields too. FreeText contents, widget values, and stamp
 * appearance streams inside a redacted region are otherwise left intact and
 * extractable. Annotations with no resolvable `/Rect` are kept (they draw
 * nothing spatial).
 */
internal fun PdfEditor.pruneIntersectingAnnots(
    dict: PdfDictionary,
    rectangles: List<KiteRectangle>,
    dropped: MutableList<PdfReference>,
): PdfDictionary {
    val annots = dict.getArray("Annots", effective) ?: return dict
    val kept = ArrayList<PdfObject>(annots.items.size)
    var changed = false
    for (item in annots.items) {
        val annotDict = when (val resolved = item.resolve(effective)) {
            is PdfDictionary -> resolved
            else -> null
        }
        val rect = annotDict?.let { annotRect(it) }
        if (rect != null && rectangles.any { rectsIntersect(it, rect) }) {
            changed = true // drop it
            // Only an indirect one can be named from anywhere else: a dictionary
            // written straight into /Annots lives nowhere but here, so removing it
            // from the array is the whole of removing it.
            (item as? PdfReference)?.let { dropped.add(it) }
        } else {
            kept.add(item)
        }
    }
    if (!changed) return dict
    return withEntry(dict, "Annots", io.github.yuroyami.kitepdf.core.parser.PdfArray(kept))
}

/** Normalised `/Rect` of an annotation, or null when absent/malformed. */
internal fun PdfEditor.annotRect(annot: PdfDictionary): KiteRectangle? {
    val arr = annot.getArray("Rect", effective) ?: return null
    if (arr.size < 4) return null
    fun n(i: Int): Double? = when (val v = arr[i].resolve(effective)) {
        is PdfInt -> v.value.toDouble()
        is io.github.yuroyami.kitepdf.core.parser.PdfReal -> v.value
        else -> null
    }
    val x0 = n(0) ?: return null
    val y0 = n(1) ?: return null
    val x1 = n(2) ?: return null
    val y1 = n(3) ?: return null
    return KiteRectangle(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
}

/**
 * Detach the form fields of [droppedAnnots] from the interactive form.
 *
 * A widget annotation is usually the field dictionary as well (ISO 32000-1,
 * 12.7.3.3), so dropping it from the page `/Annots` leaves `/AcroForm /Fields`
 * (12.7.2) pointing at that same object: its `/V`, `/DV`, `/T` and appearance
 * stream stay reachable from the catalog and survive [saveRewritten], and the
 * caller ships a file they believe is clean. A widget that is one of a field's
 * `/Kids` is taken out of the parent instead, and a parent left with nothing is
 * detached in turn.
 *
 * Two things happen to a detached field: it is [scrub]bed of its value and its
 * names, and it is taken out of the form's `/Fields` and `/CO` arrays.
 */
internal fun PdfEditor.detachRedactedFields(droppedAnnots: List<PdfReference>) {
    if (droppedAnnots.isEmpty()) return

    val detached = LinkedHashMap<Long, PdfReference>()
    for (annot in droppedAnnots) {
        val annotDict = effectiveObject(annot.objectNumber) as? PdfDictionary ?: continue
        // A Popup's /Parent is the markup annotation it belongs to (12.5.6.14), not
        // a field parent, so walking it as one would take the title and appearance
        // off an annotation nobody redacted, and a malformed one would abort the
        // redaction. Everything else takes the field path, including a /Subtype
        // this cannot read (getName does not resolve an indirect one), because
        // under-detaching ships the value. A Popup that hides its subtype that way
        // gets no further than [isFieldNode], which refuses its markup parent.
        if (annotDict.getName("Subtype") == "Popup") continue
        // A widget with no /Parent IS the field, named in /Fields directly. One
        // that has a parent is going either way, so naming it here costs nothing
        // and covers a file that also lists a kid widget in /Fields.
        detached[annot.objectNumber] = annot
        detachFromParentField(annot, annotDict.getRef("Parent") ?: continue, detached)
    }
    if (detached.isEmpty()) return
    scrub(detached.values, PdfEditor.SCRUBBED_FIELD_KEYS)

    // The form dictionary is read last: /Fields may be absent or malformed, and
    // the /Kids detachment above has to run either way.
    val rootRef = (trailerOverrides["Root"] ?: base.trailer["Root"]) as? PdfReference ?: return
    val catalog = effectiveObject(rootRef.objectNumber) as? PdfDictionary ?: return
    // /AcroForm is usually its own object, but a dictionary written straight into
    // the catalog is legal, and then the catalog is what carries the change.
    val acroRef = catalog["AcroForm"] as? PdfReference
    val acro = (if (acroRef != null) effectiveObject(acroRef.objectNumber) else catalog["AcroForm"])
        as? PdfDictionary ?: return

    // /Fields is the root field list; /CO is the calculation order (12.7.2,
    // Table 218), which names terminal fields directly and so keeps a detached
    // one alive on its own even after /Fields lets go of its root.
    var newAcro: PdfDictionary? = null
    for (key in listOf("Fields", "CO")) {
        val pruned = withoutDetached(acro.getArray(key, effective), detached.keys) ?: continue
        newAcro = withEntry(newAcro ?: acro, key, pruned)
    }
    if (newAcro == null) return
    if (acroRef != null) {
        updateObject(acroRef, newAcro)
    } else {
        updateObject(rootRef, withEntry(catalog, "AcroForm", newAcro))
    }
}

/** [array] with every detached object taken out, or null when it names none of them. */
internal fun PdfEditor.withoutDetached(
    array: io.github.yuroyami.kitepdf.core.parser.PdfArray?,
    detached: Set<Long>,
): io.github.yuroyami.kitepdf.core.parser.PdfArray? {
    if (array == null) return null
    val kept = array.items.filter { item ->
        val num = (item as? PdfReference)?.objectNumber
        num == null || num !in detached
    }
    if (kept.size == array.items.size) return null
    return io.github.yuroyami.kitepdf.core.parser.PdfArray(kept)
}

/**
 * Restage each of [objects] without [keys].
 *
 * Redaction takes an annotation out of `/Annots` and a field out of `/Fields`,
 * and then leaves [saveRewritten]'s reachability GC to delete the object. That
 * only holds for the reference paths this editor rewrites: a form's `/XFA`
 * payload carries its own copy of the field tree, and a tagged document names
 * annotations from its `/StructTreeRoot` through an `OBJR` (ISO 32000-1,
 * 14.7.4.3). Scrubbing means an object that something else still names ships
 * empty instead of carrying the content the caller asked to remove.
 *
 * Only objects redaction actually removed are scrubbed. A parent field that kept
 * a widget outside every region is still live and keeps everything it has.
 */
internal fun PdfEditor.scrub(objects: Collection<PdfReference>, keys: List<String>) {
    for (ref in objects) {
        val dict = effectiveObject(ref.objectNumber) as? PdfDictionary ?: continue
        val scrubbed = LinkedHashMap(dict.map)
        var changed = false
        for (key in keys) if (scrubbed.remove(key) != null) changed = true
        if (changed) updateObject(ref, PdfDictionary(scrubbed))
    }
}

/**
 * True unless [dict] is something a form field never is.
 *
 * It deliberately says nothing about what a field looks like: `/FT` is
 * inheritable and `/T` is optional (ISO 32000-1, 12.7.3.1, Table 220), so a
 * terminal field can carry neither, and a test for them refuses a real field and
 * leaves its value in the file. What it does rule out is a page or `/Pages` node
 * (7.7.3.2), whose `/Kids` is the PAGE tree, and an annotation other than a
 * widget, whose `/T` is an author's name (12.5.6.4) and which is usually still on
 * the page. Whether a candidate really is this widget's field is settled
 * structurally in [detachFromParentField], not here; both `getName` reads are
 * non-resolving, and that structural test is what backstops them.
 */
internal fun PdfEditor.isFieldNode(dict: PdfDictionary): Boolean = when {
    dict.getName("Type") == "Page" || dict.getName("Type") == "Pages" -> false
    dict.getName("Type") == "Annot" || dict["Rect"] != null -> dict.getName("Subtype") == "Widget"
    else -> true
}

/**
 * Take [widget] out of its parent field's `/Kids`, and add to [detached] a
 * parent that is left with no kids at all: it only existed to hold the widgets
 * that went, so the walk climbs to ITS parent in turn until one still has a kid
 * or the root field `/Fields` names is reached. A candidate whose `/Kids` is
 * non-empty but does not name the child is not that widget's field and is left
 * untouched; only a candidate with no `/Kids` at all is detached, for holding
 * the value directly (12.7.3.3).
 *
 * Fields form a tree (ISO 32000-1, 12.7.3.3), so a `/Parent` chain that returns
 * to a node it already visited is malformed and the root field cannot be found.
 * Redaction is a destructive write, so it says so rather than hand back a file
 * whose `/Fields` still reaches the value the caller asked to remove.
 */
internal fun PdfEditor.detachFromParentField(
    widget: PdfReference,
    parentRef: PdfReference,
    detached: MutableMap<Long, PdfReference>,
) {
    var child = widget
    var parent = parentRef
    val seen = HashSet<Long>()
    while (seen.add(parent.objectNumber)) {
        val field = effectiveObject(parent.objectNumber) as? PdfDictionary ?: return
        // A stray /Parent (a producer writing it where it means /P, say) points at
        // something that is not a field at all. The structural test below is what
        // keeps this walk out of the page tree; this one keeps a markup annotation
        // that is still on the page from being scrubbed on the way past.
        if (!isFieldNode(field)) return
        // The parent-kid link is mutual (12.7.3.3), so the node this walk may
        // rewrite is the one that actually names the child it arrived from. That
        // is the whole test: a page keeps its annotations in /Annots and has no
        // /Kids at all, and nor has a markup annotation, so a stray /Parent cannot
        // walk this into the page tree however field-like the target looks.
        val kids = field.getArray("Kids", effective)?.items.orEmpty()
        val survivors = kids.filter { (it as? PdfReference)?.objectNumber != child.objectNumber }
        if (kids.isEmpty()) {
            // No /Kids at all: a producer that writes /Parent and forgets
            // /Kids leaves the field's value right here, and the field owns
            // nothing else, so detaching it takes the value out without
            // touching anything that belongs to somebody else. Pages and
            // other annotations never reach this line, the guard at the top
            // of the loop having refused them.
            if (PdfEditor.SCRUBBED_FIELD_KEYS.any { it in field }) detached[parent.objectNumber] = parent
            return
        }
        if (survivors.size == kids.size) {
            // /Kids is not empty but does not name the child the walk
            // arrived from: the mutual link (12.7.3.3) is missing on this
            // side too, so this candidate is not that widget's field. It
            // can carry a real /V and /T of its own, for a field nobody
            // redacted, so it is left untouched, /Kids included.
            return
        }
        if (survivors.isNotEmpty()) {
            updateObject(
                parent,
                withEntry(field, "Kids", io.github.yuroyami.kitepdf.core.parser.PdfArray(survivors)),
            )
            return
        }
        detached[parent.objectNumber] = parent
        child = parent
        parent = field.getRef("Parent") ?: return
    }
    throw IllegalStateException(
        "Form field /Parent chain loops at object ${parent.objectNumber}; the redacted widget's field " +
            "cannot be detached from /AcroForm /Fields, which would leave its value in the file.",
    )
}

internal fun PdfEditor.rectsIntersect(a: KiteRectangle, b: KiteRectangle): Boolean =
    a.left < b.right && a.right > b.left && a.bottom < b.top && a.top > b.bottom

internal fun PdfEditor.loadPageFonts(resources: PdfDictionary?): Map<String, PdfFont> {
    val fontDict = resources?.getDict("Font", effective) ?: return emptyMap()
    val out = LinkedHashMap<String, PdfFont>()
    for ((name, value) in fontDict.map) out[name] = PdfFont.from(value, effective)
    return out
}

internal fun PdfEditor.loadImageXObjectNames(resources: PdfDictionary?): Set<String> {
    val xobjects = resources?.getDict("XObject", effective) ?: return emptySet()
    val out = HashSet<String>()
    for ((name, value) in xobjects.map) {
        val stream = value.resolve(effective) as? PdfStream ?: continue
        if (stream.dict.getName("Subtype") == "Image") out.add(name)
    }
    return out
}

internal fun PdfEditor.loadFormXObjectNames(resources: PdfDictionary?): Set<String> {
    val xobjects = resources?.getDict("XObject", effective) ?: return emptySet()
    val out = HashSet<String>()
    for ((name, value) in xobjects.map) {
        val stream = value.resolve(effective) as? PdfStream ?: continue
        if (stream.dict.getName("Subtype") == "Form") out.add(name)
    }
    return out
}

/** Per-name form `/Matrix` (default identity when absent). */
internal fun PdfEditor.loadFormMatrices(resources: PdfDictionary?): Map<String, io.github.yuroyami.kitepdf.core.render.KiteMatrix> {
    val xobjects = resources?.getDict("XObject", effective) ?: return emptyMap()
    val out = LinkedHashMap<String, io.github.yuroyami.kitepdf.core.render.KiteMatrix>()
    for ((name, value) in xobjects.map) {
        val stream = value.resolve(effective) as? PdfStream ?: continue
        if (stream.dict.getName("Subtype") != "Form") continue
        val m = stream.dict.getArray("Matrix", effective) ?: continue
        if (m.size < 6) continue
        fun n(i: Int): Double = when (val v = m[i].resolve(effective)) {
            is PdfInt -> v.value.toDouble()
            is io.github.yuroyami.kitepdf.core.parser.PdfReal -> v.value
            else -> 0.0
        }
        out[name] = io.github.yuroyami.kitepdf.core.render.KiteMatrix(n(0), n(1), n(2), n(3), n(4), n(5))
    }
    return out
}

/**
 * Per-name form `/BBox`, the box outside which a form paints nothing
 * (ISO 32000-1, 8.10.2, Table 95). A form missing a readable one is left out of
 * the map, and [RedactionEngine] then treats every invocation of it as
 * intersecting, which over-redacts rather than trusting a malformed form (R6).
 */
internal fun PdfEditor.loadFormBBoxes(resources: PdfDictionary?): Map<String, KiteRectangle> {
    val xobjects = resources?.getDict("XObject", effective) ?: return emptyMap()
    val out = LinkedHashMap<String, KiteRectangle>()
    for ((name, value) in xobjects.map) {
        val stream = value.resolve(effective) as? PdfStream ?: continue
        if (stream.dict.getName("Subtype") != "Form") continue
        val b = stream.dict.getArray("BBox", effective) ?: continue
        if (b.size < 4) continue
        fun n(i: Int): Double? = when (val v = b[i].resolve(effective)) {
            is PdfInt -> v.value.toDouble()
            is io.github.yuroyami.kitepdf.core.parser.PdfReal -> v.value
            else -> null
        }
        val x0 = n(0) ?: continue
        val y0 = n(1) ?: continue
        val x1 = n(2) ?: continue
        val y1 = n(3) ?: continue
        // The array is [llx lly urx ury] but writers do emit it flipped.
        out[name] = KiteRectangle(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
    }
    return out
}
