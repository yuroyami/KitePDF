package com.yuroyami.kitepdf.writer

import com.yuroyami.kitepdf.PdfDocument
import com.yuroyami.kitepdf.PdfFormField
import com.yuroyami.kitepdf.PdfPage
import com.yuroyami.kitepdf.Rectangle
import com.yuroyami.kitepdf.content.ContentStreamParser
import com.yuroyami.kitepdf.content.Operation
import com.yuroyami.kitepdf.core.ByteArrayBuilder
import com.yuroyami.kitepdf.core.ByteReader
import com.yuroyami.kitepdf.font.PdfFont
import com.yuroyami.kitepdf.parser.PdfBoolean
import com.yuroyami.kitepdf.parser.PdfDictionary
import com.yuroyami.kitepdf.parser.PdfInt
import com.yuroyami.kitepdf.parser.PdfName
import com.yuroyami.kitepdf.parser.PdfObject
import com.yuroyami.kitepdf.parser.PdfReference
import com.yuroyami.kitepdf.parser.PdfStream
import com.yuroyami.kitepdf.parser.PdfString
import com.yuroyami.kitepdf.parser.XrefParser
import kotlin.math.abs

/**
 * Incremental-update writer (ISO 32000-1 §7.5.6).
 *
 * Edits to a PDF are saved by **appending** to the original file, never
 * rewriting it: the original bytes are preserved verbatim, the changed/new
 * objects are written after them, and a fresh cross-reference section + trailer
 * (whose `/Prev` points back to the document's previous xref) is appended last.
 *
 * This is the safest writer architecture for correctness — the original is
 * untouched, so a reader that ignores the update still sees the original
 * document — and it is the foundation for digital signing (which signs the
 * appended byte range). KitePDF's own reader already follows `/Prev` chains,
 * so a saved document re-opens with the edits applied and the originals intact.
 *
 * Usage:
 * ```
 * val editor = doc.edit()
 * editor.setInfo(title = "New Title")
 * val newRef = editor.addObject(PdfDictionary(mapOf("Type" to PdfName("Foo"))))
 * val bytes = editor.saveIncremental()
 * ```
 *
 * Object numbers for [addObject] are allocated above the document's current
 * `/Size`. Use [addFlateStream]/[PdfStreams] to control stream compression.
 * Incrementally writing **encrypted** documents is not yet supported — newly
 * written strings/streams would need to be encrypted to match the document's
 * security handler.
 */
class PdfEditor internal constructor(private val base: PdfDocument) {

    private class Staged(val generation: Int, val value: PdfObject)

    /** objectNumber → staged (new or replacement) object, in insertion order. */
    private val staged = LinkedHashMap<Long, Staged>()

    /** Trailer entries to set/replace in the appended section's trailer. */
    private val trailerOverrides = LinkedHashMap<String, PdfObject>()

    /**
     * Set once a redaction is staged. Incremental save is then refused: it would
     * append the new content while leaving the original (unredacted) bytes in the
     * file, where they remain recoverable — defeating redaction.
     */
    private var redactionStaged = false

    private var nextObjectNumber: Long

    init {
        require(!base.isEncrypted) {
            "Incremental writing of encrypted PDFs is not yet supported."
        }
        val maxInXref = base.xref.keys.maxOrNull() ?: 0L
        val sizeFloor = (base.trailer.getInt("Size") ?: 0L) - 1
        nextObjectNumber = maxOf(maxInXref, sizeFloor, 0L) + 1
    }

    /** Reserve the next free object number (generation 0). */
    fun allocateReference(): PdfReference = PdfReference(nextObjectNumber++, 0)

    /** Stage a brand-new indirect object; returns the reference to it. */
    fun addObject(value: PdfObject): PdfReference {
        val ref = allocateReference()
        staged[ref.objectNumber] = Staged(ref.generation, value)
        return ref
    }

