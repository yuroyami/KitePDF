package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteReader
import io.github.yuroyami.kitepdf.core.PdfFormatException
import io.github.yuroyami.kitepdf.core.WrongPasswordException
import io.github.yuroyami.kitepdf.crypto.Decryptor
import io.github.yuroyami.kitepdf.crypto.StandardSecurityHandler
import io.github.yuroyami.kitepdf.filters.FilterChain
import io.github.yuroyami.kitepdf.parser.IndirectResolver
import io.github.yuroyami.kitepdf.parser.Lexer
import io.github.yuroyami.kitepdf.parser.Parser
import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfObject
import io.github.yuroyami.kitepdf.parser.PdfReference
import io.github.yuroyami.kitepdf.parser.PdfRepair
import io.github.yuroyami.kitepdf.parser.PdfStream
import io.github.yuroyami.kitepdf.parser.PdfString
import io.github.yuroyami.kitepdf.parser.Token
import io.github.yuroyami.kitepdf.parser.XrefEntry
import io.github.yuroyami.kitepdf.parser.XrefParser
import io.github.yuroyami.kitepdf.writer.PdfEditor

/**
 * A loaded PDF document. Construct with [open].
 *
 * ```
 * val doc = PdfDocument.open(bytes)                        // unencrypted
 * val doc = PdfDocument.open(bytes, "secret".encodeToByteArray())
 *
 * println("${doc.pageCount} pages — PDF ${doc.version}")
 * println(doc.pages[0].extractText())
 *
 * // Editing returns a writer; the document itself is immutable.
 * val editor = doc.edit()
 * editor.setInfo(title = "Reviewed")
 * val updated: ByteArray = editor.saveIncremental()
 * ```
 *
 * Holds the raw byte buffer plus the xref table; pages and indirect objects
 * are resolved lazily and cached.
 */
