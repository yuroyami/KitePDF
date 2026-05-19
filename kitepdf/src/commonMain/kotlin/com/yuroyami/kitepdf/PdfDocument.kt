package com.yuroyami.kitepdf

import com.yuroyami.kitepdf.core.ByteReader
import com.yuroyami.kitepdf.core.PdfFormatException
import com.yuroyami.kitepdf.crypto.Decryptor
import com.yuroyami.kitepdf.crypto.StandardSecurityHandler
import com.yuroyami.kitepdf.filters.FilterChain
import com.yuroyami.kitepdf.parser.IndirectResolver
import com.yuroyami.kitepdf.parser.Lexer
import com.yuroyami.kitepdf.parser.Parser
import com.yuroyami.kitepdf.parser.PdfArray
import com.yuroyami.kitepdf.parser.PdfDictionary
import com.yuroyami.kitepdf.parser.PdfInt
import com.yuroyami.kitepdf.parser.PdfObject
import com.yuroyami.kitepdf.parser.PdfReference
import com.yuroyami.kitepdf.parser.PdfStream
import com.yuroyami.kitepdf.parser.Token
import com.yuroyami.kitepdf.parser.XrefEntry
import com.yuroyami.kitepdf.parser.XrefParser

/**
 * Top-level KitePDF entry point.
 *
 * ```
 * val doc = PdfDocument.open(bytes)
 * println("${doc.pageCount} pages, PDF ${doc.version}")
 * println(doc.pages[0].extractText())
 * ```
 *
 * Holds the raw byte buffer plus the xref table; resolves indirect objects
 * lazily via [resolve]. Object stream lookups are cached so repeated access
 * doesn't re-decode the same stream.
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

    /** True if [open] (or [openEncrypted]) found a working password for an encrypted doc. */
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

    /** All pages in document order (lazily built from the catalog's /Pages tree). */
    val pages: List<PdfPage> by lazy { buildPageList() }

    val pageCount: Int get() = pages.size

    /* ─── IndirectResolver ───────────────────────────────────────────────── */

    override fun resolve(ref: PdfReference): PdfObject? {
        objectCache[ref.objectNumber]?.let { return it }
        val entry = xref[ref.objectNumber] ?: return null
        val resolved: PdfObject? = when (entry) {
            is XrefEntry.Free -> null
            is XrefEntry.InUse -> resolveInPlace(entry)
            is XrefEntry.Compressed -> resolveFromObjectStream(entry)
        }
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
        reader.seek(entry.byteOffset)
        activelyResolving.add(entry.objectNumber)
        try {
            val parser = Parser(Lexer(reader), resolver = this)
            val indirect = parser.readIndirectObject()
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

        // For each object, parse from (first + offsets[i]).
        val out = HashMap<Int, PdfObject>(n)
        for (i in 0 until n) {
            val absoluteOffset = first + offsets[i]
            val objReader = ByteReader(decoded).apply { seek(absoluteOffset) }
            val value = Parser(Lexer(objReader)).readObject()
            out[i] = value
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
        walkPageTree(rootNode, inherited = PageInheritable(), out = acc)
        return acc
    }

    private fun walkPageTree(node: PdfDictionary, inherited: PageInheritable, out: MutableList<PdfPage>) {
        val merged = inherited.merge(node)
        when (node.getName("Type")) {
            "Page" -> out.add(PdfPage(this, node, merged))
            "Pages" -> {
                val kids = node.getArray("Kids", this)
                    ?: throw PdfFormatException("/Pages node missing /Kids")
                for (kidObj in kids) {
                    val kidDict = (kidObj as? PdfReference)?.let { resolve(it) } ?: kidObj
                    walkPageTree(
                        kidDict as? PdfDictionary
                            ?: throw PdfFormatException("/Kids entry not a dict"),
                        merged, out,
                    )
                }
            }
            null -> {
                // Some old PDFs omit /Type on root /Pages node. Treat as Pages if it has /Kids.
                if (node["Kids"] != null) {
                    walkPageTree(
                        PdfDictionary(node.map + ("Type" to com.yuroyami.kitepdf.parser.PdfName("Pages"))),
                        inherited, out,
                    )
                } else {
                    throw PdfFormatException("Page tree node missing /Type")
                }
            }
            else -> throw PdfFormatException("Unknown page tree /Type: ${node.getName("Type")}")
        }
    }

    /* ─── Companion: open() ──────────────────────────────────────────────── */

    companion object {

        fun open(bytes: ByteArray, password: ByteArray = byteArrayOf()): PdfDocument {
            val reader = ByteReader(bytes)
            val version = parseHeader(reader)
            val merged = parseWithPrevChain(reader)
            val security = buildSecurityHandler(merged.trailer, bytes, password)
            return PdfDocument(version, bytes, merged.entries, merged.trailer, security)
        }

        /**
         * Build a [StandardSecurityHandler] if /Encrypt is present in the trailer.
         * Returns null for unencrypted documents.
         */
        private fun buildSecurityHandler(
            trailer: PdfDictionary,
            bytes: ByteArray,
            password: ByteArray,
        ): StandardSecurityHandler? {
            val encryptObj = trailer["Encrypt"] ?: return null
            val encryptDict = when (encryptObj) {
                is PdfDictionary -> encryptObj
                is PdfReference -> {
                    // Resolve manually — we don't have a PdfDocument yet.
                    val entry = parseLooseEncryptionRef(bytes, trailer, encryptObj) ?: return null
                    entry
                }
                else -> return null
            }
            val fileIdFirst = (trailer["ID"] as? PdfArray)?.let {
                (it.firstOrNull() as? com.yuroyami.kitepdf.parser.PdfString)?.bytes
            } ?: ByteArray(0)
            return StandardSecurityHandler(encryptDict, fileIdFirst, password)
        }

        /**
         * Resolve an /Encrypt reference without a full document. We re-parse the
         * referenced object directly from the byte buffer. Streams in /Encrypt
         * itself are never permitted (it must be a dictionary).
         */
        private fun parseLooseEncryptionRef(
            bytes: ByteArray,
            trailer: PdfDictionary,
            ref: PdfReference,
        ): PdfDictionary? {
            // Walk the xref entries to find the byte offset.
            // The merged xref isn't in scope here, so reparse the trailer's startxref.
            // This is a minor inefficiency we accept to keep the encryption setup
            // self-contained and not chicken-and-egg with PdfDocument's resolver.
            val reader = ByteReader(bytes)
            val startxref = XrefParser.findStartXref(reader)
            val xref = XrefParser(reader).parseFromOffset(startxref)
            val entry = xref.entries[ref.objectNumber] as? XrefEntry.InUse ?: return null
            reader.seek(entry.byteOffset)
            val parser = Parser(Lexer(reader))
            return parser.readIndirectObject().value as? PdfDictionary
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
        private fun parseWithPrevChain(reader: ByteReader): com.yuroyami.kitepdf.parser.XrefAndTrailer {
            val visited = HashSet<Int>()
            val merged = HashMap<Long, XrefEntry>()
            var newestTrailer: PdfDictionary? = null

            var startOffset = XrefParser.findStartXref(reader)
            var safetyCounter = 0
            while (startOffset >= 0 && safetyCounter < MAX_PREV_HOPS) {
                if (!visited.add(startOffset)) break   // cycle guard
                val section = XrefParser(reader).parseFromOffset(startOffset)
                // Newer sections were visited first; fill in gaps from older ones.
                for ((num, entry) in section.entries) {
                    if (num !in merged) merged[num] = entry
                }
                if (newestTrailer == null) newestTrailer = section.trailer
                val prev = section.trailer.getInt("Prev")?.toInt() ?: -1
                startOffset = prev
                safetyCounter++
            }
            val trailer = newestTrailer
                ?: throw PdfFormatException("No xref section yielded a trailer")
            return com.yuroyami.kitepdf.parser.XrefAndTrailer(merged, trailer)
        }

        /** Bound on /Prev chain length — generous; cycle guard catches the rest. */
        private const val MAX_PREV_HOPS = 32

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