    /** Stage a replacement for an existing object (keeps [ref]'s generation). */
    fun updateObject(ref: PdfReference, value: PdfObject) {
        staged[ref.objectNumber] = Staged(ref.generation, value)
    }

    /**
     * Stage a new `/FlateDecode`-compressed stream from uncompressed [data]
     * (see [PdfStreams.flate]); returns the reference to it.
     */
    fun addFlateStream(data: ByteArray, extra: Map<String, PdfObject> = emptyMap()): PdfReference =
        addObject(PdfStreams.flate(data, extra))

    /** Set or replace a trailer entry (e.g. `/Root`, `/Info`) in the new section. */
    fun setTrailerEntry(key: String, value: PdfObject) {
        trailerOverrides[key] = value
    }

    /** Number of objects staged for writing. */
    val pendingObjectCount: Int get() = staged.size

    /**
     * Set document metadata (`/Info`). Only non-null fields are changed; any
     * existing `/Info` entries (standard or custom) are preserved. Updates the
     * existing `/Info` object in place if the trailer references one, otherwise
     * creates a new `/Info` object and points the trailer at it. Returns the
     * reference to the (possibly newly created) `/Info` dictionary.
     */
    fun setInfo(
        title: String? = null,
        author: String? = null,
        subject: String? = null,
        keywords: String? = null,
        creator: String? = null,
        producer: String? = null,
    ): PdfReference {
        val existingRef = base.trailer.getRef("Info")
        val existing = when (val raw = base.trailer["Info"]) {
            is PdfReference -> base.resolve(raw) as? PdfDictionary
            is PdfDictionary -> raw
            else -> null
        }
        val merged = LinkedHashMap<String, PdfObject>()
        existing?.map?.let { merged.putAll(it) }

        fun put(key: String, value: String?) {
            if (value != null) merged[key] = PdfString(PdfText.encodeTextString(value))
        }
        put("Title", title)
        put("Author", author)
        put("Subject", subject)
        put("Keywords", keywords)
        put("Creator", creator)
        put("Producer", producer)

        val dict = PdfDictionary(merged)
        return if (existingRef != null) {
            updateObject(existingRef, dict)
            existingRef
        } else {
            val ref = addObject(dict)
            setTrailerEntry("Info", ref)
            ref
        }
    }

    /* ─── Page content editing ───────────────────────────────────────────── */

    /**
     * Rewrite [page]'s content stream by [transform]ing its parsed operations.
     * The page's decoded content is parsed (see [ContentStreamParser]), passed
     * to [transform], re-serialized, written as a new compressed stream, and
     * the page's `/Contents` is repointed at it. The original content objects
     * are left in place (orphaned) per incremental-update semantics.
     *
     * Note: [transform] only reorders/removes/keeps existing operations — it
     * doesn't introduce new resource dependencies. To overlay new content (with
     * its own fonts), use [stampPage].
     */
    fun editPageContent(page: PdfPage, transform: (List<Operation>) -> List<Operation>) {
        val ref = pageReference(page)
        val ops = ContentStreamParser.parse(page.contentBytes)
        val newContent = ContentStreamWriter.serialize(transform(ops))
        val streamRef = addObject(PdfStreams.flate(newContent))
        updateObject(ref, withEntry(page.dictionary, "Contents", streamRef))
    }

    /**
     * Remove all text-showing operations (`Tj`, `TJ`, `'`, `"`) from [page].
     *
     * This is a text-stripping primitive, NOT secure region redaction: it drops
     * every show-text operator on the page (not images, not a chosen rectangle).
     * True redaction (removing only content within an area, including images)
     * needs text-position tracking and is a later feature.
     */
    fun removeAllText(page: PdfPage) {
        editPageContent(page) { ops -> ops.filter { it.operator !in TEXT_SHOW_OPERATORS } }
    }