class PdfDocument private constructor(
    val version: String,
    val bytes: ByteArray,
    val xref: Map<Long, XrefEntry>,
    val trailer: PdfDictionary,
    private val security: StandardSecurityHandler?,
) : IndirectResolver {

    /** True if the document is encrypted. */
    val isEncrypted: Boolean get() = security != null

    /** True if [open] found a working password for an encrypted doc. */
    val isAuthenticated: Boolean get() = security?.isAuthenticated ?: true

    private val reader = ByteReader(bytes)
    private val objectCache = HashMap<Long, PdfObject?>()
    private val objStreamCache = HashMap<Long, Map<Int, PdfObject>>()

    /** The document catalog (/Root in the trailer). */
    val catalog: PdfDictionary by lazy {
        val rootRef = trailer.getRef("Root")
            ?: throw PdfFormatException("Trailer has no /Root reference")
        resolve(rootRef) as? PdfDictionary
            ?: throw PdfFormatException("/Root did not resolve to a dictionary")
    }

    /**
     * Document /Info dictionary (title, author, dates, …). Returns an empty
     * info object if the trailer has no /Info or it doesn't resolve.
     */
    val info: PdfDocumentInfo by lazy {
        val raw = trailer["Info"] ?: return@lazy PdfDocumentInfo()
        val dict = when (raw) {
            is PdfReference -> resolve(raw) as? PdfDictionary
            is PdfDictionary -> raw
            else -> null
        } ?: return@lazy PdfDocumentInfo()
        PdfDocumentInfo.parse(dict)
    }

    /**
     * Document permissions (ISO 32000-1 §7.6.3.2 Table 22). Always allow-all
     * for unencrypted documents; reflects the `/P` bit-flags from the security
     * handler otherwise.
     */
    val permissions: PdfPermissions by lazy {
        PdfPermissions.from(security)
    }

    /**
     * Raw XMP metadata packet as a UTF-8 string, or `null` if the catalog has
     * no `/Metadata` stream (or it doesn't resolve to one).
     */
    val xmpMetadataXml: String? by lazy {
        val raw = catalog["Metadata"] ?: return@lazy null
        val stream = when (raw) {
            is PdfReference -> resolve(raw) as? PdfStream
            is PdfStream -> raw
            else -> null
        } ?: return@lazy null
        val bytes = io.github.yuroyami.kitepdf.filters.FilterChain.decode(stream)
        // XMP packets are UTF-8. Skip a leading BOM if present.
        val start = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) 3 else 0
        bytes.decodeToString(start)
    }

    /**
     * Parsed XMP metadata. Extracts the common Dublin Core / Adobe PDF /
     * XMP-basic properties. Falls back to the trailer `/Info` dict when XMP
     * is absent — call [info] explicitly if you need both views.
     */
    val xmp: PdfXmpMetadata? by lazy {
        xmpMetadataXml?.let { PdfXmpMetadata.parse(it) }
    }

    /** All pages in document order (lazily built from the catalog's /Pages tree). */
    val pages: List<PdfPage> by lazy { buildPageList() }

    val pageCount: Int get() = pages.size

    /** Indirect-object-number → zero-based page index. Built alongside [pages]. */
    private val pageRefToIndex = HashMap<Long, Int>()

    /**
     * Parsed `/PageLabels` number tree, or empty if the catalog doesn't have
     * one. Drives [PdfPage.label].
     */
    internal val pageLabels: PageLabelTree by lazy {
        val labels = catalog.getDict("PageLabels", this) ?: return@lazy PageLabelTree.EMPTY
        PageLabelTree.parse(labels, this)
    }

    /**
     * Named-destination catalog (`/Dests` legacy dict + `/Names /Dests` name
     * tree). Used by [resolveDestination].
     */
    private val destCatalog: DestinationCatalog by lazy {
        DestinationCatalog.build(catalog, this)
    }

    /**
     * Document outline (bookmarks). Top-level entries from the catalog
     * `/Outlines /First` chain; empty list if the document has no outline.
     */
    val outlines: List<PdfOutline> by lazy {
        PdfOutline.buildTree(catalog, this)
    }

    /** Initial UI panel hint (`/PageMode`). [PageMode.UseNone] when absent. */
    val pageMode: PageMode get() = PageMode.fromName(catalog.getName("PageMode"))

    /** Initial page-layout hint (`/PageLayout`). [PageLayout.SinglePage] when absent. */
    val pageLayout: PageLayout get() = PageLayout.fromName(catalog.getName("PageLayout"))

    /**
     * Viewer-preferences hints (`/ViewerPreferences`). Returns
     * [PdfViewerPreferences.DEFAULT] when the catalog doesn't carry the dict.
     */
    val viewerPreferences: PdfViewerPreferences by lazy {
        val dict = catalog.getDict("ViewerPreferences", this) ?: return@lazy PdfViewerPreferences.DEFAULT
        PdfViewerPreferences.parse(dict, this)
    }

    /**
     * Article threads (`/Threads`). Each thread is a reading-order sequence
     * of bead rectangles on pages — used by readers to jump between columns
     * in a multi-column layout. Empty list when the catalog has no /Threads.
     */
    val articleThreads: List<PdfArticleThread> by lazy {
        pages  // touch to populate pageRefToIndex
        PdfArticleThread.parseAll(catalog, this, pageRefToIndex)
    }

    /** Document language tag (BCP 47), e.g. "en-US", "fr-CA". `null` when /Lang is absent. */
    val language: String? get() = (catalog["Lang"] as? PdfString)?.asText()

    /**
     * Document-level JavaScript scripts from `/Names /JavaScript`. Map keys
     * are the script names; values are the JS source. Empty when none.
     */
    val documentJavaScripts: Map<String, String> by lazy {
        val names = catalog.getDict("Names", this) ?: return@lazy emptyMap()
        val jsTree = names.getDict("JavaScript", this) ?: return@lazy emptyMap()
        val entries = io.github.yuroyami.kitepdf.parser.NameTreeWalker.collect(jsTree, this)
        val out = LinkedHashMap<String, String>()
        for ((name, value) in entries) {
            val dict = when (value) {
                is PdfReference -> resolve(value) as? PdfDictionary
                is PdfDictionary -> value
                else -> null
            } ?: continue
            val action = PdfAction.parse(dict, this)
            if (action is PdfAction.JavaScript) out[name] = action.script
        }
        out
    }

    /**
     * Embedded file attachments — typically the document author's
     * supplementary files: source artwork, datasets, XML schemas, etc.
     * Empty list when the document has no /EmbeddedFiles name tree.
     */
    val attachments: List<PdfAttachment> by lazy {
        PdfAttachment.parseAll(catalog, this)
    }

    /**
     * Tagged-PDF accessibility metadata. `null` when the document has no
     * `/MarkInfo` dict — i.e. it is not tagged.
     */
    val markInfo: PdfMarkInfo? by lazy {
        PdfMarkInfo.parse(catalog.getDict("MarkInfo", this))
    }

    /**
     * Optional Content / layers metadata (read-only). `null` when the
     * catalog has no `/OCProperties`.
     */
    val optionalContent: PdfOptionalContent? by lazy {
        PdfOptionalContent.parse(catalog, this)
    }

    /**
     * Interactive form (AcroForm) catalog metadata. `null` when the
     * catalog has no `/AcroForm` entry — the document carries no
     * interactive form fields.
     */
    val acroForm: PdfAcroForm? by lazy {
        PdfAcroForm.parse(catalog, this)
    }

    /**
     * Flattened interactive-form fields (the AcroForm field tree's terminal
     * fields, with inheritable attributes resolved). Empty when the document
     * has no form. Drives form-filling via [io.github.yuroyami.kitepdf.writer.PdfEditor].
     */
    val formFields: List<PdfFormField> by lazy {
        PdfFormField.collect(catalog, this)
    }

    /** Look up a form field by its fully-qualified name; null if not present. */
    fun formField(fullyQualifiedName: String): PdfFormField? =
        formFields.firstOrNull { it.fullyQualifiedName == fullyQualifiedName }

    /**
     * Resolve a `/Dest` (on a Link annotation or outline) or `/A /D` (a GoTo
     * action's destination) to a typed [PdfDestination]. Accepts any of:
     * an explicit array, a name pointing into the catalog's name-tree, or a
     * dict containing `/D` (a wrapped destination). Returns `null` when the
     * destination can't be resolved.
     */
    fun resolveDestination(raw: PdfObject?): PdfDestination? {
        // Touch [pages] to ensure pageRefToIndex is populated.
        pages
        return DestinationParser.resolve(raw, destCatalog, this, pageRefToIndex)
    }

    /* ─── Editing / writing ──────────────────────────────────────────────── */

    /**
     * Open an incremental-update editor over this document. Edits are saved by
     * appending to the original bytes (see [PdfEditor]); this document instance
     * itself is never mutated.
     */
    fun edit(): PdfEditor = PdfEditor(this)

    /* ─── IndirectResolver ───────────────────────────────────────────────── */

    override fun resolve(ref: PdfReference): PdfObject? {
        objectCache[ref.objectNumber]?.let { return it }
        val entry = xref[ref.objectNumber] ?: return null
        // Lenient salvage: a single corrupt object must not abort the whole
        // document (MuPDF resolves objects best-effort). On any parse failure we
        // cache null and keep going; the page walk and renderers tolerate nulls.
        val resolved: PdfObject? = runCatching {
            when (entry) {
                is XrefEntry.Free -> null
                is XrefEntry.InUse -> resolveInPlace(entry)
                is XrefEntry.Compressed -> resolveFromObjectStream(entry)
            }
        }.getOrNull()
        objectCache[ref.objectNumber] = resolved
        return resolved
    }

    /**
     * Re-entrancy guard: if we're already resolving an indirect /Length, we
     * must not recurse into the same resolver path (the second seek would
     * clobber the first one's position). The guard set is checked before any
     * /Length resolution dispatch.
     */
    private val activelyResolving = HashSet<Long>()

    private fun resolveInPlace(entry: XrefEntry.InUse): PdfObject {
        // H6: cyclic indirect /Length (A->B->A) would recurse to a StackOverflow
        // (a hard crash on Kotlin/Native). If we're already resolving this object,
        // break the cycle by throwing — resolve()'s runCatching caches null.
        if (entry.objectNumber in activelyResolving) {
            throw PdfFormatException("cyclic object reference ${entry.objectNumber}")
        }
        reader.seek(entry.byteOffset)
        activelyResolving.add(entry.objectNumber)
        try {
            val parser = Parser(Lexer(reader), resolver = this)
            val indirect = parser.readIndirectObject()
            // M9: a stale/wrong xref offset can land us on a different object.
            // Reject a number mismatch (throw -> cached null) rather than silently
            // returning the WRONG object under the requested object number.
            if (indirect.number != entry.objectNumber) {
                throw PdfFormatException(
                    "xref offset for object ${entry.objectNumber} parsed object ${indirect.number}"
                )
            }
            return maybeDecrypt(indirect.number, indirect.generation, indirect.value)
        } finally {
            activelyResolving.remove(entry.objectNumber)
        }
    }

    private fun maybeDecrypt(objNum: Long, genNum: Int, value: PdfObject): PdfObject {
        val s = security ?: return value
        if (!s.isAuthenticated) return value
        return Decryptor.decryptIndirect(objNum, genNum, value, s)
    }

    private fun resolveFromObjectStream(entry: XrefEntry.Compressed): PdfObject? {
        val members = objStreamCache.getOrPut(entry.containingObjectStream) {
            decodeObjectStream(entry.containingObjectStream)
        }
        return members[entry.indexInObjectStream]
    }

    /**
     * Decode an /ObjStm (object stream) — PDF 1.5+ §7.5.7. The decoded body
     * starts with N pairs of (objNum, offset) integers, then the N objects
     * concatenated. We parse the header and slice each object out.
     */
    private fun decodeObjectStream(objStreamRef: Long): Map<Int, PdfObject> {
        val containerRef = PdfReference(objStreamRef, 0)
        // Look up directly via xref (avoid recursion through resolve cache).
        val entry = xref[objStreamRef] as? XrefEntry.InUse
            ?: throw PdfFormatException("ObjStm $objStreamRef not in xref as in-use")
        reader.seek(entry.byteOffset)
        val parser = Parser(Lexer(reader), resolver = this)
        val indirect = parser.readIndirectObject()
        val stream = indirect.value as? PdfStream
            ?: throw PdfFormatException("ObjStm $objStreamRef is not a stream")
        val n = stream.dict.getInt("N")?.toInt()
            ?: throw PdfFormatException("ObjStm $objStreamRef missing /N")
        val first = stream.dict.getInt("First")?.toInt()
            ?: throw PdfFormatException("ObjStm $objStreamRef missing /First")

        val decoded = FilterChain.decode(stream)

        // Parse the header (N pairs of integers) from the decoded bytes.
        val headerParser = Lexer(ByteReader(decoded))
        val offsets = IntArray(n)
        for (i in 0 until n) {
            val objNumTok = headerParser.nextToken() as? Token.Integer
                ?: throw PdfFormatException("ObjStm header: expected obj num")
            val offsetTok = headerParser.nextToken() as? Token.Integer
                ?: throw PdfFormatException("ObjStm header: expected obj offset")
            // We don't strictly need objNumTok here — index inside stream IS our key.
            offsets[i] = offsetTok.value.toInt()
            // touch to silence unused-warning lint (keeps shape for future use)
            objNumTok.value
        }

        // For each object, parse from (first + offsets[i]). One ByteReader over
        // the decoded buffer, seeked per member — the members share the buffer.
        val out = HashMap<Int, PdfObject>(n)
        val objReader = ByteReader(decoded)
        for (i in 0 until n) {
            objReader.seek(first + offsets[i])
            out[i] = Parser(Lexer(objReader)).readObject()
        }
        // mention containerRef so tooling sees it's intentional context
        if (containerRef.objectNumber < 0) error("unreachable")
        return out
    }

    /* ─── Page tree ──────────────────────────────────────────────────────── */

    private fun buildPageList(): List<PdfPage> {
        val pagesRef = catalog.getRef("Pages")
            ?: throw PdfFormatException("Catalog has no /Pages reference")
        val rootNode = resolve(pagesRef) as? PdfDictionary
            ?: throw PdfFormatException("/Pages did not resolve to a dictionary")
        val acc = mutableListOf<PdfPage>()
        walkPageTree(rootNode, inherited = PageInheritable(), out = acc, visited = HashSet(), depth = 0)
        return acc
    }

    /**
     * Recursively flatten the page tree. Guarded against cyclic and pathologically
     * deep `/Kids` graphs (malformed/adversarial input) by a visited-ref set and a
     * depth cap; bad kids are skipped rather than aborting the whole document.
     */
    private fun walkPageTree(
        node: PdfDictionary,
        inherited: PageInheritable,
        out: MutableList<PdfPage>,
        visited: HashSet<Long>,
        depth: Int,
        sourceRef: PdfReference? = null,
    ) {
        if (depth > MAX_PAGE_TREE_DEPTH) return
        val merged = inherited.merge(node)
        val type = node.getName("Type")
        when {
            type == "Page" -> {
                val index = out.size
                out.add(PdfPage(this, node, merged, index, sourceRef))
                if (sourceRef != null) pageRefToIndex[sourceRef.objectNumber] = index
            }
            type == "Pages" || (type == null && node["Kids"] != null) -> {
                val kids = node.getArray("Kids", this) ?: return
                for (kidObj in kids) {
                    val kidRef = kidObj as? PdfReference
                    if (kidRef != null && !visited.add(kidRef.objectNumber)) continue  // cycle guard
                    val kidDict = (if (kidRef != null) resolve(kidRef) else kidObj) as? PdfDictionary
                        ?: continue  // skip unresolvable / non-dict kids
                    walkPageTree(kidDict, merged, out, visited, depth + 1, kidRef)
                }
            }
            // Unknown / typeless leaf with no /Kids: skip leniently.
        }
    }

    /* ─── Companion: open() ──────────────────────────────────────────────── */

    companion object {

        fun open(
            bytes: ByteArray,
            password: ByteArray = byteArrayOf(),
            allowInvalidPassword: Boolean = false,
        ): PdfDocument {
            val reader = ByteReader(bytes)
            // Header garbage is tolerated; a missing %PDF- marker is not fatal.
            val version = runCatching { parseHeader(reader) }.getOrElse { "1.4" }

            // A structurally-good but unauthenticated encrypted document must NOT
            // be handed back silently; its streams/strings decode to ciphertext
            // garbage. Signal it unless the caller opted into read-only access.
            fun finish(doc: PdfDocument): PdfDocument {
                if (doc.isEncrypted && !doc.isAuthenticated && !allowInvalidPassword) {
                    throw WrongPasswordException(
                        "PDF is encrypted and the supplied password did not authenticate " +
                            "(pass allowInvalidPassword=true to open read-only)."
                    )
                }
                return doc
            }

            // Fast path: trust the cross-reference chain, but validate that it
            // actually yields a resolvable catalog + page tree. A wrong-offset or
            // truncated xref silently produces garbage, so we probe before trusting.
            // Note: the auth check runs OUTSIDE this runCatching so a wrong password
            // on a structurally-good doc throws rather than falling to repair.
            val normal = runCatching {
                val m = parseWithPrevChain(reader)
                val sec = buildSecurityHandler(m.entries, m.trailer, bytes, password)
                val doc = PdfDocument(version, bytes, m.entries, m.trailer, sec)
                require(isStructurallyUsable(doc)) { "xref did not resolve a page tree" }
                doc
            }.getOrNull()
            if (normal != null) return finish(normal)

            // Recovery path: rebuild the xref by scanning the raw bytes.
            val repaired = PdfRepair.rebuild(reader)
            val sec = buildSecurityHandler(repaired.entries, repaired.trailer, bytes, password)
            return finish(PdfDocument(version, bytes, repaired.entries, repaired.trailer, sec))
        }

        /**
         * Cheap structural probe used to decide whether the parsed xref is good
         * enough or we must fall back to repair. Resolves /Root and the /Pages
         * root node only (not the full tree), keeping lazy-open semantics.
         */
        private fun isStructurallyUsable(doc: PdfDocument): Boolean = runCatching {
            val pagesRef = doc.catalog.getRef("Pages") ?: return@runCatching false
            doc.resolve(pagesRef) is PdfDictionary
        }.getOrDefault(false)

        /**
         * Build a [StandardSecurityHandler] if /Encrypt is present in the trailer.
         * Returns null for unencrypted documents. The /Encrypt object is resolved
         * via the already-merged xref [entries] (works for repaired docs too).
         */
        private fun buildSecurityHandler(
            entries: Map<Long, XrefEntry>,
            trailer: PdfDictionary,
            bytes: ByteArray,
            password: ByteArray,
        ): StandardSecurityHandler? {
            val encryptObj = trailer["Encrypt"] ?: return null
            val encryptDict = when (encryptObj) {
                is PdfDictionary -> encryptObj
                is PdfReference -> resolveDictDirect(bytes, entries, encryptObj) ?: return null
                else -> return null
            }
            val fileIdFirst = (trailer["ID"] as? PdfArray)?.let {
                (it.firstOrNull() as? io.github.yuroyami.kitepdf.parser.PdfString)?.bytes
            } ?: ByteArray(0)
            // The single password the user supplies is tried as BOTH the user and
            // the owner password (either one authenticates the document).
            return StandardSecurityHandler(
                encryptDict,
                fileIdFirst,
                userPassword = password,
                ownerPassword = password,
            )
        }

        /**
         * Resolve a direct (in-file) indirect reference to a dictionary using the
         * merged xref, without a full [PdfDocument] resolver. Used for /Encrypt,
         * which must be an unencrypted in-file dictionary (never in an ObjStm).
         */
        private fun resolveDictDirect(
            bytes: ByteArray,
            entries: Map<Long, XrefEntry>,
            ref: PdfReference,
        ): PdfDictionary? {
            val entry = entries[ref.objectNumber] as? XrefEntry.InUse ?: return null
            val reader = ByteReader(bytes)
            reader.seek(entry.byteOffset)
            return runCatching {
                Parser(Lexer(reader)).readIndirectObject().value as? PdfDictionary
            }.getOrNull()
        }

        /**
         * Walk the xref-section chain via /Prev links (ISO 32000-1 §7.5.6).
         *
         * PDFs with incremental updates store newer changes in appended xref
         * sections whose trailers point back to the previous one with /Prev.
         * The most-recent entry for an object number wins; older sections fill
         * in objects the newer sections didn't touch. We also pick up /Root
         * from the newest trailer that defines it.
         */
        private fun parseWithPrevChain(reader: ByteReader): io.github.yuroyami.kitepdf.parser.XrefAndTrailer {
            val visited = HashSet<Int>()
            val merged = HashMap<Long, XrefEntry>()
            var newestTrailer: PdfDictionary? = null

            var startOffset = XrefParser.findStartXref(reader)
            var safetyCounter = 0
            while (startOffset >= 0 && safetyCounter < MAX_PREV_HOPS) {
                if (!visited.add(startOffset)) break   // cycle guard
                val section = XrefParser(reader).parseFromOffset(startOffset)

                // Hybrid-reference files (§7.5.8.4): a classic xref section may carry
                // a /XRefStm pointing at a cross-reference stream that holds the
                // entries for compressed objects (listed as free in the classic
                // table). Overlay those onto this section before merging.
                var sectionEntries: Map<Long, XrefEntry> = section.entries
                val xrefStm = section.trailer.getInt("XRefStm")?.toInt()
                if (xrefStm != null && xrefStm >= 0 && visited.add(xrefStm)) {
                    val stm = runCatching { XrefParser(reader).parseFromOffset(xrefStm) }.getOrNull()
                    if (stm != null) {
                        val combined = HashMap(section.entries)
                        for ((num, e) in stm.entries) {
                            val existing = combined[num]
                            if (existing == null || existing is XrefEntry.Free) combined[num] = e
                        }
                        sectionEntries = combined
                    }
                }

                // Newer sections were visited first; fill in gaps from older ones.
                for ((num, entry) in sectionEntries) {
                    if (num !in merged) merged[num] = entry
                }
                if (newestTrailer == null) newestTrailer = section.trailer
                val prev = section.trailer.getInt("Prev")?.toInt() ?: -1
                startOffset = prev
                safetyCounter++
            }
            val trailer = newestTrailer
                ?: throw PdfFormatException("No xref section yielded a trailer")
            return io.github.yuroyami.kitepdf.parser.XrefAndTrailer(merged, trailer)
        }

        /** Bound on /Prev chain length — generous; cycle guard catches the rest. */
        private const val MAX_PREV_HOPS = 32

        /** Bound on page-tree recursion depth — guards adversarial/cyclic /Kids. */
        private const val MAX_PAGE_TREE_DEPTH = 50

        private fun parseHeader(reader: ByteReader): String {
            // The header may have up to 1024 leading bytes of garbage before %PDF-.
            val signature = "%PDF-".encodeToByteArray()
            for (i in 0 until minOf(1024, reader.size - signature.size)) {
                if (reader.matches(signature, at = i)) {
                    val sb = StringBuilder()
                    var p = i + signature.size
                    while (p < reader.size && p < i + signature.size + 8) {
                        val b = reader.bytes[p].toInt() and 0xFF
                        if (b == '\r'.code || b == '\n'.code) break
                        sb.append(b.toChar())
                        p++
                    }
                    return sb.toString()
                }
            }
            throw PdfFormatException("PDF header not found")
        }
    }
}

/**
 * Attributes inherited down the page tree (ISO 32000-1 §7.7.3.4): a child
 * page that doesn't set its own /MediaBox, /CropBox, /Resources, or /Rotate
 * inherits from the nearest ancestor that does.
 */
internal data class PageInheritable(
    val mediaBox: PdfArray? = null,
    val cropBox: PdfArray? = null,
    val resources: PdfDictionary? = null,
    val rotate: Long? = null,
) {
    fun merge(node: PdfDictionary): PageInheritable = PageInheritable(
        mediaBox = node.getArray("MediaBox") ?: mediaBox,
        cropBox = node.getArray("CropBox") ?: cropBox,
        resources = node.getDict("Resources") ?: resources,
        rotate = node.getInt("Rotate") ?: rotate,
    )
}
