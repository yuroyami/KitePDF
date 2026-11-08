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
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.parser.PdfString
import io.github.yuroyami.kitepdf.parser.XrefEntry
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
    internal val base: PdfDocument,
    random: kotlin.random.Random?,
) {

    private class Staged(val generation: Int, val value: PdfObject)

    /** objectNumber → staged (new or replacement) object, in insertion order. */
    private val staged = LinkedHashMap<Long, Staged>()

    /** Trailer entries to set/replace in the appended section's trailer. */
    internal val trailerOverrides = LinkedHashMap<String, PdfObject>()

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
            requireNotNull(random) {
                "Editing an encrypted PDF requires a cryptographically secure Random."
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
        // The /Info as it stands in this editor: an earlier call's object and values count, so each
        // call changes only the entries it is given (ISO 32000-1, 14.3.3; #686).
        val raw = trailerOverrides["Info"] ?: base.trailer["Info"]
        val existingRef = raw as? PdfReference
        val existing = when (raw) {
            is PdfReference -> effectiveObject(raw.objectNumber) as? PdfDictionary
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
        // ISO 32000-1, 14.3.3, Table 317: the date of the most recent change (#692).
        val now = XmpSync.now()
        merged["ModDate"] = PdfString(now.toString().encodeToByteArray())
        syncXmp(title, author, subject, keywords, creator, producer, now)

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

    /**
     * Rewrites the catalog's XMP packet with the values that [setInfo] was given, so it agrees
     * with `/Info` (ISO 32000-2, 14.3.3; #692). A packet that does not decode is left as it is.
     */
    private fun syncXmp(
        title: String?, author: String?, subject: String?, keywords: String?, creator: String?, producer: String?,
        date: io.github.yuroyami.kitepdf.PdfDate,
    ) {
        val rootRef = (trailerOverrides["Root"] ?: base.trailer["Root"]) as? PdfReference ?: return
        val catalog = effectiveObject(rootRef.objectNumber) as? PdfDictionary ?: return
        val raw = catalog["Metadata"] ?: return
        val stream = (if (raw is PdfReference) effectiveObject(raw.objectNumber) else raw) as? PdfStream ?: return
        val bytes = runCatching { io.github.yuroyami.kitepdf.core.filters.FilterChain.decode(stream) }.getOrNull() ?: return
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        val xml = bytes.decodeToString(start)
        val updated = XmpSync.update(xml, title, author, subject, keywords, creator, producer, date)
        if (updated == xml) return
        // A metadata stream stays uncompressed, so a reader that does not decode filters reads it (14.3.2).
        val dict = LinkedHashMap(stream.dict.map).apply {
            remove("Filter"); remove("DecodeParms")
            this["Type"] = PdfName("Metadata"); this["Subtype"] = PdfName("XML")
        }
        val body = updated.encodeToByteArray()
        dict["Length"] = PdfInt(body.size.toLong())
        val newStream = PdfStream(PdfDictionary(dict), body)
        if (raw is PdfReference) updateObject(raw, newStream)
        else updateObject(rootRef, PdfDictionary(LinkedHashMap(catalog.map).apply { this["Metadata"] = newStream }))
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
        val colorSpaces = ContentStreamParser.colorSpaces(effectiveResources(ref, page), effective)
        val ops = ContentStreamParser.parse(effectiveContentBytes(ref), colorSpaces)
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
        // The content's own q and Q may not balance, so the wrap opens and closes enough of
        // them that the stamp starts in default user space whatever it did (#1014).
        val wrap = SaveRestoreWrap.of(runCatching { ContentStreamParser.parse(contentBytes) }.getOrDefault(emptyList()))
        merged.append(wrap.open.encodeToByteArray())
        merged.append(contentBytes)
        merged.append("\n${wrap.close}q\n".encodeToByteArray())
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
                    ).withTextEncoding(font),
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
     * appearance honours the field's `/DA` (font, size, colour): the font of the form's `/DR` that it
     * names when the writer can encode for it, a simple font with WinAnsiEncoding, else Helvetica. It is clipped
     * to the field rectangle. It draws over the widget's `/MK` background and border, turns with
     * its `/MK /R`, and puts one character in each cell of a comb field. A value longer than the
     * field's `/MaxLen` is cut to it. Also clears the form's `/NeedAppearances` flag (if
     * set) so viewers use the appearance we just generated.
     *
     * Only `/Tx` (text) fields are supported; buttons/choices come later.
     *
     * A password field (`/Ff` bit 14) draws one asterisk a character, never its value, as ISO
     * 32000-1, 12.7.4.3, Table 228 asks. Its `/V` still takes [value], since the caller asked for it;
     * the note to the same entry advises a reader never to store a password, so an app that saves
     * what its reader typed skips such fields (#954).
     */
    public fun setTextFieldValue(field: PdfFormField, value: String) {
        require(field.type == PdfFormField.FieldType.Text) {
            "setTextFieldValue supports text (/Tx) fields only, not ${field.type}"
        }
        // ISO 32000-1, 12.7.4.3, Table 229: /MaxLen is the longest value the field holds, so a longer
        // one is cut to it, as a viewer's input stops there (#719).
        val kept = field.maxLength?.takeIf { it >= 0 && value.length > it }?.let { value.take(it) } ?: value
        val fieldRef = field.fieldReference
            ?: throw IllegalArgumentException("Field '${field.fullyQualifiedName}' has no indirect reference")
        val widgets = buttonWidgets(field)
        require(widgets.any { rectOf(it.second) != null }) {
            "Field '${field.fullyQualifiedName}' has no widget /Rect for an appearance"
        }

        val da = FieldAppearance.parseDA(field.defaultAppearance)
        // A password field draws asterisks, never its value (ISO 32000-1, 12.7.4.3, Table 228, #954).
        val shown = if ((field.flags and FieldAppearance.PASSWORD) != 0) FieldAppearance.masked(kept) else kept
        val (fontRef, metrics) = appearanceFont(da)
        val entries = LinkedHashMap<String, PdfObject>()
        entries["V"] = PdfString(PdfText.encodeTextString(kept))
        // A rich text field keeps its rich value in step with the plain one, so a reader that
        // shows the rich value shows the new text too (ISO 32000-1, 12.7.3.4, #204).
        if (field.isRichText) entries["RV"] = PdfString(PdfText.encodeTextString(richBodyOf(kept)))
        writeFieldValue(field, fieldRef, widgets, entries) { w, h, widget ->
            val look = FieldAppearance.lookOf(widget, base, field.flags, field.maxLength)
            FieldAppearance.build(shown, w, h, da, fontRef, field.isMultiline, field.quadding, metrics, look)
        }
        clearNeedAppearances()
    }

    /**
     * Stores [entries] on the field and gives each of [widgets] an appearance [draw]s for its own
     * `/Rect`. ISO 32000-1, 12.7.3.1: a field shown in several places has a widget for each place,
     * and all of them show its value, so the editor redraws every one before it clears
     * `/NeedAppearances` (#702). A merged field and widget takes both in one dictionary.
     */
    private fun writeFieldValue(
        field: PdfFormField,
        fieldRef: PdfReference,
        widgets: List<Pair<PdfReference, PdfDictionary>>,
        entries: Map<String, PdfObject>,
        draw: (width: Double, height: Double, widget: PdfDictionary) -> PdfStream,
    ) {
        var merged = false
        for ((ref, widget) in widgets) {
            val ap = rectOf(widget)?.let { r ->
                PdfDictionary(linkedMapOf("N" to (addObject(draw(abs(r[2] - r[0]), abs(r[3] - r[1]), widget)) as PdfObject)))
            }
            if (ref == fieldRef) {
                val d = LinkedHashMap(field.fieldDict.map)
                d.putAll(entries)
                if (ap != null) d["AP"] = ap
                updateObject(fieldRef, PdfDictionary(d))
                merged = true
            } else if (ap != null) {
                updateObject(ref, withEntry(widget, "AP", ap))
            }
        }
        if (!merged) updateObject(fieldRef, PdfDictionary(LinkedHashMap(field.fieldDict.map).apply { putAll(entries) }))
    }

    /** [value] as the XHTML body of a rich text value, one paragraph a line, with no style of its own. */
    private fun richBodyOf(value: String): String {
        fun escape(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val paragraphs = value.split("\r\n", "\r", "\n").joinToString("") { "<p>${escape(it)}</p>" }
        return """<?xml version="1.0"?><body xmlns="http://www.w3.org/1999/xhtml" xmlns:xfa="http://www.xfa.org/schema/xfa-data/1.0/" """ +
            """xfa:APIVersion="Acroform:2.7.0.0" xfa:spec="2.1">$paragraphs</body>"""
    }

    /**
     * Set a button field (`/Btn`) to a named export value, the mechanism behind
     * checkboxes and radio groups. [exportValue] is the on-state name of a widget,
     * or, for a field with `/Opt`, one of [PdfFormField.options], which selects the
     * kid at its index. The field's `/V` becomes that on-state name; every widget's
     * `/AS` is set to it when the widget defines it as an appearance state, or to
     * `/Off` otherwise (so sibling radios in the group are cleared). Pass `"Off"`
     * to clear the field. A value that names no state throws
     * [IllegalArgumentException].
     *
     * A widget that ships no appearance for the state gets one drawn for it,
     * from its own `/MK` colours, so a ticked box actually looks ticked.
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

        // /Ff bit 16: the button is a radio, so its widgets draw round.
        val radio = (field.flags and (1 shl 15)) != 0
        val widgets = buttonWidgets(field)
        // ISO 32000-1, 12.7.4.2.1 and Table 227: with /Opt, the on-state names may be each kid's
        // index in /Kids, and /Opt holds the export values. Without /Opt or a state of its own
        // there is no way to tell which radio owns which value, so a lone widget owns the name
        // that the caller asked for, and the others keep the appearances the file shipped.
        val optNames = field.fieldDict.getArray("Opt", base)?.map {
            (it.resolve(base) as? PdfString)?.asText()
        }
        val onNames = widgets.mapIndexed { i, (_, wdict) ->
            appearanceStateNames(wdict).firstOrNull { it != "Off" }
                ?: if (optNames != null) i.toString() else if (widgets.size == 1) exportValue else null
        }
        // The on state that /V and /AS take: a widget's own state name, or the one of the kid that
        // /Opt puts the export value at (12.7.4.2.3; #695).
        val target = when {
            exportValue == "Off" -> "Off"
            exportValue in onNames -> exportValue
            optNames != null && exportValue in optNames -> onNames.getOrNull(optNames.indexOf(exportValue))
            else -> null
        } ?: throw IllegalArgumentException(
            "'$exportValue' is no state or /Opt value of button field '${field.fullyQualifiedName}'",
        )
        put(fieldRef, field.fieldDict, "V", PdfName(target))
        for ((i, entry) in widgets.withIndex()) {
            val (wref, wdict) = entry
            var states = appearanceStateNames(wdict)
            val ownName = onNames[i]
            // A synthesized appearance is keyed by the same name that /V and /AS use.
            if (states.isEmpty() && ownName != null && ownName != "Off") {
                toggleAppearance(wdict, ownName, radio)?.let { ap ->
                    put(wref, wdict, "AP", ap)
                    states = setOf(ownName, "Off")
                }
            }
            val asName = if (target in states) target else "Off"
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
        val entries = LinkedHashMap<String, PdfObject>()
        entries["V"] = PdfString(PdfText.encodeTextString(value))
        // /I selected-index, when the value is one of /Opt.
        val selectedIndex = optionIndex(field, value)
        if (selectedIndex >= 0) entries["I"] = io.github.yuroyami.kitepdf.core.parser.PdfArray(listOf(PdfInt(selectedIndex.toLong())))

        // Build the appearance of each widget (same path as a text field shows its value).
        val da = FieldAppearance.parseDA(field.defaultAppearance)
        val (fontRef, metrics) = appearanceFont(da)
        // ISO 32000-1, 12.7.4.4: /V holds the export value, but the widget shows the option's text,
        // and a list box shows its rows with the selected one highlighted (#700).
        val selection = field.choiceSelectionForValue(value)
            ?: io.github.yuroyami.kitepdf.PdfChoiceSelection(emptyList(), unresolvedValues = listOf(value))
        val label = field.choiceOptions.firstOrNull { it.index in selection.indices }?.label ?: value
        writeFieldValue(field, fieldRef, buttonWidgets(field), entries) { w, h, widget ->
            val combo = { FieldAppearance.build(label, w, h, da, fontRef, metrics = metrics, look = FieldAppearance.lookOf(widget, base, field.flags, null)) }
            if (field.isCombo) combo() else io.github.yuroyami.kitepdf.render.ChoiceAppearance.build(field, selection, widget, w, h, base) ?: combo()
        }
        clearNeedAppearances()
    }

    /**
     * An `/AP` dictionary with both states drawn for a toggle widget, or null
     * when the widget has no rectangle to draw in.
     */
    private fun toggleAppearance(widget: PdfDictionary, onState: String, radio: Boolean): PdfDictionary? {
        val rect = rectOf(widget) ?: return null
        val w = abs(rect[2] - rect[0])
        val h = abs(rect[3] - rect[1])
        if (w <= 0.0 || h <= 0.0) return null
        val mk = widget.getDict("MK", base)
        val background = FieldAppearance.colorOps(mk?.getArray("BG", base), stroking = false)
        val border = FieldAppearance.colorOps(mk?.getArray("BC", base), stroking = true)
        val borderWidth = (widget.getDict("BS", base)?.get("W")?.resolve(base)).let {
            (it as? PdfReal)?.value ?: (it as? PdfInt)?.value?.toDouble() ?: 1.0
        }
        // /MK /CA names the mark; a radio's default is the filled circle.
        val mark = (mk?.get("CA")?.resolve(base) as? PdfString)?.asText()?.firstOrNull()
            ?: if (radio) 'l' else '4'
        val da = FieldAppearance.parseDA(acroFormDefaultAppearance())
        val zapf = addObject(
            PdfDictionary(
                linkedMapOf(
                    "Type" to PdfName("Font"),
                    "Subtype" to PdfName("Type1"),
                    "BaseFont" to PdfName("ZapfDingbats"),
                ),
            ),
        )
        fun state(on: Boolean) = addObject(
            FieldAppearance.buildToggle(
                w, h, on, radio, mark, background, border, borderWidth, da.colorOps, zapf,
                rotation = FieldAppearance.rotationOf(mk, base),
            ),
        ) as PdfObject
        return PdfDictionary(
            linkedMapOf(
                "N" to PdfDictionary(linkedMapOf(onState to state(true), "Off" to state(false))),
            ),
        )
    }

    /** A widget's `/Rect` as [x0, y0, x1, y1], or null. */
    private fun rectOf(widget: PdfDictionary): DoubleArray? {
        val arr = widget.getArray("Rect", base) ?: return null
        if (arr.size < 4) return null
        val v = DoubleArray(4) { i ->
            when (val o = arr.getOrNull(i)?.resolve(base)) {
                is PdfReal -> o.value
                is PdfInt -> o.value.toDouble()
                else -> return null
            }
        }
        return v
    }

    /**
     * The font a field appearance draws in, and the metrics it is measured with. ISO 32000-1,
     * 12.7.3.3: the font operand of `/DA` names a resource of the form's `/DR`, so the appearance
     * references that font object when the writer can encode for it: a simple font with
     * WinAnsiEncoding, measured by its standard 14 metrics when its base font is one, else by
     * Helvetica's (#707). A font the writer cannot encode for, a Type0 or a symbolic one, or a name
     * `/DR` lacks, gives a new Helvetica object, which draws the value rather than nothing.
     */
    private fun appearanceFont(da: FieldAppearance.DefaultAppearance): Pair<PdfReference, StandardFont> {
        acroFormDict()?.let { acro ->
            val entry = (acro["DR"]?.resolve(base) as? PdfDictionary)?.let { (it["Font"]?.resolve(base) as? PdfDictionary)?.get(da.fontName) }
            val font = entry?.let { (if (it is PdfReference) effectiveObject(it.objectNumber) else it) as? PdfDictionary }
            if (entry != null && font != null && font.getName("Subtype") in SIMPLE_FONTS &&
                font["Encoding"]?.resolve(base).let { it is PdfName && it.value == "WinAnsiEncoding" }
            ) {
                val baseFont = font.getName("BaseFont")?.substringAfter('+')
                val metrics = StandardFont.entries.firstOrNull { it.baseFont == baseFont && it != StandardFont.Symbol && it != StandardFont.ZapfDingbats }
                    ?: StandardFont.Helvetica
                return (entry as? PdfReference ?: addObject(font)) to metrics
            }
        }
        val helvetica = addObject(
            PdfDictionary(
                linkedMapOf(
                    "Type" to PdfName("Font"),
                    "Subtype" to PdfName("Type1"),
                    "BaseFont" to PdfName("Helvetica"),
                    "Encoding" to PdfName("WinAnsiEncoding"),
                ),
            ),
        )
        return helvetica to StandardFont.Helvetica
    }

    private fun acroFormDict(): PdfDictionary? {
        val rootRef = (trailerOverrides["Root"] ?: base.trailer["Root"]) as? PdfReference ?: return null
        val catalog = effectiveObject(rootRef.objectNumber) as? PdfDictionary ?: return null
        val acroRef = catalog["AcroForm"] as? PdfReference
        return (if (acroRef != null) effectiveObject(acroRef.objectNumber) else catalog["AcroForm"]) as? PdfDictionary
    }

    /** The form-wide `/DA`, which is where a mark's colour usually comes from. */
    private fun acroFormDefaultAppearance(): String? {
        val rootRef = (trailerOverrides["Root"] ?: base.trailer["Root"]) as? PdfReference ?: return null
        val catalog = effectiveObject(rootRef.objectNumber) as? PdfDictionary ?: return null
        val acroRef = catalog["AcroForm"] as? PdfReference
        val acro = (if (acroRef != null) effectiveObject(acroRef.objectNumber) else catalog["AcroForm"])
            as? PdfDictionary ?: return null
        return (acro["DA"]?.resolve(base) as? PdfString)?.asText()
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

    /**
     * If the AcroForm has `/NeedAppearances true`, flip it false so our `/AP`
     * is used. Reads the staged-or-base view (an earlier edit may have staged
     * the form), and accepts an `/AcroForm` written straight into the catalog.
     */
    private fun clearNeedAppearances() {
        val rootRef = (trailerOverrides["Root"] ?: base.trailer["Root"]) as? PdfReference ?: return
        val catalog = effectiveObject(rootRef.objectNumber) as? PdfDictionary ?: return
        val acroRef = catalog["AcroForm"] as? PdfReference
        val acro = (if (acroRef != null) effectiveObject(acroRef.objectNumber) else catalog["AcroForm"])
            as? PdfDictionary ?: return
        if ((acro["NeedAppearances"] as? PdfBoolean)?.value != true) return
        val cleared = withEntry(acro, "NeedAppearances", PdfBoolean(false))
        if (acroRef != null) {
            updateObject(acroRef, cleared)
        } else {
            updateObject(rootRef, withEntry(catalog, "AcroForm", cleared))
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
        changePageOrder { it.clear(); it.addAll(orderedPageRefs) }
    }

    /**
     * Applies [change] to a copy of the tracked page order and keeps it only when it leaves a page
     * and the catalog's /Pages can be rebuilt, so a call that throws leaves the editor as it was.
     * The tracked order used to change first, and a refused removal of the last page lost that page
     * at the next page operation (#736).
     */
    private inline fun changePageOrder(change: (MutableList<PdfReference>) -> Unit) {
        val next = currentOrder().toMutableList()
        change(next)
        require(next.isNotEmpty()) { "Cannot remove the last page" }
        base.catalog.getRef("Pages")
            ?: throw IllegalStateException("Catalog /Pages is not an indirect reference; cannot reorganise pages")
        pageOrderState = next
        applyPageOrder()
    }

    /** Remove [page] from the document. */
    public fun removePage(page: PdfPage) {
        val target = page.reference?.objectNumber
            ?: throw IllegalArgumentException("Page ${page.index} has no indirect reference")
        changePageOrder { order -> order.removeAll { it.objectNumber == target } }
    }

    /** Append a page deep-copied from [source] (see [graftPage]); returns its new ref. */
    public fun appendPage(source: PdfDocument, sourceIndex: Int): PdfReference {
        val ref = graftPage(source, sourceIndex)
        changePageOrder { it.add(ref) }
        return ref
    }

    /** Insert a (grafted) page reference at zero-based [position]. */
    public fun insertPageAt(position: Int, pageRef: PdfReference) {
        changePageOrder { order -> order.add(position.coerceIn(0, order.size), pageRef) }
    }

    /** Append every page of [source] to this document (cross-document merge). */
    public fun mergeDocument(source: PdfDocument) {
        val refs = source.pages.indices.map { graftPage(source, it) }
        changePageOrder { it.addAll(refs) }
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
        // onto each base page that doesn't carry its own, with /CropBox, the fourth inheritable entry.
        val baseByNum = base.pages.mapNotNull { p -> p.reference?.let { it.objectNumber to p } }.toMap()
        for (pref in order) {
            val pd = effectiveObject(pref.objectNumber) as? PdfDictionary ?: continue
            val m = LinkedHashMap(pd.map)
            m["Parent"] = pagesRef
            baseByNum[pref.objectNumber]?.let { bp ->
                if ("MediaBox" !in m) m["MediaBox"] = rectToArray(bp.mediaBox)
                // /CropBox inherits too (ISO 32000-1, 7.7.3.4, Table 30; #685).
                if ("CropBox" !in m && bp.cropBox != bp.mediaBox) m["CropBox"] = rectToArray(bp.cropBox)
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

        // The copy holds the page and what it draws or shows, not the rest of the source: no
        // other page and no page tree node, which a link's destination, an annotation's /P or a
        // bead reaches, is entered, and a reference to one is copied as null (ISO 32000-1,
        // 12.3.2.2; #1015). A widget becomes a field of its own, so its field's other widgets,
        // and their pages, stay behind (12.7.3.1).
        val otherPages = source.pages.mapNotNull { it.reference?.objectNumber }.filterTo(HashSet()) { it != srcPageNum }
        val overrides = HashMap<Long, PdfObject>()
        runCatching { effective.getArray("Annots", source) }.getOrNull()?.forEach { item ->
            val ref = item as? PdfReference ?: return@forEach
            val annot = runCatching { source.resolve(ref) }.getOrNull() as? PdfDictionary ?: return@forEach
            if (annot.getName("Subtype") == "Widget" && annot["Parent"] != null) overrides[ref.objectNumber] = standaloneWidget(annot, source)
        }
        fun sourceObject(num: Long): PdfObject? = overrides[num] ?: source.resolve(PdfReference(num, 0))
        fun outside(num: Long): Boolean {
            if (num in otherPages) return true
            val obj = runCatching { source.resolve(PdfReference(num, 0)) }.getOrNull() as? PdfDictionary ?: return false
            return obj.getName("Type") == "Pages" || obj.getName("Type") == "Catalog"
        }

        // BFS the source graph, allocating a new number per visited source object.
        val refMap = HashMap<Long, Long>()
        val queue = ArrayDeque<Long>()
        fun enqueue(srcNum: Long) {
            if (srcNum !in refMap && !outside(srcNum)) { refMap[srcNum] = allocateReference().objectNumber; queue.addLast(srcNum) }
        }
        val newPageNum = allocateReference().objectNumber
        refMap[srcPageNum] = newPageNum
        collectReferences(effective) { enqueue(it) }
        while (queue.isNotEmpty()) {
            val obj = sourceObject(queue.removeFirst()) ?: continue
            collectReferences(obj) { enqueue(it) }
        }

        // Stage remapped copies of the page and every reachable object.
        staged[newPageNum] = Staged(0, remapReferences(effective, refMap))
        for ((srcNum, newNum) in refMap) {
            if (newNum == newPageNum) continue
            val obj = sourceObject(srcNum) ?: continue
            staged[newNum] = Staged(0, remapReferences(obj, refMap))
        }
        return PdfReference(newPageNum, 0)
    }

    /**
     * [widget] as a field of its own: the field entries it inherits from its `/Parent` chain
     * (ISO 32000-1, 12.7.3.1, Table 220, and 12.7.3.3) written onto it, its partial names joined
     * into its full name, and no `/Parent`, so a graft does not pull in the field's other widgets.
     */
    private fun standaloneWidget(widget: PdfDictionary, source: PdfDocument): PdfDictionary {
        val out = LinkedHashMap(widget.map)
        out.remove("Parent")
        val names = ArrayList<String>()
        (widget["T"] as? PdfString)?.asText()?.let(names::add)
        var parent = runCatching { widget["Parent"]?.resolve(source) }.getOrNull() as? PdfDictionary
        var depth = 0
        while (parent != null && depth++ < 32) {
            for (key in INHERITED_FIELD_KEYS) if (key !in out) parent[key]?.let { out[key] = it }
            (parent["T"] as? PdfString)?.asText()?.let { names.add(0, it) }
            parent = runCatching { parent["Parent"]?.resolve(source) }.getOrNull() as? PdfDictionary
        }
        if (names.isNotEmpty()) out["T"] = PdfString(PdfText.encodeTextString(names.joinToString(".")))
        return PdfDictionary(out)
    }

    /** A self-contained copy of a page dict: inheritance baked in, /Parent removed. */
    private fun graftablePageDict(page: PdfPage): PdfDictionary {
        val m = LinkedHashMap(page.dictionary.map)
        m.remove("Parent")
        m["Type"] = PdfName("Page")
        if ("MediaBox" !in m) m["MediaBox"] = rectToArray(page.mediaBox)
        if ("CropBox" !in m && page.cropBox != page.mediaBox) m["CropBox"] = rectToArray(page.cropBox)
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
     * Shares [io.github.yuroyami.kitepdf.PageContents] with
     * [PdfPage.contentBytes]; only the resolve step differs (staged-or-base
     * here, the base document there).
     */
    private fun effectiveContentBytes(ref: PdfReference): ByteArray =
        io.github.yuroyami.kitepdf.PageContents.concatenated(effectivePageDict(ref)["Contents"]) {
            effectiveObject(it.objectNumber) as? PdfStream
        } ?: ByteArray(0)

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

    /**
     * Takes the images [dropped] out of the resource dictionaries that [pageRef] (whose dictionary
     * before the rewrite was [pageDict]) shared or inherited: its own indirect `/Resources` and the
     * `/Resources` of its `/Pages` ancestors (ISO 32000-1, 7.7.3.4), once no other page draws them,
     * so the rewrite does not keep image data that nothing draws (#1010). A page that names one of
     * them and holds a form XObject or a pattern, which could draw it unnamed in its content,
     * counts as drawing it.
     */
    private fun unnameUndrawnXObjects(pageRef: PdfReference, pageDict: PdfDictionary, dropped: Set<PdfReference>) {
        if (dropped.isEmpty()) return
        val stillDrawn = HashSet<PdfReference>()
        for (other in base.pages) {
            val otherRef = other.reference ?: continue
            if (otherRef == pageRef) continue
            val res = runCatching { effectiveResources(otherRef, other) }.getOrNull() ?: continue
            val xobjects = runCatching { res.getDict("XObject", effective) }.getOrNull() ?: continue
            val named = xobjects.map.filterValues { it is PdfReference && it in dropped }
            if (named.isEmpty()) continue
            val indirect = xobjects.map.values.any { v ->
                (runCatching { v.resolve(effective) }.getOrNull() as? PdfStream)?.dict?.getName("Subtype") == "Form"
            } || runCatching { res.getDict("Pattern", effective) }.getOrNull()?.map?.isNotEmpty() == true
            val drawn = if (indirect) named.keys else {
                val ops = runCatching { ContentStreamParser.parse(effectiveContentBytes(otherRef)) }.getOrDefault(emptyList())
                ops.filter { it.operator == "Do" }.mapNotNull { (it.operands.firstOrNull() as? PdfName)?.value }.toSet().intersect(named.keys)
            }
            for (name in drawn) (named[name] as? PdfReference)?.let(stillDrawn::add)
        }
        val gone = dropped - stillDrawn
        if (gone.isEmpty()) return

        fun pruned(resources: PdfDictionary): PdfDictionary? {
            val xobjects = runCatching { resources.getDict("XObject", effective) }.getOrNull() ?: return null
            val kept = xobjects.map.filterValues { !(it is PdfReference && it in gone) }
            if (kept.size == xobjects.map.size) return null
            return withEntry(resources, "XObject", PdfDictionary(LinkedHashMap(kept)))
        }
        // The page's own resources, when another object holds them.
        (pageDict["Resources"] as? PdfReference)?.let { r ->
            (effectiveObject(r.objectNumber) as? PdfDictionary)?.let(::pruned)?.let { updateObject(r, it) }
        }
        // The /Pages ancestors, whose resources every page below them inherits.
        var parent = pageDict["Parent"] as? PdfReference
        val seen = HashSet<Long>()
        while (parent != null && seen.add(parent.objectNumber)) {
            val node = effectiveObject(parent.objectNumber) as? PdfDictionary ?: break
            when (val res = node["Resources"]) {
                is PdfReference -> (effectiveObject(res.objectNumber) as? PdfDictionary)?.let(::pruned)?.let { updateObject(res, it) }
                is PdfDictionary -> pruned(res)?.let { updateObject(parent!!, withEntry(node, "Resources", it)) }
                else -> {}
            }
            parent = node["Parent"] as? PdfReference
        }
    }

    /** Redact a single rectangular region of [page] (see [redactRegions]). */
    public fun redactRegion(page: PdfPage, rectangle: KiteRectangle): Unit = redactRegions(page, listOf(rectangle))

    /** Redact a single rectangular region of [page], with [images] deciding what an image in it loses (see [redactRegions]). */
    public fun redactRegion(page: PdfPage, rectangle: KiteRectangle, images: RedactImages): Unit = redactRegions(page, listOf(rectangle), images)

    /** Redact rectangular regions of [page], taking every image a region meets off the page (see the overload with [RedactImages]). */
    public fun redactRegions(page: PdfPage, rectangles: List<KiteRectangle>): Unit = redactRegions(page, rectangles, RedactImages.Remove)

    /**
     * Redact rectangular regions of [page] (rectangles in page user space).
     *
     * This is **true** redaction: text whose box intersects a region has its
     * bytes REMOVED from the content stream (so it can't be extracted or
     * recovered), surviving text keeps its position, intersecting images are
     * dropped from the page (or, with [RedactImages.Pixels], lose the pixels
     * under the regions), and an opaque black box is painted over each
     * region. It does not merely paint over still-present content.
     *
     * **[images] decides what an image XObject in a region loses.** [RedactImages.Remove]
     * takes it off the page. [RedactImages.Pixels] keeps it, in a copy whose samples under
     * the regions (mapped into the image, rounded outward) take the darkest value of its
     * colour space, written again with `/FlateDecode`; its `/SMask` loses the same pixels,
     * so no alpha outline survives, and the original goes from the page as a removed image
     * does. What it cannot blank safely is taken off the page as with `Remove`: an inline
     * image, a stencil mask, an image with a stencil `/Mask`, a JPEG 2000 or JBIG2 image, a
     * JPEG of four components, and one whose colour space or data does not read. An image
     * that another page, or a form drawn outside every region, also draws keeps its original
     * there.
     *
     * Calls compose: redacting a second region does not undo the first, and a
     * stamp or content edit staged earlier is redacted along with the original
     * page content. The black box from an earlier call is repainted along with
     * the new one even when the two regions overlap, so a later call cannot
     * uncover part of an earlier redaction (see [redactedRegionsByPage]).
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
     * sound, movie, and the actions and appearance characteristics that reach the
     * rest (`/A`, `/AA`, `/MK`) all go, and a detached field loses its value, its
     * appearance state and its names on top of that. A structure this editor does
     * not rewrite (an `/XFA` payload, a tagged document's `/StructTreeRoot`) then
     * cannot ship the content by keeping the object alive. A file attachment's file also
     * goes from the catalog's `/Names /EmbeddedFiles` tree when the tree names it, so the
     * document loses that attachment, as the engine removes too much rather than too
     * little (#632).
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
     * stroke lays down (ISO 32000-1, 8.4.3.2). A stroke inside a form XObject that
     * sets no `w` or `M` of its own is judged by the invoking stream's, the same
     * way `PageRenderer` draws it (8.10.2): the form does not restart the pen at
     * the 1.0/10.0 defaults. One exception ([RedactionEngine]): a path that also
     * sets a clip (`W`) keeps its coordinates and loses only its paint, because
     * everything up to the matching `Q` is clipped by it (8.5.4) and dropping it
     * would let all of that paint over the rest of the page.
     */
    public fun redactRegions(page: PdfPage, rectangles: List<KiteRectangle>, images: RedactImages) {
        if (rectangles.isEmpty()) return
        redactionImages = images
        // ISO 32000-1, 7.9.5: a rectangle is two opposite corners in EITHER order and
        // the consumer normalises. Read positionally, an inverted one is inside out,
        // so every intersection test below would match nothing and the caller would
        // get a file they believe is redacted.
        val regions = rectangles.map { it.normalized() }
        beginRedactionCall()
        val ref = pageReference(page)
        val pageDict = effectivePageDict(ref)
        val pageResources = effectiveResources(ref, page)
        redactionPageResources = pageResources
        // Split inline images at the renderer's bytes, or a redaction can miss drawn text (#266).
        val ops = ContentStreamParser.parse(effectiveContentBytes(ref), ContentStreamParser.colorSpaces(pageResources, effective))

        val engine = RedactionEngine(
            loadPageFonts(pageResources),
            loadImageXObjectNames(pageResources),
            loadFormXObjectNames(pageResources),
            regions,
            type3Fonts = loadType3Fonts(pageResources),
        )
        engine.formMatrices = loadFormMatrices(pageResources)
        engine.formBBoxes = loadFormBBoxes(pageResources)
        engine.extGStates = loadExtGStates(pageResources)
        engine.tilingPatternNames = loadTilingPatternNames(pageResources)
        engine.namedPropertyLists = loadPropertyLists(pageResources)
        engine.fontBands = loadFontBands(pageResources)
        engine.imagePixels = images == RedactImages.Pixels
        val filtered = engine.run(ops)
        // Recurse into every intersecting form XObject first: it decides which `Do`
        // operands point at a redacted clone and which leave the stream altogether,
        // so it has to run before the body is serialized.
        val formRedaction = recurseIntoForms(pageResources, engine.formXObjectHits)
        // An image drawn into a region under RedactImages.Pixels is repointed at a blanked copy (#926).
        redactImagePixels(pageResources, engine.imageHits, formRedaction, engine.droppedImageNames)
        // Soft mask groups, which no Do reaches, redact into copies of their own (#631).
        val maskRedaction = redactSoftMasks(pageResources, engine.softMaskHits)
        // A sequence that lost content loses its replacement text, a named list through a copy (#1008).
        val (marked, markedResources) = stripNamedPropertyLists(pageResources, engine.namedPropertyStrips, applySoftMaskRedaction(filtered, maskRedaction))
        val finalOps = applyFormRedaction(marked, formRedaction)
        val body = ContentStreamWriter.serialize(finalOps)

        val out = ByteArrayBuilder(body.size + 64)
        // The boxes go in default user space, whatever q and Q the content leaves unbalanced (#1014).
        val wrap = SaveRestoreWrap.of(finalOps)
        out.append(wrap.open.encodeToByteArray())
        out.append(body)
        out.append("\n${wrap.close}".encodeToByteArray())
        // This call's own regions always repaint (their content is new). An
        // EARLIER region only needs repainting if it overlaps one of them: only
        // an overlap could have sent that box's construction back through the
        // engine above and had it judged (and possibly dropped) as an ordinary
        // filled path, same as any other content (see [redactedRegionsByPage]).
        // A non-overlapping earlier box was never at risk, so its own box from
        // its own call is still sitting in [body] untouched, and painting it
        // again here would only be a second, identical rectangle.
        val allRegionsOnPage = redactedRegionsByPage.getOrPut(ref.objectNumber) { ArrayList() }
        val earlierAtRisk = allRegionsOnPage.filter { old -> regions.any { rectsIntersect(old, it) } }
        for (new in regions) if (new !in allRegionsOnPage) allRegionsOnPage.add(new)
        for (r in (earlierAtRisk + regions).distinct()) {
            val box = "q 0 g ${fmt(r.left)} ${fmt(r.bottom)} ${fmt(r.width)} ${fmt(r.height)} re f Q\n"
            out.append(box.encodeToByteArray())
        }
        val streamRef = addObject(PdfStreams.flate(out.toByteArray()))

        // Rebuild the page dict: new /Contents, pruned /XObject, filtered /Annots. The thumbnail
        // is a picture of the page before the redaction (ISO 32000-1, 12.3.4), and page-piece
        // data belongs to the application that wrote it, which can keep the whole page there
        // (14.5): the editor cannot redact either, so a redacted page loses both (#1011).
        var newPage = withEntry(withoutKeys(pageDict, UNREDACTABLE_KEYS), "Contents", streamRef)
        // Resources may be inherited from an ancestor /Pages node rather than present
        // on the leaf dict. Bake the effective resources (plus any form clones) onto
        // the page before pruning so the image XObject entry is actually removed
        // (pruning a missing local /Resources no-ops and the image stream survives).
        val resourcesWithClones = withSoftMaskAdditions(withFormAdditions(markedResources, formRedaction) ?: markedResources, maskRedaction, finalOps)
        if (resourcesWithClones != null) newPage = withEntry(newPage, "Resources", resourcesWithClones)
        newPage = prunePageResourceXObjects(newPage, engine.droppedImageNames, engine.survivingImageNames)
        newPage = prunePageResourceXObjects(newPage, formRedaction.droppedNames, namesStillDrawn(finalOps))
        // A font that only the redacted text used goes, Type 3 glyph pictures and all (#1013).
        newPage.getDict("Resources", effective)?.let { res ->
            val pruned = withoutUnpaintedPatterns(withoutUnshownFonts(res, finalOps, keepAll = lendsFonts(res)), finalOps, engine.tilingPatternNames)
            if (pruned != null && pruned !== res) newPage = withEntry(newPage, "Resources", pruned)
        }
        // The images whose Do went, and the forms whose Do now names a redacted copy, which a shared
        // or inherited resource dictionary still names (#1010, #1007).
        val drawnNow = namesStillDrawn(finalOps)
        val renamedAway = formRedaction.renames.values.map { it.from }.toSet() - drawnNow
        newPage = prunePageResourceXObjects(newPage, renamedAway, drawnNow)
        val droppedImages = ((engine.droppedImageNames - engine.survivingImageNames) + renamedAway).mapNotNull { name ->
            pageResources?.getDict("XObject", effective)?.get(name) as? PdfReference
        }.toSet()
        val droppedAnnots = ArrayList<PdfReference>()
        newPage = pruneIntersectingAnnots(newPage, regions, droppedAnnots)
        // Stage the page first: what follows reads effective objects, and a widget's
        // field is only reachable through the /Annots this call just rewrote.
        updateObject(ref, newPage)
        unnameUndrawnXObjects(ref, pageDict, droppedImages)
        stripStructureText(pageDict, engine.lostMcids)
        // Set before the field detach, which raises on a malformed /Parent chain: the
        // page is already rewritten, so a caller that catches must still be refused an
        // incremental save (it would leave the original bytes in the file).
        redactionStaged = true
        // Content first, fields second: anything taken off the page loses its content
        // whatever else still names it, and that must not depend on the field walk
        // below, which can raise.
        // A removed attachment's file goes from the catalog's /EmbeddedFiles tree too, before
        // the scrub takes /FS: the document loses that attachment (ISO 32000-1, 7.7.4; #632).
        removeEmbeddedFilesOf(droppedAnnots)
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
    internal val formDescent = ArrayDeque<Long>()

    /** The copy each form on [formDescent] is being redacted into, so a form that draws itself names its copy (#1007). */
    internal val descentTargets = HashMap<Long, PdfReference>()

    /** Form object numbers whose ORIGINAL object is already spoken for. */
    internal val claimedForms = HashSet<Long>()

    /** Form identity, [formKey] of object number plus rectangles plus pen, to the object holding that redaction. */
    internal val redactedFormCache = LinkedHashMap<String, PdfReference>()

    /** A number per font a form inherits, for [formKey]. A [PdfFont] compares by identity. */
    internal val redactionFontIds = HashMap<PdfFont, Int>()

    /**
     * Form streams as they stood when the CURRENT redaction call began. The first
     * invocation of a form stages its rewrite over the original object, so a
     * sibling invocation that read the staged state would redact an already
     * redacted stream and lose content only the first invocation's region covered.
     */
    internal val formSources = LinkedHashMap<Long, PdfStream>()

    /**
     * The page being redacted, for the fallback a form with no `/Resources` of
     * its own takes (7.8.3). Mirrors `PageRenderer.pageResources`: both use the
     * PAGE rather than the immediate invoker, because both cache a rewritten
     * form by object number and share it across invocations.
     */
    internal var redactionPageResources: PdfDictionary? = null

    /** What the current redaction call does to an image that meets a region (#926). */
    internal var redactionImages: RedactImages = RedactImages.Remove

    /**
     * Resolver that sees STAGED objects, not only the ones in the base document.
     * A clone minted by an earlier redaction lives in the staging map and nowhere
     * else, so a lookup through [base] alone finds nothing: the next call would not
     * recognise the clone as a form, would not record its `Do`, and would leave the
     * second region's content inside it untouched.
     */
    internal val effective = IndirectResolver { effectiveObject(it.objectNumber) }

    /**
     * Regions already redacted on a page, across every [redactRegions] call this
     * editor has made so far. Keyed by page object number.
     *
     * Every OVERLAPPING earlier region is repainted alongside this call's own, so
     * an earlier call's black box survives even when the content rewrite (which
     * re-parses that box as an ordinary filled path, like any other) drops all or
     * part of it because a later region overlaps it. A non-overlapping earlier
     * region is not repainted: nothing in this call's rewrite could have touched
     * its box, so it is still sitting in the stream from whichever call painted
     * it, and painting it again would only add a second, identical rectangle.
     * Deliberately NOT cleared by [beginRedactionCall]:
     * unlike the per-call form bookkeeping below, this has to survive between
     * calls to do its job.
     */
    private val redactedRegionsByPage = LinkedHashMap<Long, MutableList<KiteRectangle>>()

    private fun fmt(d: Double): String = PdfObjectWriter.formatReal(d)

    private fun pageReference(page: PdfPage): PdfReference = page.reference
        ?: throw IllegalArgumentException("Page ${page.index} has no indirect reference and cannot be edited")

    internal fun withEntry(dict: PdfDictionary, key: String, value: PdfObject): PdfDictionary =
        PdfDictionary(LinkedHashMap(dict.map).apply { put(key, value) })

    /**
     * Produce the updated document bytes: original + appended objects + new
     * xref section + trailer. When nothing was staged or overridden this is a
     * verbatim copy of the original.
     *
     * When the document opened through repair, its own table is the broken part, so
     * the appended table stands alone: it lists every object at the offset repair found
     * and names no `/Prev`, and the objects that lived inside an object stream, which a
     * classic table cannot point into, are written out again (#586).
     */
    public fun saveIncremental(): ByteArray {
        check(!redactionStaged) {
            "Redaction was staged; call saveRewritten() instead. An incremental save " +
                "would leave the original, unredacted content recoverable in the file."
        }
        if (staged.isEmpty() && trailerOverrides.isEmpty()) return base.rawBytes.copyOf()

        val out = ByteArrayBuilder(base.rawBytes.size + 1024)
        out.append(base.rawBytes)
        // The appended section must start on its own line so byte offsets and
        // the "N G obj" header parse cleanly regardless of how the original ended.
        val last = base.rawBytes.lastOrNull()
        if (last != null && last != '\n'.code.toByte() && last != '\r'.code.toByte()) {
            out.append('\n'.code.toByte())
        }

        val entries = ArrayList<ClassicXrefWriter.Entry>()
        fun append(num: Long, generation: Int, value: PdfObject) {
            entries.add(ClassicXrefWriter.Entry(num, out.size(), generation))
            out.append("$num $generation obj\n".encodeToByteArray())
            PdfObjectWriter.writeObject(value, out)
            out.append("\nendobj\n".encodeToByteArray())
        }
        for ((num, s) in staged.entries.sortedBy { it.key }) {
            // Staged objects live in plain text; an encrypted base document
            // needs them encrypted with its own parameters on the way out.
            append(num, s.generation, encryptor?.encryptIndirect(num, s.generation, s.value) ?: s.value)
        }
        if (base.isRepaired) {
            // The encryption dictionary is never encrypted, wherever a broken file keeps it.
            val encryptNum = (base.trailer["Encrypt"] as? PdfReference)?.objectNumber
            for ((num, entry) in base.xref.entries.sortedBy { it.key }) {
                if (num <= 0L || num in staged) continue
                when (entry) {
                    is XrefEntry.InUse -> entries.add(ClassicXrefWriter.Entry(num, entry.byteOffset, entry.generation))
                    is XrefEntry.Compressed -> {
                        val value = base.resolve(PdfReference(num, 0)) ?: continue
                        append(num, 0, if (num == encryptNum) value else encryptor?.encryptIndirect(num, 0, value) ?: value)
                    }
                    is XrefEntry.Free -> Unit
                }
            }
        }

        val xrefOffset = out.size()
        ClassicXrefWriter.write(out, entries)
        writeTrailer(out, xrefOffset)
        return out.toByteArray()
    }

    /* ─── xref + trailer ─────────────────────────────────────────────────── */

    /**
     * The trailer of an incremental section. Its `/Prev` names the file's last table, which
     * fills in the objects this section does not list; a repaired file's section lists them
     * all and names none.
     */
    private fun writeTrailer(out: ByteArrayBuilder, xrefOffset: Int) {
        val prevXref = if (base.isRepaired) null else XrefParser.findStartXref(ByteReader(base.rawBytes))
        val maxNum = maxOf(
            base.xref.keys.maxOrNull() ?: 0L,
            staged.keys.maxOrNull() ?: 0L,
        )

        val dict = LinkedHashMap<String, PdfObject>()
        dict["Size"] = PdfInt(maxNum + 1)
        base.trailer["Root"]?.let { dict["Root"] = it }
        base.trailer["Info"]?.let { dict["Info"] = it }
        base.trailer["Encrypt"]?.let { dict["Encrypt"] = it }
        // Keep the permanent first /ID string (the encryption key is made from it) and write a new
        // second one, as an update does (ISO 32000-1, 14.4; #738).
        dict["ID"] = DocumentId.updated(base.trailer["ID"], base.rawBytes, out.toByteArray())
        prevXref?.let { dict["Prev"] = PdfInt(it.toLong()) }
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

        // A page taken out of the page tree is not written, whatever still names it: an outline
        // item, a link, an action, /OpenAction, a named destination or a structure element's /Pg.
        // A destination names a page of the document (ISO 32000-1, 12.3.2), so each reference to
        // the removed page is written as null, which readers take as no destination (#1016).
        val removedPages = pageOrderState?.let { order ->
            val kept = order.mapTo(HashSet()) { it.objectNumber }
            base.pages.mapNotNull { it.reference?.objectNumber }.filterNot { it in kept }.toHashSet()
        } ?: emptySet()

        // Reachability BFS over effective (staged-or-original) objects.
        val reachable = LinkedHashSet<Long>()
        val queue = ArrayDeque<Long>()
        for (r in roots) if (effectiveObject(r.objectNumber) != null && reachable.add(r.objectNumber)) {
            queue.addLast(r.objectNumber)
        }
        while (queue.isNotEmpty()) {
            val obj = effectiveObject(queue.removeFirst()) ?: continue
            collectReferences(obj) { refNum ->
                if (refNum !in removedPages && effectiveObject(refNum) != null && reachable.add(refNum)) queue.addLast(refNum)
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
        val out = ByteArrayBuilder(base.rawBytes.size)
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
        trailer["ID"] = DocumentId.updated(base.trailer["ID"], base.rawBytes, out.toByteArray())
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

        val out = ByteArrayBuilder(base.rawBytes.size)
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
            id = DocumentId.updated(base.trailer["ID"], base.rawBytes, out.toByteArray()),
        )
        out.append("$xrefStmNum 0 obj\n".encodeToByteArray())
        PdfObjectWriter.writeObject(xrefStream, out)
        out.append("\nendobj\n".encodeToByteArray())
        out.append("startxref\n$xrefOffset\n%%EOF\n".encodeToByteArray())
        return out.toByteArray()
    }

    /** Staged override if present, else the original object; null for free/missing. */
    internal fun effectiveObject(num: Long): PdfObject? =
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

    internal companion object {
        /** The field entries a widget takes from its field when it becomes a field of its own (ISO 32000-1, Tables 220 and 222). */
        private val INHERITED_FIELD_KEYS = listOf("FT", "Ff", "V", "DV", "DA", "Q", "DS", "RV", "MaxLen", "Opt", "TI", "I", "TU", "TM", "AA")
        /** The font subtypes whose single-byte codes the writer's WinAnsi text encoder writes. */
        private val SIMPLE_FONTS = setOf("Type1", "TrueType", "MMType1")

        /** Text-showing operators (§9.4.3). */
        val TEXT_SHOW_OPERATORS = setOf("Tj", "TJ", "'", "\"")

        /**
         * What an annotation carries: its text (§12.5.2, Table 168), the same text as
         * rich content (§12.5.6.2), the stream that draws it, the file it attaches
         * (§12.5.6.15), its sound (§12.5.6.16), its movie (§12.5.6.17), and the
         * action, additional actions and appearance characteristics that reach the
         * rest (a rendition action's media clip, §12.5.6.18, and a caption in
         * `/MK /CA`). One removed from the page has no use for any of them, so this
         * closes the class instead of chasing one subtype at a time.
         */
        val SCRUBBED_ANNOT_KEYS = listOf("Contents", "RC", "AP", "FS", "Sound", "Movie", "A", "AA", "MK")

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