    /**
     * Overlay content onto an existing [page] (a stamp/watermark) drawn by
     * [block]. The existing content is preserved (wrapped in `q`/`Q` so its
     * graphics state can't leak into the overlay), the overlay is appended in
     * its own `q`/`Q`, and any standard fonts the overlay uses are merged into
     * the page's `/Resources` under fresh, non-colliding names.
     */
    fun stampPage(page: PdfPage, block: ContentStreamBuilder.() -> Unit) {
        val ref = pageReference(page)

        val existingFonts = page.resources?.getDict("Font", base)
        val usedNames = HashSet<String>(existingFonts?.keys ?: emptySet())
        val stampFonts = LinkedHashMap<StandardFont, String>()
        fun resolveStampFont(font: StandardFont): String = stampFonts.getOrPut(font) {
            var i = 1
            while ("KF$i" in usedNames) i++
            "KF$i".also { usedNames.add(it) }
        }

        val csb = ContentStreamBuilder(::resolveStampFont)
        csb.block()
        val stampBytes = csb.toByteArray()

        val merged = ByteArrayBuilder(page.contentBytes.size + stampBytes.size + 16)
        merged.append("q\n".encodeToByteArray())
        merged.append(page.contentBytes)
        merged.append("\nQ\nq\n".encodeToByteArray())
        merged.append(stampBytes)
        merged.append("Q\n".encodeToByteArray())
        val streamRef = addObject(PdfStreams.flate(merged.toByteArray()))

        // Merge the overlay's fonts into the page's resources.
        val fontDict = LinkedHashMap<String, PdfObject>()
        existingFonts?.map?.let { fontDict.putAll(it) }
        for ((font, name) in stampFonts) {
            fontDict[name] = addObject(
                PdfDictionary(
                    linkedMapOf(
                        "Type" to PdfName("Font"),
                        "Subtype" to PdfName("Type1"),
                        "BaseFont" to PdfName(font.baseFont),
                    ),
                ),
            )
        }
        val resources = LinkedHashMap<String, PdfObject>()
        page.resources?.map?.let { resources.putAll(it) }
        resources["Font"] = PdfDictionary(fontDict)

        var newPage = withEntry(page.dictionary, "Contents", streamRef)
        newPage = withEntry(newPage, "Resources", PdfDictionary(resources))
        updateObject(ref, newPage)
    }

    /* ─── Form filling ───────────────────────────────────────────────────── */

    /**
     * Fill a text form field: set its value (`/V`) and regenerate the widget's
     * normal appearance (`/AP /N`) so the value is visible in any viewer. The
     * appearance honours the field's `/DA` (font, size, colour) and is clipped
     * to the field rectangle. Also clears the form's `/NeedAppearances` flag (if
     * set) so viewers use the appearance we just generated.
     *
     * Only `/Tx` (text) fields are supported; buttons/choices come later.
     */
    fun setTextFieldValue(field: PdfFormField, value: String) {
        require(field.type == PdfFormField.FieldType.Text) {
            "setTextFieldValue supports text (/Tx) fields only, not ${field.type}"
        }
        val fieldRef = field.fieldReference
            ?: throw IllegalArgumentException("Field '${field.fullyQualifiedName}' has no indirect reference")
        val rect = field.rect
            ?: throw IllegalArgumentException("Field '${field.fullyQualifiedName}' has no widget /Rect for an appearance")

        val fontRef = addObject(
            PdfDictionary(
                linkedMapOf(
                    "Type" to PdfName("Font"),
                    "Subtype" to PdfName("Type1"),
                    "BaseFont" to PdfName("Helvetica"),
                ),
            ),
        )
        val da = FieldAppearance.parseDA(field.defaultAppearance)
        val ap = FieldAppearance.build(value, abs(rect.width), abs(rect.height), da, fontRef)
        val apDict = PdfDictionary(linkedMapOf("N" to (addObject(ap) as PdfObject)))
        val vStr = PdfString(PdfText.encodeTextString(value))

        val widgetRef = field.widgetReference ?: fieldRef
        if (widgetRef == fieldRef) {
            // Merged field+widget: one object carries both /V and /AP.
            val d = LinkedHashMap(field.fieldDict.map)
            d["V"] = vStr
            d["AP"] = apDict
            updateObject(fieldRef, PdfDictionary(d))
        } else {
            updateObject(fieldRef, withEntry(field.fieldDict, "V", vStr))
            updateObject(widgetRef, withEntry(field.widgetDict, "AP", apDict))
        }
        clearNeedAppearances()
    }

