package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormField
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.Rectangle
import io.github.yuroyami.kitepdf.content.ContentStreamParser
import io.github.yuroyami.kitepdf.content.Operation
import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.ByteReader
import io.github.yuroyami.kitepdf.font.PdfFont
import io.github.yuroyami.kitepdf.parser.PdfBoolean
import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfName
import io.github.yuroyami.kitepdf.parser.PdfObject
import io.github.yuroyami.kitepdf.parser.PdfReference
import io.github.yuroyami.kitepdf.parser.PdfStream
import io.github.yuroyami.kitepdf.parser.PdfString
import io.github.yuroyami.kitepdf.parser.XrefParser
import kotlin.math.abs

/**
 * Writer for edits to an existing PDF. Open one with [PdfDocument.edit].
 *
 * ```
 * val editor = doc.edit()
 * editor.setInfo(title = "New Title")
 * editor.stampPage(doc.pages[0]) {
 *     setFillRgb(0.8, 0.1, 0.1)
 *     text(StandardFont.HelveticaBold, 48.0, 120.0, 400.0, "DRAFT")
 * }
 * val bytes = editor.saveIncremental()
 * ```
 *
 * Two save modes:
 *
 *  - [saveIncremental] **appends** changes to the original bytes (ISO 32000-1
 *    §7.5.6). The original content is preserved verbatim, only the new/changed
 *    objects, a fresh xref section and a trailer pointing back via `/Prev` are
 *    written at the end. This is the right mode for ordinary edits and the
 *    foundation for digital signing (which signs the appended byte range).
 *  - [saveRewritten] writes a fresh, garbage-collected file containing only
 *    objects reachable from the catalog, with edits applied and unreachable
 *    objects dropped. Required for **redaction**, since removed content is
 *    truly gone rather than retained in the original byte prefix.
 *
 * **Encrypted** documents (V4 AES-128 and V5 AES-256) are supported when the
 * password authenticated: every staged object is encrypted with the SAME
 * parameters as the base document at [saveIncremental] time and the trailer
 * keeps the original `/Encrypt`. Legacy RC4 documents are refused (read-only).
 * Note [saveRewritten] instead emits a DECRYPTED file: the rewrite drops the
 * `/Encrypt` dictionary and writes the resolved plain-text objects.
 */
