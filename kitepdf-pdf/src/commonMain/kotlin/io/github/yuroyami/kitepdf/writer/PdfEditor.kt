package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.KiteRawApi
import io.github.yuroyami.kitepdf.PdfFormField
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.content.ContentStreamParser
import io.github.yuroyami.kitepdf.content.Operation
import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.ByteReader
import io.github.yuroyami.kitepdf.core.font.PdfFont
import io.github.yuroyami.kitepdf.core.parser.PdfBoolean
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.parser.PdfString
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
public class PdfEditor internal constructor(
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
     * file, where they remain recoverable, defeating redaction.
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
    @KiteRawApi
    public fun allocateReference(): PdfReference = PdfReference(nextObjectNumber++, 0)

    /** Stage a brand-new indirect object; returns the reference to it. */
    @KiteRawApi
    public fun addObject(value: PdfObject): PdfReference {
        val ref = allocateReference()
        staged[ref.objectNumber] = Staged(ref.generation, value)
        return ref
    }

    /** Stage a replacement for an existing object (keeps [ref]'s generation). */
    @KiteRawApi
    public fun updateObject(ref: PdfReference, value: PdfObject) {
        staged[ref.objectNumber] = Staged(ref.generation, value)
    }

    /**
     * Stage a new `/FlateDecode`-compressed stream from uncompressed [data]
     * (see [PdfStreams.flate]); returns the reference to it.
     */
    public fun addFlateStream(data: ByteArray, extra: Map<String, PdfObject> = emptyMap()): PdfReference =
        addObject(PdfStreams.flate(data, extra))

    /** Set or replace a trailer entry (e.g. `/Root`, `/Info`) in the new section. */
    @KiteRawApi
    public fun setTrailerEntry(key: String, value: PdfObject) {
        trailerOverrides[key] = value
    }

    /** Number of objects staged for writing. */
    public val pendingObjectCount: Int get() = staged.size

    /**
     * Set document metadata (`/Info`). Only non-null fields are changed; any
     * existing `/Info` entries (standard or custom) are preserved. Updates the
     * existing `/Info` object in place if the trailer references one, otherwise
     * creates a new `/Info` object and points the trailer at it. Returns the
     * reference to the (possibly newly created) `/Info` dictionary.
     */
    public fun setInfo(
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
     * [transform] receives the page's content as it stands after any earlier
     * edit in this editor, so successive calls compose in call order.
     *
     * Note: [transform] only reorders/removes/keeps existing operations. It
     * doesn't introduce new resource dependencies. To overlay new content (with
     * its own fonts), use [stampPage].
     */
    public fun editPageContent(page: PdfPage, transform: (List<Operation>) -> List<Operation>) {
        val ref = pageReference(page)
        val ops = ContentStreamParser.parse(effectiveContentBytes(ref))
        val newContent = ContentStreamWriter.serialize(transform(ops))
        val streamRef = addObject(PdfStreams.flate(newContent))
        updateObject(ref, withEntry(effectivePageDict(ref), "Contents", streamRef))
    }

    /**
     * Remove all text-showing operations (`Tj`, `TJ`, `'`, `"`) from [page].
     *
     * This is a text-stripping primitive, NOT secure region redaction: it drops
     * every show-text operator on the page (not images, not a chosen rectangle).
     * True redaction (removing only content within an area, including images)
     * needs text-position tracking and is a later feature.
     */
    public fun removeAllText(page: PdfPage) {
        editPageContent(page) { ops -> ops.filter { it.operator !in TEXT_SHOW_OPERATORS } }
    }

    /**
     * Overlay content onto an existing [page] (a stamp/watermark) drawn by
     * [block]. The existing content is preserved (wrapped in `q`/`Q` so its
     * graphics state can't leak into the overlay), the overlay is appended in
     * its own `q`/`Q`, and any standard fonts the overlay uses are merged into
     * the page's `/Resources` under fresh, non-colliding names.
     *
     * Stamps compose: a second stamp overlays the first rather than replacing
     * it, and gets its own font resource names.
     */
    public fun stampPage(page: PdfPage, block: ContentStreamBuilder.() -> Unit) {
        val ref = pageReference(page)
        val pageDict = effectivePageDict(ref)
        val pageResources = effectiveResources(ref, page)
        val contentBytes = effectiveContentBytes(ref)

        val existingFonts = pageResources?.getDict("Font", base)
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

        val merged = ByteArrayBuilder(contentBytes.size + stampBytes.size + 16)
        merged.append("q\n".encodeToByteArray())
        merged.append(contentBytes)
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
        pageResources?.map?.let { resources.putAll(it) }
        resources["Font"] = PdfDictionary(fontDict)

        var newPage = withEntry(pageDict, "Contents", streamRef)
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
    public fun setTextFieldValue(field: PdfFormField, value: String) {
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
     * Set a button field (`/Btn`) to a named export value, the mechanism behind
     * checkboxes and radio groups. The field's `/V` becomes the chosen on-state
     * name; every widget's `/AS` is set to that name when the widget defines it
     * as an appearance state, or to `/Off` otherwise (so sibling radios in the
     * group are cleared). Pass `"Off"` to clear the field.
     */
    public fun setButtonValue(field: PdfFormField, exportValue: String) {
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
    public fun setCheckbox(field: PdfFormField, checked: Boolean) {
        val on = checkboxOnState(field) ?: "On"
        setButtonValue(field, if (checked) on else "Off")
    }

    /**
     * Set a choice field (`/Ch`: combo box or list box) to [value]. Sets `/V`,
     * sets `/I` (selected index) when the value is found in `/Opt`, and
     * regenerates the widget appearance so the selection is visible.
     */
    public fun setChoiceValue(field: PdfFormField, value: String) {
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
            if (selectedIndex >= 0) d["I"] = io.github.yuroyami.kitepdf.core.parser.PdfArray(listOf(PdfInt(selectedIndex.toLong())))
            if (apDict != null) d["AP"] = apDict
            updateObject(fieldRef, PdfDictionary(d))
        } else {
            val fd = LinkedHashMap(field.fieldDict.map)
            fd["V"] = vStr
            if (selectedIndex >= 0) fd["I"] = io.github.yuroyami.kitepdf.core.parser.PdfArray(listOf(PdfInt(selectedIndex.toLong())))
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

    /** The checkbox "on" state, the first non-Off /AP /N appearance name. */
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
                is io.github.yuroyami.kitepdf.core.parser.PdfArray ->
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
    public fun rotatePage(page: PdfPage, degrees: Int) {
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
    public fun setPageOrder(orderedPageRefs: List<PdfReference>) {
        require(orderedPageRefs.isNotEmpty()) { "A document must have at least one page" }
        pageOrderState = orderedPageRefs.toMutableList()
        applyPageOrder()
    }

    /** Remove [page] from the document. */
    public fun removePage(page: PdfPage) {
        val target = page.reference?.objectNumber
            ?: throw IllegalArgumentException("Page ${page.index} has no indirect reference")
        val order = currentOrder()
        order.removeAll { it.objectNumber == target }
        require(order.isNotEmpty()) { "Cannot remove the last page" }
        applyPageOrder()
    }

    /** Append a page deep-copied from [source] (see [graftPage]); returns its new ref. */
    public fun appendPage(source: PdfDocument, sourceIndex: Int): PdfReference {
        val ref = graftPage(source, sourceIndex)
        currentOrder().add(ref)
        applyPageOrder()
        return ref
    }

    /** Insert a (grafted) page reference at zero-based [position]. */
    public fun insertPageAt(position: Int, pageRef: PdfReference) {
        val order = currentOrder()
        order.add(position.coerceIn(0, order.size), pageRef)
        applyPageOrder()
    }

    /** Append every page of [source] to this document (cross-document merge). */
    public fun mergeDocument(source: PdfDocument) {
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
                "Kids" to io.github.yuroyami.kitepdf.core.parser.PdfArray(order.toList()),
                "Count" to PdfInt(order.size.toLong()),
            )),
        )
        // Flattening to one /Pages node can strip attributes a leaf page inherited
        // from an intermediate node, so bake the resolved MediaBox/Resources/Rotate
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
    public fun graftPage(source: PdfDocument, sourceIndex: Int): PdfReference {
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

    private fun rectToArray(r: KiteRectangle): io.github.yuroyami.kitepdf.core.parser.PdfArray =
        io.github.yuroyami.kitepdf.core.parser.PdfArray(listOf(
            io.github.yuroyami.kitepdf.core.parser.PdfReal(r.left),
            io.github.yuroyami.kitepdf.core.parser.PdfReal(r.bottom),
            io.github.yuroyami.kitepdf.core.parser.PdfReal(r.right),
            io.github.yuroyami.kitepdf.core.parser.PdfReal(r.top),
        ))

    /** Effective (staged-or-base) page dictionary for [ref]. */
    private fun effectivePageDict(ref: PdfReference): PdfDictionary =
        effectiveObject(ref.objectNumber) as? PdfDictionary
            ?: throw IllegalArgumentException("Page ${ref.objectNumber} did not resolve to a dictionary")

    /**
     * Decoded page content as it stands AFTER any staged edit, so a second edit
     * composes with the first instead of replacing it.
     *
     * Mirrors [PdfPage.contentBytes]: `/Contents` may be one stream reference, a
     * direct stream, or an array of streams that concatenate into a single
     * stream with whitespace between the parts (ISO 32000-1, 7.8.2). A member
     * that will not decode is skipped rather than failing the page (R6).
     */
    private fun effectiveContentBytes(ref: PdfReference): ByteArray {
        fun decode(stream: PdfStream): ByteArray? =
            runCatching { io.github.yuroyami.kitepdf.core.filters.FilterChain.decode(stream) }.getOrNull()

        return when (val contents = effectivePageDict(ref)["Contents"]) {
            is PdfReference -> (effectiveObject(contents.objectNumber) as? PdfStream)?.let(::decode) ?: ByteArray(0)
            is PdfStream -> decode(contents) ?: ByteArray(0)
            is io.github.yuroyami.kitepdf.core.parser.PdfArray -> {
                val buf = ByteArrayBuilder(4096)
                var first = true
                for (part in contents) {
                    val partRef = part as? PdfReference ?: continue
                    val bytes = (effectiveObject(partRef.objectNumber) as? PdfStream)?.let(::decode) ?: continue
                    if (!first) buf.append('\n'.code.toByte())
                    buf.append(bytes)
                    first = false
                }
                buf.toByteArray()
            }
            else -> ByteArray(0)
        }
    }

    /**
     * Resource dictionary as it stands after any staged edit. A staged page dict
     * always carries its own `/Resources`; an untouched page may inherit them
     * from an ancestor `/Pages` node, which [PdfPage.resources] already walks.
     */
    private fun effectiveResources(ref: PdfReference, page: PdfPage): PdfDictionary? =
        when (val resources = effectivePageDict(ref)["Resources"]) {
            is PdfDictionary -> resources
            // A reference that fails to resolve to a dictionary returns null here,
            // it does not fall back to inheritance like the absent-key case below;
            // every call site already null-checks this result.
            is PdfReference -> effectiveObject(resources.objectNumber) as? PdfDictionary
            else -> page.resources
        }

    /* ─── Redaction ──────────────────────────────────────────────────────── */

    /** Redact a single rectangular region of [page] (see [redactRegions]). */
    public fun redactRegion(page: PdfPage, rectangle: KiteRectangle): Unit = redactRegions(page, listOf(rectangle))

    /**
     * Redact rectangular regions of [page] (rectangles in page user space).
     *
     * This is **true** redaction: text whose box intersects a region has its
     * bytes REMOVED from the content stream (so it can't be extracted or
     * recovered), surviving text keeps its position, intersecting images are
     * dropped from the page, and an opaque black box is painted over each
     * region. It does not merely paint over still-present content.
     *
     * Calls compose: redacting a second region does not undo the first, and a
     * stamp or content edit staged earlier is redacted along with the original
     * page content.
     *
     * Conservative by design. A run touching a region is removed wholesale, so
     * partial overlaps over-remove. Content inside referenced form XObjects IS
     * recursed into (redacted in the form's own coordinate space); a dropped
     * image's XObject entry is pruned from `/Resources /XObject` so
     * [saveRewritten]'s reachability GC drops the image stream; and annotations
     * whose `/Rect` intersects a region are removed from the page `/Annots`.
     *
     * **A removed widget takes its form field with it.** A widget annotation is
     * usually the field dictionary too (ISO 32000-1, 12.7.3.3), so it is also
     * detached from `/AcroForm /Fields` and `/CO` (12.7.2), or from its parent
     * field's `/Kids` when it is one of several. Otherwise the field's `/V`, `/DV`,
     * `/T` and appearance stream stay reachable from the catalog and survive the
     * rewrite. A field that still has a widget outside every region keeps that
     * widget and its place in the form.
     *
     * **An annotation removed from the page keeps nothing.** Reachability is not the
     * only thing keeping redacted content out of the rewrite, so the object itself is
     * emptied: text (`/Contents`, `/RC`), appearance (`/AP`), attached file (`/FS`),
     * sound and movie go, and a detached field loses its value, its appearance state
     * and its names on top of that. A structure this editor does not rewrite (an
     * `/XFA` payload, a tagged document's `/StructTreeRoot`) then cannot ship the
     * content by keeping the object alive. One thing this does not reach: an embedded
     * file the catalog's `/Names /EmbeddedFiles` tree names as well stays in the
     * document, since that copy of it is not on the page.
     *
     * **The page may come out with more XObjects than it went in with.** One form
     * XObject can be drawn in several places (ISO 32000-1, 8.10), and each place
     * sees the region in a different part of the form. One rewritten stream cannot
     * be right for all of them, so each place that needs a different redaction gets
     * its OWN copy of the form, `/Resources /XObject` gains a generated name for it
     * (the original name plus `R1`, `R2`, ...), and that one `Do` is repointed.
     * Places that no region touches are left alone and keep drawing the original.
     *
     * **A vector path in a region is removed, not covered.** A signature or a chart
     * drawn as line art IS its coordinates, so the path's construction operators go
     * with its painting operator, and the pen's width counts towards the ink a
     * stroke lays down (ISO 32000-1, 8.4.3.2). One exception ([RedactionEngine]):
     * a path that also sets a clip (`W`) keeps its coordinates and loses only its
     * paint, because everything up to the matching `Q` is clipped by it (8.5.4) and
     * dropping it would let all of that paint over the rest of the page.
     */
    public fun redactRegions(page: PdfPage, rectangles: List<KiteRectangle>) {
        if (rectangles.isEmpty()) return
        // ISO 32000-1, 7.9.5: a rectangle is two opposite corners in EITHER order and
        // the consumer normalises. Read positionally, an inverted one is inside out,
        // so every intersection test below would match nothing and the caller would
        // get a file they believe is redacted.
        val regions = rectangles.map { it.normalized() }
        beginRedactionCall()
        val ref = pageReference(page)
        val pageDict = effectivePageDict(ref)
        val pageResources = effectiveResources(ref, page)
        val ops = ContentStreamParser.parse(effectiveContentBytes(ref))

        val engine = RedactionEngine(
            loadPageFonts(pageResources),
            loadImageXObjectNames(pageResources),
            loadFormXObjectNames(pageResources),
            regions,
        )
        engine.formMatrices = loadFormMatrices(pageResources)
        engine.formBBoxes = loadFormBBoxes(pageResources)
        val filtered = engine.run(ops)
        // Recurse into every intersecting form XObject first: it decides which `Do`
        // operands point at a redacted clone and which leave the stream altogether,
        // so it has to run before the body is serialized.
        val formRedaction = recurseIntoForms(pageResources, engine.formXObjectHits)
        val finalOps = applyFormRedaction(filtered, formRedaction)
        val body = ContentStreamWriter.serialize(finalOps)

        val out = ByteArrayBuilder(body.size + 64)
        out.append("q\n".encodeToByteArray())
        out.append(body)
        out.append("\nQ\n".encodeToByteArray())
        for (r in regions) {
            val box = "q 0 g ${fmt(r.left)} ${fmt(r.bottom)} ${fmt(r.width)} ${fmt(r.height)} re f Q\n"
            out.append(box.encodeToByteArray())
        }
        val streamRef = addObject(PdfStreams.flate(out.toByteArray()))

        // Rebuild the page dict: new /Contents, pruned /XObject, filtered /Annots.
        var newPage = withEntry(pageDict, "Contents", streamRef)
        // Resources may be inherited from an ancestor /Pages node rather than present
        // on the leaf dict. Bake the effective resources (plus any form clones) onto
        // the page before pruning so the image XObject entry is actually removed
        // (pruning a missing local /Resources no-ops and the image stream survives).
        val resourcesWithClones = withFormAdditions(pageResources, formRedaction) ?: pageResources
        if (resourcesWithClones != null) newPage = withEntry(newPage, "Resources", resourcesWithClones)
        newPage = prunePageResourceXObjects(newPage, engine.droppedImageNames, engine.survivingImageNames)
        newPage = prunePageResourceXObjects(newPage, formRedaction.droppedNames, namesStillDrawn(finalOps))
        val droppedAnnots = ArrayList<PdfReference>()
        newPage = pruneIntersectingAnnots(newPage, regions, droppedAnnots)
        // Stage the page first: what follows reads effective objects, and a widget's
        // field is only reachable through the /Annots this call just rewrote.
        updateObject(ref, newPage)
        // Set before the field detach, which raises on a malformed /Parent chain: the
        // page is already rewritten, so a caller that catches must still be refused an
        // incremental save (it would leave the original bytes in the file).
        redactionStaged = true
        // Content first, fields second: anything taken off the page loses its content
        // whatever else still names it, and that must not depend on the field walk
        // below, which can raise.
        scrub(droppedAnnots, SCRUBBED_ANNOT_KEYS)
        detachRedactedFields(droppedAnnots)
    }

    /**
     * Form object numbers on the CURRENT recursion descent. A form reached again
     * while it is still on this stack is a genuine cycle: ISO 32000-1, 8.10.1 says
     * a form XObject shall not invoke itself, directly or indirectly, so a file
     * that does is malformed and the descent stops there (R6). A form invoked twice
     * by the same parent is NOT a cycle, which is why this cannot be a plain
     * visited set.
     */
    private val formDescent = ArrayDeque<Long>()

    /** Form object numbers whose ORIGINAL object is already spoken for. */
    private val claimedForms = HashSet<Long>()

    /** Form identity (object number plus mapped rectangles) to the object holding that redaction. */
    private val redactedFormCache = LinkedHashMap<String, PdfReference>()

    /** Clone object number to the resource name minted for it, so one clone gets one name. */
    private val formCloneNames = LinkedHashMap<Long, String>()

    /**
     * Form streams as they stood when the CURRENT redaction call began. The first
     * invocation of a form stages its rewrite over the original object, so a
     * sibling invocation that read the staged state would redact an already
     * redacted stream and lose content only the first invocation's region covered.
     */
    private val formSources = LinkedHashMap<Long, PdfStream>()

    /**
     * Resolver that sees STAGED objects, not only the ones in the base document.
     * A clone minted by an earlier redaction lives in the staging map and nowhere
     * else, so a lookup through [base] alone finds nothing: the next call would not
     * recognise the clone as a form, would not record its `Do`, and would leave the
     * second region's content inside it untouched.
     */
    private val effective = IndirectResolver { effectiveObject(it.objectNumber) }

    /**
     * Reset the per-call form bookkeeping.
     *
     * All four maps describe ONE redaction call: inside a call they accumulate
     * across the hit loop, and between calls they have to be forgotten. Carrying
     * [claimedForms] over would make a second call find the form already claimed
     * and clone it rather than rewriting it, leaving the original (which still
     * holds the second region's content) named in `/XObject` and so kept alive by
     * the reachability GC. Cleared, the second call re-claims the form and composes
     * on the snapshot of the already-redacted version (D-1).
     */
    private fun beginRedactionCall() {
        formSources.clear()
        claimedForms.clear()
        redactedFormCache.clear()
        formCloneNames.clear()
    }

    /**
     * Identity of one redaction OF one form. Two invocations of the same form
     * that map a region to the same place can share a rewrite; two that map it
     * elsewhere cannot, because one rewritten stream cannot be right for both.
     * Coordinates are quantised to 1/1000 pt so float noise in the inverted CTM
     * does not manufacture clones that are the same rewrite twice.
     */
    private fun formKey(objectNumber: Long, rectangles: List<KiteRectangle>): String = buildString {
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
    }

    /**
     * One coordinate as a key component, in thousandths of a point.
     *
     * Each degenerate value gets its OWN sentinel. Folding NaN, the two infinities
     * and an overflow into one bucket would make two mappings that differ look
     * identical, so they would share one rewrite when they need two.
     */
    private fun quantise(value: Double): Long = when {
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
    private class Rename(val from: String, val to: String)

    /** What a descent into a parent's forms asks the parent to change about itself. */
    private class FormRedaction {
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
     * (form, rectangles) pair therefore gets its own object, and the caller
     * repoints that invocation's `Do` at it.
     *
     * @return what the caller must change about its own stream and resources.
     */
    private fun recurseIntoForms(
        resources: PdfDictionary?,
        hits: List<RedactionEngine.FormHit>,
    ): FormRedaction {
        val result = FormRedaction()
        val xobjects = resources?.getDict("XObject", effective) ?: return result
        val usedNames = HashSet(xobjects.keys)

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
            val key = formKey(formRef.objectNumber, hit.formRects)
            val claimable = formRef.objectNumber !in pristine
            val target = redactedFormCache[key] ?: redactFormXObject(formRef, hit.formRects, key, claimable)
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
     * @return the object holding this redaction, or null when the form's stream is
     *   missing or will not decode.
     */
    private fun redactFormXObject(
        formRef: PdfReference,
        rectangles: List<KiteRectangle>,
        key: String,
        claimable: Boolean,
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
            val formResources = stream.dict.getDict("Resources", effective)
            val ops = ContentStreamParser.parse(content)
            val engine = RedactionEngine(
                loadPageFonts(formResources),
                loadImageXObjectNames(formResources),
                loadFormXObjectNames(formResources),
                rectangles,
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
    private fun applyFormRedaction(ops: List<Operation>, redaction: FormRedaction): List<Operation> {
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
    private fun isDoNamed(op: Operation?, name: String): Boolean =
        op != null && op.operator == "Do" && (op.operands.firstOrNull() as? PdfName)?.value == name

    /** Names a `Do` still invokes after the rewrite, so a resource nothing draws can go. */
    private fun namesStillDrawn(ops: List<Operation>): Set<String> {
        val out = HashSet<String>()
        for (op in ops) {
            if (op.operator != "Do") continue
            (op.operands.firstOrNull() as? PdfName)?.value?.let { out.add(it) }
        }
        return out
    }

    /** Add cloned forms to a resource dictionary's `/XObject`, or null when there is nothing to add. */
    private fun withFormAdditions(resources: PdfDictionary?, redaction: FormRedaction): PdfDictionary? {
        if (redaction.additions.isEmpty()) return null
        val xobjects = LinkedHashMap(resources?.getDict("XObject", effective)?.map ?: emptyMap())
        for ((name, ref) in redaction.additions) xobjects[name] = ref
        val merged = LinkedHashMap(resources?.map ?: emptyMap())
        merged["XObject"] = PdfDictionary(xobjects)
        return PdfDictionary(merged)
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
     * Remove entries in the dict's `/Resources /XObject` for XObjects that were
     * dropped from the content AND are not still drawn elsewhere on the page.
     * Without this the stream stays reachable and its data survives the GC. Called
     * once for dropped images and once for dropped forms, since the two have
     * different notions of what "still drawn" means.
     */
    private fun prunePageResourceXObjects(
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
    private fun pruneIntersectingAnnots(
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
    private fun annotRect(annot: PdfDictionary): KiteRectangle? {
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
    private fun detachRedactedFields(droppedAnnots: List<PdfReference>) {
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
        scrub(detached.values, SCRUBBED_FIELD_KEYS)

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
    private fun withoutDetached(
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
    private fun scrub(objects: Collection<PdfReference>, keys: List<String>) {
        for (ref in objects) {
            val dict = effectiveObject(ref.objectNumber) as? PdfDictionary ?: continue
            val scrubbed = LinkedHashMap(dict.map)
            var changed = false
            for (key in keys) if (scrubbed.remove(key) != null) changed = true
            if (changed) updateObject(ref, PdfDictionary(scrubbed))
        }
    }

    /**
     * True when [dict] is shaped like a form field, so the `/Kids` walk may treat it
     * as one.
     *
     * A field carries `/FT` or `/T` (ISO 32000-1, 12.7.3.1). Two things a stray
     * `/Parent` can point at instead have to be refused. A page or `/Pages` node
     * (7.7.3.2) holds the PAGE tree in its `/Kids`, so rewriting it would delete
     * pages from the document, which is worse than anything a redaction leak can do.
     * A markup annotation carries `/T` too, but there it is the author's name
     * (12.5.6.4) and the annotation is usually still on the page, so scrubbing it
     * would take the title and appearance off something nobody redacted.
     */
    private fun isFieldNode(dict: PdfDictionary): Boolean = when {
        dict.getName("Type") == "Page" || dict.getName("Type") == "Pages" -> false
        dict.getName("Type") == "Annot" || dict["Rect"] != null -> dict.getName("Subtype") == "Widget"
        else -> dict.getName("FT") != null || dict["T"] != null
    }

    /**
     * Take [widget] out of its parent field's `/Kids`, and add to [detached] a
     * parent that is left with no kids at all: it only existed to hold the widgets
     * that went, so the walk climbs to ITS parent in turn until one still has a kid
     * or the root field `/Fields` names is reached.
     *
     * Fields form a tree (ISO 32000-1, 12.7.3.3), so a `/Parent` chain that returns
     * to a node it already visited is malformed and the root field cannot be found.
     * Redaction is a destructive write, so it says so rather than hand back a file
     * whose `/Fields` still reaches the value the caller asked to remove.
     */
    private fun detachFromParentField(
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
            // something that is not a field at all, and rewriting THAT dictionary's
            // /Kids would edit the page tree rather than a form. The annotation itself
            // is already scrubbed, so stopping here costs nothing.
            if (!isFieldNode(field)) return
            // Unreadable /Kids means a malformed parent-kid link: treat the field as
            // emptied and over-remove rather than trust it.
            val kids = field.getArray("Kids", effective)?.items.orEmpty()
            val survivors = kids.filter { (it as? PdfReference)?.objectNumber != child.objectNumber }
            if (survivors.isNotEmpty()) {
                // A parent that never named this widget has nothing to rewrite.
                if (survivors.size != kids.size) {
                    updateObject(
                        parent,
                        withEntry(field, "Kids", io.github.yuroyami.kitepdf.core.parser.PdfArray(survivors)),
                    )
                }
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

    private fun rectsIntersect(a: KiteRectangle, b: KiteRectangle): Boolean =
        a.left < b.right && a.right > b.left && a.bottom < b.top && a.top > b.bottom

    private fun loadPageFonts(resources: PdfDictionary?): Map<String, PdfFont> {
        val fontDict = resources?.getDict("Font", effective) ?: return emptyMap()
        val out = LinkedHashMap<String, PdfFont>()
        for ((name, value) in fontDict.map) out[name] = PdfFont.from(value, effective)
        return out
    }

    private fun loadImageXObjectNames(resources: PdfDictionary?): Set<String> {
        val xobjects = resources?.getDict("XObject", effective) ?: return emptySet()
        val out = HashSet<String>()
        for ((name, value) in xobjects.map) {
            val stream = value.resolve(effective) as? PdfStream ?: continue
            if (stream.dict.getName("Subtype") == "Image") out.add(name)
        }
        return out
    }

    private fun loadFormXObjectNames(resources: PdfDictionary?): Set<String> {
        val xobjects = resources?.getDict("XObject", effective) ?: return emptySet()
        val out = HashSet<String>()
        for ((name, value) in xobjects.map) {
            val stream = value.resolve(effective) as? PdfStream ?: continue
            if (stream.dict.getName("Subtype") == "Form") out.add(name)
        }
        return out
    }

    /** Per-name form `/Matrix` (default identity when absent). */
    private fun loadFormMatrices(resources: PdfDictionary?): Map<String, io.github.yuroyami.kitepdf.core.render.KiteMatrix> {
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
    private fun loadFormBBoxes(resources: PdfDictionary?): Map<String, KiteRectangle> {
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
    public fun saveIncremental(): ByteArray {
        check(!redactionStaged) {
            "Redaction was staged; call saveRewritten() instead. An incremental save " +
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
     * objects. The reader fills those from the `/Prev` chain.
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
     * an edit) are dropped, which is what makes it the correct method for
     * **redaction** (the removed content is truly gone, not just superseded).
     */
    public fun saveRewritten(useObjectStreams: Boolean = false): ByteArray {
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
            is io.github.yuroyami.kitepdf.core.parser.PdfArray -> obj.items.forEach { collectReferences(it, visit) }
            is PdfDictionary -> obj.map.values.forEach { collectReferences(it, visit) }
            is PdfStream -> obj.dict.map.values.forEach { collectReferences(it, visit) }
            else -> {}
        }
    }

    private fun remapReferences(obj: PdfObject, remap: Map<Long, Long>): PdfObject = when (obj) {
        is PdfReference -> remap[obj.objectNumber]?.let { PdfReference(it, 0) }
            ?: io.github.yuroyami.kitepdf.core.parser.PdfNull
        is io.github.yuroyami.kitepdf.core.parser.PdfArray ->
            io.github.yuroyami.kitepdf.core.parser.PdfArray(obj.items.map { remapReferences(it, remap) })
        is PdfDictionary -> PdfDictionary(
            LinkedHashMap<String, PdfObject>().also { m -> obj.map.forEach { (k, v) -> m[k] = remapReferences(v, remap) } },
        )
        is PdfStream -> PdfStream(remapReferences(obj.dict, remap) as PdfDictionary, obj.rawBytes)
        else -> obj
    }

    private companion object {
        /** Text-showing operators (§9.4.3). */
        val TEXT_SHOW_OPERATORS = setOf("Tj", "TJ", "'", "\"")

        /**
         * What an annotation carries: its text (§12.5.2, Table 168), the same text as
         * rich content (§12.5.6.2), the stream that draws it, the file it attaches
         * (§12.5.6.15), its sound (§12.5.6.16) and its movie (§12.5.6.17).
         */
        val SCRUBBED_ANNOT_KEYS = listOf("Contents", "RC", "AP", "FS", "Sound", "Movie")

        /**
         * What a form field carries on top of that: the value and its default
         * (§12.7.3.3), the value as rich text (§12.7.3.4), the appearance state that IS
         * the value for a check box or radio button (§12.7.4.2.1), the selected indices
         * of a list box (§12.7.4.4), and the three names that identify the field
         * (§12.7.3.1). `/AP` repeats so a non-annotation parent field carrying one
         * loses it too.
         */
        val SCRUBBED_FIELD_KEYS = listOf("V", "DV", "RV", "AS", "I", "T", "TU", "TM", "AP")
    }
}