    /** If the AcroForm has `/NeedAppearances true`, flip it false so our `/AP` is used. */
    private fun clearNeedAppearances() {
        val acroRef = base.catalog["AcroForm"] as? PdfReference ?: return
        val acro = base.resolve(acroRef) as? PdfDictionary ?: return
        if ((acro["NeedAppearances"] as? PdfBoolean)?.value == true) {
            updateObject(acroRef, withEntry(acro, "NeedAppearances", PdfBoolean(false)))
        }
    }

    /* ─── Redaction ──────────────────────────────────────────────────────── */

    /** Redact a single rectangular region of [page] (see [redactRegions]). */
    fun redactRegion(page: PdfPage, rectangle: Rectangle) = redactRegions(page, listOf(rectangle))

    /**
     * Redact rectangular regions of [page] (rectangles in page user space).
     *
     * This is **true** redaction: text whose box intersects a region has its
     * bytes REMOVED from the content stream (so it can't be extracted or
     * recovered), surviving text keeps its position, intersecting images are
     * dropped from the page, and an opaque black box is painted over each
     * region. It does not merely paint over still-present content.
     *
     * Conservative by design — a run touching a region is removed wholesale, so
     * partial overlaps over-remove. Current limits ([RedactionEngine]): vector
     * paths in the region are left as-is, content inside referenced form
     * XObjects isn't recursed into, and a dropped image's data object is not yet
     * purged from the file (it's no longer drawn or referenced by content).
     */
    fun redactRegions(page: PdfPage, rectangles: List<Rectangle>) {
        if (rectangles.isEmpty()) return
        val ref = pageReference(page)

        val ops = ContentStreamParser.parse(page.contentBytes)
        val filtered = RedactionEngine(loadPageFonts(page), loadImageXObjectNames(page), rectangles).run(ops)
        val body = ContentStreamWriter.serialize(filtered)

        val out = ByteArrayBuilder(body.size + 64)
        out.append("q\n".encodeToByteArray())
        out.append(body)
        out.append("\nQ\n".encodeToByteArray())
        for (r in rectangles) {
            val box = "q 0 g ${fmt(r.left)} ${fmt(r.bottom)} ${fmt(r.width)} ${fmt(r.height)} re f Q\n"
            out.append(box.encodeToByteArray())
        }
        val streamRef = addObject(PdfStreams.flate(out.toByteArray()))
        updateObject(ref, withEntry(page.dictionary, "Contents", streamRef))
        redactionStaged = true
    }

    private fun loadPageFonts(page: PdfPage): Map<String, PdfFont> {
        val fontDict = page.resources?.getDict("Font", base) ?: return emptyMap()
        val out = LinkedHashMap<String, PdfFont>()
        for ((name, value) in fontDict.map) out[name] = PdfFont.from(value, base)
        return out
    }

    private fun loadImageXObjectNames(page: PdfPage): Set<String> {
        val xobjects = page.resources?.getDict("XObject", base) ?: return emptySet()
        val out = HashSet<String>()
        for ((name, value) in xobjects.map) {
            val stream = value.resolve(base) as? PdfStream ?: continue
            if (stream.dict.getName("Subtype") == "Image") out.add(name)
        }
        return out
    }

    private fun fmt(d: Double): String = PdfObjectWriter.formatReal(d)

    private fun pageReference(page: PdfPage): PdfReference = page.reference
        ?: throw IllegalArgumentException("Page ${page.index} has no indirect reference and cannot be edited")

    private fun withEntry(dict: PdfDictionary, key: String, value: PdfObject): PdfDictionary =
        PdfDictionary(LinkedHashMap(dict.map).apply { put(key, value) })