class PdfEditor internal constructor(
    private val base: PdfDocument,
    random: kotlin.random.Random = kotlin.random.Random.Default,
) {

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

    /** Re-encrypts staged objects for an encrypted base document; null for plain docs. */
    private val encryptor: io.github.yuroyami.kitepdf.crypto.Encryptor?

    init {
        val handler = base.securityHandler
        encryptor = if (handler == null) {
            null
        } else {
            require(handler.isAuthenticated) {
                "Cannot edit an encrypted PDF that did not authenticate; reopen it with the password."
            }
            require(handler.supportsWrite) {
                "Editing RC4-encrypted PDFs is not supported (legacy write support is intentionally absent); " +
                    "AES-128 (V4) and AES-256 (V5) documents can be edited."
            }
            io.github.yuroyami.kitepdf.crypto.Encryptor(handler, random)
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

    /**
     * Set a button field (`/Btn`) to a named export value — the mechanism behind
     * checkboxes and radio groups. The field's `/V` becomes the chosen on-state
     * name; every widget's `/AS` is set to that name when the widget defines it
     * as an appearance state, or to `/Off` otherwise (so sibling radios in the
     * group are cleared). Pass `"Off"` to clear the field.
     */
    fun setButtonValue(field: PdfFormField, exportValue: String) {
        require(field.type == PdfFormField.FieldType.Button) {
            "setButtonValue supports button (/Btn) fields only, not ${field.type}"
        }
        val fieldRef = field.fieldReference
            ?: throw IllegalArgumentException("Field '${field.fullyQualifiedName}' has no indirect reference")

        // Stage per-object overrides, merging so a merged field+widget gets both
        // /V and /AS in one updated dictionary.
        val pending = LinkedHashMap<Long, Pair<PdfReference, LinkedHashMap<String, PdfObject>>>()
        fun put(ref: PdfReference, baseDict: PdfDictionary, key: String, value: PdfObject) {
            val e = pending.getOrPut(ref.objectNumber) { ref to LinkedHashMap(baseDict.map) }
            e.second[key] = value
        }

        put(fieldRef, field.fieldDict, "V", PdfName(exportValue))
        for ((wref, wdict) in buttonWidgets(field)) {
            val states = appearanceStateNames(wdict)
            val asName = if (exportValue in states) exportValue else "Off"
            put(wref, wdict, "AS", PdfName(asName))
        }
        for ((_, e) in pending) updateObject(e.first, PdfDictionary(e.second))
        clearNeedAppearances()
    }

    /**
     * Check or uncheck a checkbox field. The "on" state name is taken from the
     * widget's `/AP /N` keys (the non-`Off` one, e.g. `/Yes`), so the value
     * matches whatever the document author defined.
     */
    fun setCheckbox(field: PdfFormField, checked: Boolean) {
        val on = checkboxOnState(field) ?: "On"
        setButtonValue(field, if (checked) on else "Off")
    }

    /**
     * Set a choice field (`/Ch` — combo box or list box) to [value]. Sets `/V`,
     * sets `/I` (selected index) when the value is found in `/Opt`, and
     * regenerates the widget appearance so the selection is visible.
     */
    fun setChoiceValue(field: PdfFormField, value: String) {
        require(field.type == PdfFormField.FieldType.Choice) {
            "setChoiceValue supports choice (/Ch) fields only, not ${field.type}"
        }
        val fieldRef = field.fieldReference
            ?: throw IllegalArgumentException("Field '${field.fullyQualifiedName}' has no indirect reference")
        val vStr = PdfString(PdfText.encodeTextString(value))

        // Build the appearance (same path as a text field shows its value).
        val apDict = field.rect?.let { rect ->
            val fontRef = addObject(PdfDictionary(linkedMapOf(
                "Type" to PdfName("Font"), "Subtype" to PdfName("Type1"), "BaseFont" to PdfName("Helvetica"),
            )))
            val da = FieldAppearance.parseDA(field.defaultAppearance)
            val ap = FieldAppearance.build(value, abs(rect.width), abs(rect.height), da, fontRef)
            PdfDictionary(linkedMapOf("N" to (addObject(ap) as PdfObject)))
        }

        // /I selected-index, when the value is one of /Opt.
        val selectedIndex = optionIndex(field, value)

        val widgetRef = field.widgetReference ?: fieldRef
        if (widgetRef == fieldRef) {
            val d = LinkedHashMap(field.fieldDict.map)
            d["V"] = vStr
            if (selectedIndex >= 0) d["I"] = io.github.yuroyami.kitepdf.parser.PdfArray(listOf(PdfInt(selectedIndex.toLong())))
            if (apDict != null) d["AP"] = apDict
            updateObject(fieldRef, PdfDictionary(d))
        } else {
            val fd = LinkedHashMap(field.fieldDict.map)
            fd["V"] = vStr
            if (selectedIndex >= 0) fd["I"] = io.github.yuroyami.kitepdf.parser.PdfArray(listOf(PdfInt(selectedIndex.toLong())))
            updateObject(fieldRef, PdfDictionary(fd))
            if (apDict != null) updateObject(widgetRef, withEntry(field.widgetDict, "AP", apDict))
        }
        clearNeedAppearances()
    }

    /** Widget annotations of a button field: its /Kids, or the merged field itself. */
    private fun buttonWidgets(field: PdfFormField): List<Pair<PdfReference, PdfDictionary>> {
        val kids = field.fieldDict.getArray("Kids", base)
        if (kids != null && kids.isNotEmpty()) {
            return kids.mapNotNull { k ->
                val r = k as? PdfReference ?: return@mapNotNull null
                val d = base.resolve(r) as? PdfDictionary ?: return@mapNotNull null
                r to d
            }
        }
        val ref = field.widgetReference ?: field.fieldReference ?: return emptyList()
        return listOf(ref to field.widgetDict)
    }

    /** The appearance-state names declared under a widget's /AP /N. */
    private fun appearanceStateNames(widget: PdfDictionary): Set<String> {
        val n = widget.getDict("AP", base)?.get("N")?.resolve(base) as? PdfDictionary ?: return emptySet()
        return n.keys
    }

    /** The checkbox "on" state — the first non-Off /AP /N appearance name. */
    private fun checkboxOnState(field: PdfFormField): String? {
        for ((_, w) in buttonWidgets(field)) {
            appearanceStateNames(w).firstOrNull { it != "Off" }?.let { return it }
        }
        return null
    }

    /** Index of [value] within the field's /Opt array, or -1. /Opt entries may be
     *  strings or [export, display] pairs. */
    private fun optionIndex(field: PdfFormField, value: String): Int {
        val opt = field.fieldDict.getArray("Opt", base) ?: return -1
        opt.forEachIndexed { i, entry ->
            val text = when (val e = entry.resolve(base)) {
                is PdfString -> e.asText()
                is io.github.yuroyami.kitepdf.parser.PdfArray ->
                    (e.getOrNull(0) as? PdfString)?.asText() ?: (e.getOrNull(1) as? PdfString)?.asText()
                else -> null
            }
            if (text == value) return i
        }
        return -1
    }

    /** If the AcroForm has `/NeedAppearances true`, flip it false so our `/AP` is used. */
    private fun clearNeedAppearances() {
        val acroRef = base.catalog["AcroForm"] as? PdfReference ?: return
        val acro = base.resolve(acroRef) as? PdfDictionary ?: return
        if ((acro["NeedAppearances"] as? PdfBoolean)?.value == true) {
            updateObject(acroRef, withEntry(acro, "NeedAppearances", PdfBoolean(false)))
        }
    }

    /* ─── Page operations ────────────────────────────────────────────────── */

    /** Tracked page order, seeded from the base document on first structural op. */
    private var pageOrderState: MutableList<PdfReference>? = null

    private fun currentOrder(): MutableList<PdfReference> =
        pageOrderState ?: base.pages.mapNotNull { it.reference }.toMutableList().also { pageOrderState = it }

    /**
     * Set the page `/Rotate` (clockwise, must be a multiple of 90). Normalised to
     * 0/90/180/270.
     */
    fun rotatePage(page: PdfPage, degrees: Int) {
        require(degrees % 90 == 0) { "Rotation must be a multiple of 90, got $degrees" }
        val ref = pageReference(page)
        val norm = ((degrees % 360) + 360) % 360
        updateObject(ref, withEntry(effectivePageDict(ref), "Rotate", PdfInt(norm.toLong())))
    }

    /**
     * Replace the document's page order with exactly [orderedPageRefs]. Rebuilds a
     * single flat `/Pages` node (kids + /Count), re-parents every page to it, and
     * keeps the catalog pointing at it. This is the engine behind delete, reorder,
     * insert and merge: omit a ref to delete, permute to reorder, append a grafted
     * ref to insert. Pages no longer referenced are dropped by [saveRewritten].
     */
    fun setPageOrder(orderedPageRefs: List<PdfReference>) {
        require(orderedPageRefs.isNotEmpty()) { "A document must have at least one page" }
        pageOrderState = orderedPageRefs.toMutableList()
        applyPageOrder()
    }

    /** Remove [page] from the document. */
    fun removePage(page: PdfPage) {
        val target = page.reference?.objectNumber
            ?: throw IllegalArgumentException("Page ${page.index} has no indirect reference")
        val order = currentOrder()
        order.removeAll { it.objectNumber == target }
        require(order.isNotEmpty()) { "Cannot remove the last page" }
        applyPageOrder()
    }

    /** Append a page deep-copied from [source] (see [graftPage]); returns its new ref. */
    fun appendPage(source: PdfDocument, sourceIndex: Int): PdfReference {
        val ref = graftPage(source, sourceIndex)
        currentOrder().add(ref)
        applyPageOrder()
        return ref
    }

    /** Insert a (grafted) page reference at zero-based [position]. */
    fun insertPageAt(position: Int, pageRef: PdfReference) {
        val order = currentOrder()
        order.add(position.coerceIn(0, order.size), pageRef)
        applyPageOrder()
    }

    /** Append every page of [source] to this document (cross-document merge). */
    fun mergeDocument(source: PdfDocument) {
        val refs = source.pages.indices.map { graftPage(source, it) }
        currentOrder().addAll(refs)
        applyPageOrder()
    }

    /** Rebuild the flat `/Pages` node + re-parent from the tracked order. */
    private fun applyPageOrder() {
        val order = currentOrder()
        val pagesRef = base.catalog.getRef("Pages")
            ?: throw IllegalStateException("Catalog /Pages is not an indirect reference; cannot reorganise pages")
        updateObject(
            pagesRef,
            PdfDictionary(linkedMapOf(
                "Type" to PdfName("Pages"),
                "Kids" to io.github.yuroyami.kitepdf.parser.PdfArray(order.toList()),
                "Count" to PdfInt(order.size.toLong()),
            )),
        )
        // Flattening to one /Pages node can strip attributes a leaf page inherited
        // from an intermediate node — so bake the resolved MediaBox/Resources/Rotate
        // onto each base page that doesn't carry its own.
        val baseByNum = base.pages.mapNotNull { p -> p.reference?.let { it.objectNumber to p } }.toMap()
        for (pref in order) {
            val pd = effectiveObject(pref.objectNumber) as? PdfDictionary ?: continue
            val m = LinkedHashMap(pd.map)
            m["Parent"] = pagesRef
            baseByNum[pref.objectNumber]?.let { bp ->
                if ("MediaBox" !in m) m["MediaBox"] = rectToArray(bp.mediaBox)
                if ("Resources" !in m) bp.resources?.let { r -> m["Resources"] = r }
                if ("Rotate" !in m && bp.rotation != 0) m["Rotate"] = PdfInt(bp.rotation.toLong())
            }
            updateObject(pref, PdfDictionary(m))
        }
    }

    /**
     * Deep-copy one page (and its full transitive object graph: resources, fonts,
     * content streams, XObjects) from [source] into this editor under fresh object
     * numbers, returning the new page reference. Inherited `/MediaBox`,
     * `/Resources`, `/Rotate` are baked onto the copied page so it is
     * self-contained, and `/Parent` is dropped (set later by [applyPageOrder]).
     * Mirrors MuPDF's `pdf_graft_page`.
     */
    fun graftPage(source: PdfDocument, sourceIndex: Int): PdfReference {
        val page = source.pages.getOrNull(sourceIndex)
            ?: throw IllegalArgumentException("Source has no page $sourceIndex")
        val srcPageNum = page.reference?.objectNumber
            ?: throw IllegalArgumentException("Source page $sourceIndex has no indirect reference")

        val effective = graftablePageDict(page)

        // BFS the source graph, allocating a new number per visited source object.
        val refMap = HashMap<Long, Long>()
        val queue = ArrayDeque<Long>()
        fun enqueue(srcNum: Long) {
            if (srcNum !in refMap) { refMap[srcNum] = allocateReference().objectNumber; queue.addLast(srcNum) }
        }
        val newPageNum = allocateReference().objectNumber
        refMap[srcPageNum] = newPageNum
        collectReferences(effective) { enqueue(it) }
        while (queue.isNotEmpty()) {
            val obj = source.resolve(PdfReference(queue.removeFirst(), 0)) ?: continue
            collectReferences(obj) { enqueue(it) }
        }

        // Stage remapped copies of the page and every reachable object.
        staged[newPageNum] = Staged(0, remapReferences(effective, refMap))
        for ((srcNum, newNum) in refMap) {
            if (newNum == newPageNum) continue
            val obj = source.resolve(PdfReference(srcNum, 0)) ?: continue
            staged[newNum] = Staged(0, remapReferences(obj, refMap))
        }
        return PdfReference(newPageNum, 0)
    }

    /** A self-contained copy of a page dict: inheritance baked in, /Parent removed. */
    private fun graftablePageDict(page: PdfPage): PdfDictionary {
        val m = LinkedHashMap(page.dictionary.map)
        m.remove("Parent")
        m["Type"] = PdfName("Page")
        if ("MediaBox" !in m) m["MediaBox"] = rectToArray(page.mediaBox)
        if ("Resources" !in m) page.resources?.let { m["Resources"] = it }
        if ("Rotate" !in m && page.rotation != 0) m["Rotate"] = PdfInt(page.rotation.toLong())
        return PdfDictionary(m)
    }

    private fun rectToArray(r: Rectangle): io.github.yuroyami.kitepdf.parser.PdfArray =
        io.github.yuroyami.kitepdf.parser.PdfArray(listOf(
            io.github.yuroyami.kitepdf.parser.PdfReal(r.left),
            io.github.yuroyami.kitepdf.parser.PdfReal(r.bottom),
            io.github.yuroyami.kitepdf.parser.PdfReal(r.right),
            io.github.yuroyami.kitepdf.parser.PdfReal(r.top),
        ))

    /** Effective (staged-or-base) page dictionary for [ref]. */
    private fun effectivePageDict(ref: PdfReference): PdfDictionary =
        effectiveObject(ref.objectNumber) as? PdfDictionary
            ?: throw IllegalArgumentException("Page ${ref.objectNumber} did not resolve to a dictionary")

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
     * partial overlaps over-remove. Content inside referenced form XObjects IS
     * recursed into (redacted in the form's own coordinate space); a dropped
     * image's XObject entry is pruned from `/Resources /XObject` so
     * [saveRewritten]'s reachability GC drops the image stream; and annotations
     * whose `/Rect` intersects a region are removed from the page `/Annots`.
     *
     * Current limit ([RedactionEngine]): vector paths in the region are left as-is.
     */
    fun redactRegions(page: PdfPage, rectangles: List<Rectangle>) {
        if (rectangles.isEmpty()) return
        val ref = pageReference(page)

        val ops = ContentStreamParser.parse(page.contentBytes)
        val engine = RedactionEngine(
            loadPageFonts(page.resources),
            loadImageXObjectNames(page.resources),
            loadFormXObjectNames(page.resources),
            rectangles,
        )
        engine.formMatrices = loadFormMatrices(page.resources)
        val filtered = engine.run(ops)
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

        // Recurse into every intersecting form XObject: redact its own content so
        // sensitive text/graphics inside it are genuinely removed, not retained.
        recurseIntoForms(page.resources, engine.formXObjectHits)

        // Rebuild the page dict: new /Contents, pruned /XObject, filtered /Annots.
        var newPage = withEntry(page.dictionary, "Contents", streamRef)
        // Resources may be inherited from an ancestor /Pages node rather than present
        // on the leaf dict. Bake the effective resources onto the page before pruning
        // so the image XObject entry is actually removed (otherwise the pruning of a
        // missing local /Resources silently no-ops and the image stream survives).
        if ("Resources" !in newPage.map) {
            page.resources?.let { newPage = withEntry(newPage, "Resources", it) }
        }
        newPage = prunePageResourceXObjects(newPage, engine.droppedImageNames, engine.survivingImageNames)
        newPage = pruneIntersectingAnnots(newPage, rectangles)
        updateObject(ref, newPage)
        redactionStaged = true
    }

    /**
     * Recurse redaction into form XObjects invoked by the page whose area overlaps
     * a region. Each is re-parsed, redacted in its own coordinate space (using the
     * rectangles the engine mapped there), rewritten as a fresh stream, and staged
     * as a replacement so [saveRewritten] emits the redacted form and drops the
     * original. Guards against cycles/repeats via [redactedForms].
     */
    private fun recurseIntoForms(pageResources: PdfDictionary?, hits: List<RedactionEngine.FormHit>) {
        val xobjects = pageResources?.getDict("XObject", base) ?: return
        for (hit in hits) {
            val formRef = xobjects.getRef(hit.name) ?: continue
            redactFormXObject(formRef, hit.formRects)
        }
    }

    /** Object numbers of form XObjects already redacted, to avoid cycles/double work. */
    private val redactedForms = HashSet<Long>()

    /**
     * Redact [rectangles] (in the form's OWN coordinate space) inside the form
     * XObject at [formRef], recursing into any nested forms it invokes. The
     * rewritten form stream is staged as a replacement for [formRef].
     */
    private fun redactFormXObject(formRef: PdfReference, rectangles: List<Rectangle>) {
        if (rectangles.isEmpty()) return
        if (!redactedForms.add(formRef.objectNumber)) return
        val stream = effectiveObject(formRef.objectNumber) as? PdfStream ?: return
        val formResources = stream.dict.getDict("Resources", base)

        val content = io.github.yuroyami.kitepdf.filters.FilterChain.decode(stream)
        val ops = ContentStreamParser.parse(content)
        val engine = RedactionEngine(
            loadPageFonts(formResources),
            loadImageXObjectNames(formResources),
            loadFormXObjectNames(formResources),
            rectangles,
        )
        engine.formMatrices = loadFormMatrices(formResources)
        val filtered = engine.run(ops)
        val body = ContentStreamWriter.serialize(filtered)

        // Recurse into nested forms first (they may share this form's resource dict).
        recurseIntoForms(formResources, engine.formXObjectHits)

        // Rebuild the form stream dict: prune dropped image XObjects from its own
        // /Resources so the GC drops the image streams. Keep every other dict entry
        // (/BBox, /Matrix, /Group, /Type, /Subtype) intact; extraFrom() strips the
        // encoding entries (/Filter, /Length, ...) before we re-flate the content.
        val newDict = prunePageResourceXObjects(
            stream.dict, engine.droppedImageNames, engine.survivingImageNames,
        )
        val newStream = PdfStreams.flate(body, extraFrom(newDict))
        updateObject(formRef, newStream)
    }

    /** Carry a form stream dict's non-stream entries onto a fresh /FlateDecode stream. */
    private fun extraFrom(dict: PdfDictionary): Map<String, PdfObject> {
        val extra = LinkedHashMap<String, PdfObject>()
        for ((k, v) in dict.map) {
            if (k == "Length" || k == "Filter" || k == "DecodeParms" || k == "DL") continue
            extra[k] = v
        }
        return extra
    }

    /**
     * Remove entries in the dict's `/Resources /XObject` for image XObjects that
     * were dropped from the content AND are not still drawn elsewhere on the page.
     * Without this the image stream stays reachable and its data survives the GC.
     */
    private fun prunePageResourceXObjects(
        dict: PdfDictionary,
        droppedNames: Set<String>,
        survivingNames: Set<String>,
    ): PdfDictionary {
        val toPrune = droppedNames - survivingNames
        if (toPrune.isEmpty()) return dict
        val resources = dict.getDict("Resources", base) ?: return dict
        val xobjects = resources.getDict("XObject", base) ?: return dict
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
     * redaction rectangle. FreeText contents, widget values, and stamp appearance
     * streams inside a redacted region are otherwise left intact and extractable.
     * Annotations with no resolvable `/Rect` are kept (they draw nothing spatial).
     */
    private fun pruneIntersectingAnnots(dict: PdfDictionary, rectangles: List<Rectangle>): PdfDictionary {
        val annots = dict.getArray("Annots", base) ?: return dict
        val kept = ArrayList<PdfObject>(annots.items.size)
        var changed = false
        for (item in annots.items) {
            val annotDict = when (val resolved = item.resolve(base)) {
                is PdfDictionary -> resolved
                else -> null
            }
            val rect = annotDict?.let { annotRect(it) }
            if (rect != null && rectangles.any { rectsIntersect(it, rect) }) {
                changed = true // drop it
            } else {
                kept.add(item)
            }
        }
        if (!changed) return dict
        return withEntry(dict, "Annots", io.github.yuroyami.kitepdf.parser.PdfArray(kept))
    }

    /** Normalised `/Rect` of an annotation, or null when absent/malformed. */
    private fun annotRect(annot: PdfDictionary): Rectangle? {
        val arr = annot.getArray("Rect", base) ?: return null
        if (arr.size < 4) return null
        fun n(i: Int): Double? = when (val v = arr[i].resolve(base)) {
            is PdfInt -> v.value.toDouble()
            is io.github.yuroyami.kitepdf.parser.PdfReal -> v.value
            else -> null
        }
        val x0 = n(0) ?: return null
        val y0 = n(1) ?: return null
        val x1 = n(2) ?: return null
        val y1 = n(3) ?: return null
        return Rectangle(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
    }

    private fun rectsIntersect(a: Rectangle, b: Rectangle): Boolean =
        a.left < b.right && a.right > b.left && a.bottom < b.top && a.top > b.bottom

    private fun loadPageFonts(resources: PdfDictionary?): Map<String, PdfFont> {
        val fontDict = resources?.getDict("Font", base) ?: return emptyMap()
        val out = LinkedHashMap<String, PdfFont>()
        for ((name, value) in fontDict.map) out[name] = PdfFont.from(value, base)
        return out
    }

    private fun loadImageXObjectNames(resources: PdfDictionary?): Set<String> {
        val xobjects = resources?.getDict("XObject", base) ?: return emptySet()
        val out = HashSet<String>()
        for ((name, value) in xobjects.map) {
            val stream = value.resolve(base) as? PdfStream ?: continue
            if (stream.dict.getName("Subtype") == "Image") out.add(name)
        }
        return out
    }

    private fun loadFormXObjectNames(resources: PdfDictionary?): Set<String> {
        val xobjects = resources?.getDict("XObject", base) ?: return emptySet()
        val out = HashSet<String>()
        for ((name, value) in xobjects.map) {
            val stream = value.resolve(base) as? PdfStream ?: continue
            if (stream.dict.getName("Subtype") == "Form") out.add(name)
        }
        return out
    }

    /** Per-name form `/Matrix` (default identity when absent). */
    private fun loadFormMatrices(resources: PdfDictionary?): Map<String, io.github.yuroyami.kitepdf.render.Matrix> {
        val xobjects = resources?.getDict("XObject", base) ?: return emptyMap()
        val out = LinkedHashMap<String, io.github.yuroyami.kitepdf.render.Matrix>()
        for ((name, value) in xobjects.map) {
            val stream = value.resolve(base) as? PdfStream ?: continue
            if (stream.dict.getName("Subtype") != "Form") continue
            val m = stream.dict.getArray("Matrix", base) ?: continue
            if (m.size < 6) continue
            fun n(i: Int): Double = when (val v = m[i].resolve(base)) {
                is PdfInt -> v.value.toDouble()
                is io.github.yuroyami.kitepdf.parser.PdfReal -> v.value
                else -> 0.0
            }
            out[name] = io.github.yuroyami.kitepdf.render.Matrix(n(0), n(1), n(2), n(3), n(4), n(5))
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
            // Staged objects live in plain text; an encrypted base document
            // needs them encrypted with its own parameters on the way out.
            val value = encryptor?.encryptIndirect(num, s.generation, s.value) ?: s.value
            PdfObjectWriter.writeObject(value, out)
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
        // Preserve the original /ID (signing/encryption invariant) or synthesize one.
        dict["ID"] = base.trailer["ID"] ?: DocumentId.generate(base.bytes)
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
    fun saveRewritten(useObjectStreams: Boolean = false): ByteArray {
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

        return if (useObjectStreams) {
            writeWithObjectStreams(ordered, remap)
        } else {
            writeWithClassicXref(ordered, remap)
        }
    }

    private fun fileHeader(out: ByteArrayBuilder, version: String) {
        out.append("%PDF-$version\n".encodeToByteArray())
        out.append('%'.code.toByte())
        out.append(byteArrayOf(0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte()))
        out.append('\n'.code.toByte())
    }

    private fun newRootRef(remap: Map<Long, Long>, key: String): PdfReference? {
        val ref = (trailerOverrides[key] ?: base.trailer[key]) as? PdfReference ?: return null
        return remap[ref.objectNumber]?.let { PdfReference(it, 0) }
    }

    /** Full rewrite with a classic xref table (PDF 1.4-shaped output). */
    private fun writeWithClassicXref(ordered: List<Long>, remap: Map<Long, Long>): ByteArray {
        val out = ByteArrayBuilder(base.bytes.size)
        fileHeader(out, "1.7")

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
        newRootRef(remap, "Root")?.let { trailer["Root"] = it }
        newRootRef(remap, "Info")?.let { trailer["Info"] = it }
        trailer["ID"] = base.trailer["ID"] ?: DocumentId.generate(base.bytes)
        out.append("trailer\n".encodeToByteArray())
        PdfObjectWriter.writeObject(PdfDictionary(trailer), out)
        out.append("\nstartxref\n$xrefOffset\n%%EOF\n".encodeToByteArray())
        return out.toByteArray()
    }

    /**
     * Full rewrite with object streams + a cross-reference stream (PDF 1.5+
     * compact output): non-stream objects are packed into one `/ObjStm`, stream
     * objects stay in-file, and a single `/Type /XRef` stream replaces the
     * classic table and trailer.
     */
    private fun writeWithObjectStreams(ordered: List<Long>, remap: Map<Long, Long>): ByteArray {
        // Partition: stream objects must stay in-file; everything else is packable.
        val streamObjs = ArrayList<Long>()
        val packable = ArrayList<Long>()
        for (old in ordered) {
            if (effectiveObject(old) is PdfStream) streamObjs.add(old) else packable.add(old)
        }
        val maxNew = (ordered.size).toLong()
        val objStmNum = maxNew + 1
        val xrefStmNum = maxNew + 2

        val out = ByteArrayBuilder(base.bytes.size)
        fileHeader(out, "1.5")
        val entries = ArrayList<XRefStreamWriter.XEntry>(ordered.size + 3)
        entries.add(XRefStreamWriter.XEntry(0, 0, 0, 0))   // free-list head

        // In-file (type 1) stream objects.
        for (old in streamObjs) {
            val obj = effectiveObject(old) ?: continue
            val newNum = remap.getValue(old)
            entries.add(XRefStreamWriter.XEntry(newNum, 1, out.size().toLong(), 0))
            out.append("$newNum 0 obj\n".encodeToByteArray())
            PdfObjectWriter.writeObject(remapReferences(obj, remap), out)
            out.append("\nendobj\n".encodeToByteArray())
        }

        // Pack the remaining objects into one ObjStm (type 2 entries).
        if (packable.isNotEmpty()) {
            val members = packable.map { old ->
                remap.getValue(old) to remapReferences(effectiveObject(old)!!, remap)
            }
            members.forEachIndexed { index, (newNum, _) ->
                entries.add(XRefStreamWriter.XEntry(newNum, 2, objStmNum, index.toLong()))
            }
            val objStm = ObjectStreamWriter.build(members)
            entries.add(XRefStreamWriter.XEntry(objStmNum, 1, out.size().toLong(), 0))
            out.append("$objStmNum 0 obj\n".encodeToByteArray())
            PdfObjectWriter.writeObject(objStm, out)
            out.append("\nendobj\n".encodeToByteArray())
        }

        // The cross-reference stream is itself an in-file object.
        val xrefOffset = out.size()
        entries.add(XRefStreamWriter.XEntry(xrefStmNum, 1, xrefOffset.toLong(), 0))
        val root = newRootRef(remap, "Root")
            ?: throw IllegalStateException("Cannot write document with no /Root")
        val xrefStream = XRefStreamWriter.build(
            entries, size = xrefStmNum + 1, root = root, info = newRootRef(remap, "Info"), prev = null,
            id = base.trailer["ID"] ?: DocumentId.generate(base.bytes),
        )
        out.append("$xrefStmNum 0 obj\n".encodeToByteArray())
        PdfObjectWriter.writeObject(xrefStream, out)
        out.append("\nendobj\n".encodeToByteArray())
        out.append("startxref\n$xrefOffset\n%%EOF\n".encodeToByteArray())
        return out.toByteArray()
    }

    /** Staged override if present, else the original object; null for free/missing. */
    private fun effectiveObject(num: Long): PdfObject? =
        staged[num]?.value ?: base.resolve(PdfReference(num, 0))

    private fun collectReferences(obj: PdfObject, visit: (Long) -> Unit) {
        when (obj) {
            is PdfReference -> visit(obj.objectNumber)
            is io.github.yuroyami.kitepdf.parser.PdfArray -> obj.items.forEach { collectReferences(it, visit) }
            is PdfDictionary -> obj.map.values.forEach { collectReferences(it, visit) }
            is PdfStream -> obj.dict.map.values.forEach { collectReferences(it, visit) }
            else -> {}
        }
    }

    private fun remapReferences(obj: PdfObject, remap: Map<Long, Long>): PdfObject = when (obj) {
        is PdfReference -> remap[obj.objectNumber]?.let { PdfReference(it, 0) }
            ?: io.github.yuroyami.kitepdf.parser.PdfNull
        is io.github.yuroyami.kitepdf.parser.PdfArray ->
            io.github.yuroyami.kitepdf.parser.PdfArray(obj.items.map { remapReferences(it, remap) })
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