    /**
     * Produce the updated document bytes: original + appended objects + new
     * xref section + trailer. When nothing was staged or overridden this is a
     * verbatim copy of the original.
     */
    fun saveIncremental(): ByteArray {
        check(!redactionStaged) {
            "Redaction was staged; call saveRewritten() instead — an incremental save " +
                "would leave the original, unredacted content recoverable in the file."
        }
        if (staged.isEmpty() && trailerOverrides.isEmpty()) return base.bytes.copyOf()

        val out = ByteArrayBuilder(base.bytes.size + 1024)
        out.append(base.bytes)
        // The appended section must start on its own line so byte offsets and
        // the "N G obj" header parse cleanly regardless of how the original ended.
        val last = base.bytes.lastOrNull()
        if (last != null && last != '\n'.code.toByte() && last != '\r'.code.toByte()) {
            out.append('\n'.code.toByte())
        }

        val offsets = LinkedHashMap<Long, Int>()
        for ((num, s) in staged.entries.sortedBy { it.key }) {
            offsets[num] = out.size()
            out.append("$num ${s.generation} obj\n".encodeToByteArray())
            PdfObjectWriter.writeObject(s.value, out)
            out.append("\nendobj\n".encodeToByteArray())
        }

        val xrefOffset = out.size()
        writeClassicXref(out, offsets)
        writeTrailer(out, xrefOffset)
        return out.toByteArray()
    }

    /* ─── xref + trailer ─────────────────────────────────────────────────── */

    /**
     * A classic cross-reference section listing only the changed objects (plus
     * the free-list head). The incremental section need not enumerate untouched
     * objects — the reader fills those from the `/Prev` chain.
     */
    private fun writeClassicXref(out: ByteArrayBuilder, offsets: Map<Long, Int>) {
        val entries = offsets.map { (num, off) ->
            ClassicXrefWriter.Entry(num, off, staged.getValue(num).generation)
        }
        ClassicXrefWriter.write(out, entries)
    }

    private fun writeTrailer(out: ByteArrayBuilder, xrefOffset: Int) {
        val prevXref = XrefParser.findStartXref(ByteReader(base.bytes))
        val maxNum = maxOf(
            base.xref.keys.maxOrNull() ?: 0L,
            staged.keys.maxOrNull() ?: 0L,
        )

        val dict = LinkedHashMap<String, PdfObject>()
        dict["Size"] = PdfInt(maxNum + 1)
        base.trailer["Root"]?.let { dict["Root"] = it }
        base.trailer["Info"]?.let { dict["Info"] = it }
        base.trailer["Encrypt"]?.let { dict["Encrypt"] = it }
        base.trailer["ID"]?.let { dict["ID"] = it }
        dict["Prev"] = PdfInt(prevXref.toLong())
        for ((k, v) in trailerOverrides) dict[k] = v

        out.append("trailer\n".encodeToByteArray())
        PdfObjectWriter.writeObject(PdfDictionary(dict), out)
        out.append("\nstartxref\n$xrefOffset\n%%EOF\n".encodeToByteArray())
    }

    /* ─── Full rewrite (garbage-collected) ───────────────────────────────── */

    /**
     * Serialize a fresh, self-contained PDF containing only the objects
     * reachable from the catalog (and `/Info`), with staged edits applied and
     * objects renumbered densely. Unlike [saveIncremental], the original bytes
     * are NOT retained and unreachable objects (e.g. content streams replaced by
     * an edit) are dropped — which is what makes it the correct vehicle for
     * **redaction** (the removed content is truly gone, not just superseded).
     */
    fun saveRewritten(): ByteArray {
        val roots = buildList {
            for (key in listOf("Root", "Info")) {
                ((trailerOverrides[key] ?: base.trailer[key]) as? PdfReference)?.let { add(it) }
            }
        }

        // Reachability BFS over effective (staged-or-original) objects.
        val reachable = LinkedHashSet<Long>()
        val queue = ArrayDeque<Long>()
        for (r in roots) if (effectiveObject(r.objectNumber) != null && reachable.add(r.objectNumber)) {
            queue.addLast(r.objectNumber)
        }
        while (queue.isNotEmpty()) {
            val obj = effectiveObject(queue.removeFirst()) ?: continue
            collectReferences(obj) { refNum ->
                if (effectiveObject(refNum) != null && reachable.add(refNum)) queue.addLast(refNum)
            }
        }

        // Dense renumber, in ascending old-number order for determinism.
        val ordered = reachable.sorted()
        val remap = HashMap<Long, Long>(ordered.size)
        ordered.forEachIndexed { i, old -> remap[old] = (i + 1).toLong() }

        val out = ByteArrayBuilder(base.bytes.size)
        out.append("%PDF-1.7\n".encodeToByteArray())
        out.append('%'.code.toByte())
        out.append(byteArrayOf(0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte()))
        out.append('\n'.code.toByte())

        val xrefEntries = ArrayList<ClassicXrefWriter.Entry>(ordered.size)
        for (old in ordered) {
            val obj = effectiveObject(old) ?: continue
            val newNum = remap.getValue(old)
            xrefEntries.add(ClassicXrefWriter.Entry(newNum, out.size(), 0))
            out.append("$newNum 0 obj\n".encodeToByteArray())
            PdfObjectWriter.writeObject(remapReferences(obj, remap), out)
            out.append("\nendobj\n".encodeToByteArray())
        }

        val xrefOffset = out.size()
        ClassicXrefWriter.write(out, xrefEntries)

        val trailer = LinkedHashMap<String, PdfObject>()
        trailer["Size"] = PdfInt(ordered.size + 1L)
        for (key in listOf("Root", "Info")) {
            val ref = (trailerOverrides[key] ?: base.trailer[key]) as? PdfReference ?: continue
            remap[ref.objectNumber]?.let { trailer[key] = PdfReference(it, 0) }
        }
        out.append("trailer\n".encodeToByteArray())
        PdfObjectWriter.writeObject(PdfDictionary(trailer), out)
        out.append("\nstartxref\n$xrefOffset\n%%EOF\n".encodeToByteArray())
        return out.toByteArray()
    }

    /** Staged override if present, else the original object; null for free/missing. */
    private fun effectiveObject(num: Long): PdfObject? =
        staged[num]?.value ?: base.resolve(PdfReference(num, 0))

    private fun collectReferences(obj: PdfObject, visit: (Long) -> Unit) {
        when (obj) {
            is PdfReference -> visit(obj.objectNumber)
            is com.yuroyami.kitepdf.parser.PdfArray -> obj.items.forEach { collectReferences(it, visit) }
            is PdfDictionary -> obj.map.values.forEach { collectReferences(it, visit) }
            is PdfStream -> obj.dict.map.values.forEach { collectReferences(it, visit) }
            else -> {}
        }
    }

    private fun remapReferences(obj: PdfObject, remap: Map<Long, Long>): PdfObject = when (obj) {
        is PdfReference -> remap[obj.objectNumber]?.let { PdfReference(it, 0) }
            ?: com.yuroyami.kitepdf.parser.PdfNull
        is com.yuroyami.kitepdf.parser.PdfArray ->
            com.yuroyami.kitepdf.parser.PdfArray(obj.items.map { remapReferences(it, remap) })
        is PdfDictionary -> PdfDictionary(
            LinkedHashMap<String, PdfObject>().also { m -> obj.map.forEach { (k, v) -> m[k] = remapReferences(v, remap) } },
        )
        is PdfStream -> PdfStream(remapReferences(obj.dict, remap) as PdfDictionary, obj.rawBytes)
        else -> obj
    }

    private companion object {
        /** Text-showing operators (§9.4.3). */
        val TEXT_SHOW_OPERATORS = setOf("Tj", "TJ", "'", "\"")
    }
}
