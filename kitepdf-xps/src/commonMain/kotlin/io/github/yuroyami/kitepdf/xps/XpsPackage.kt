package io.github.yuroyami.kitepdf.xps

import io.github.yuroyami.kitepdf.core.KiteLock
import io.github.yuroyami.kitepdf.core.kiteWarn
import io.github.yuroyami.kitepdf.core.text.TextEncoding
import io.github.yuroyami.kitepdf.core.withLock
import io.github.yuroyami.kitepdf.core.xml.KiteXml
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import io.github.yuroyami.kitepdf.core.xml.KiteXmlToken
import io.github.yuroyami.kitepdf.core.zip.ZipReader

/** OPC part access, ECMA-388 §9 and the OPC ZIP mapping in ISO/IEC 29500-2. */
internal class XpsPackage(
    bytes: ByteArray,
    /** The markup of the parsed pages the package keeps. */
    parsedPageBytes: Long = PARSED_PAGE_BYTES,
    /** The decoded image brushes the package keeps. */
    imageBytes: Long = IMAGE_BYTES,
) {
    private val zip = ZipReader(bytes)
    private val names = buildMap {
        for (name in zip.names) {
            put(name.lowercase(), name)
            resolvePart("", name)?.let { put(it.lowercase(), name) }
        }
    }
    private val lock = KiteLock()
    private val fonts = HashMap<String, XpsFont?>()

    fun read(part: String): ByteArray? = runCatching {
        names[part.lowercase()]?.let { return@runCatching zip.read(it) }
        // An interleaved OPC part consists of contiguous [n].piece entries,
        // terminated by [n].last.piece. Never return a truncated partial part.
        val chunks = ArrayList<ByteArray>()
        var size = 0L
        for (index in 0 until zip.names.size) {
            val base = "${part.lowercase()}/[$index]"
            val last = names["$base.last.piece"]
            val name = last ?: names["$base.piece"] ?: return@runCatching null
            val chunk = zip.read(name) ?: return@runCatching null
            size += chunk.size
            if (size > ZipReader.DEFAULT_MAX_ENTRY_BYTES) return@runCatching null
            chunks.add(chunk)
            if (last != null) {
                val out = ByteArray(size.toInt())
                var at = 0
                for (c in chunks) { c.copyInto(out, at); at += c.size }
                return@runCatching out
            }
        }
        null
    }.getOrNull()

    /**
     * The first [maxBytes] bytes of [part], or of its first piece when the part is interleaved,
     * inflated only as far as they go.
     */
    private fun readPrefix(part: String, maxBytes: Int): ByteArray? = runCatching {
        val name = names[part.lowercase()]
            ?: names["${part.lowercase()}/[0].piece"]
            ?: names["${part.lowercase()}/[0].last.piece"]
            ?: return@runCatching null
        zip.readPrefix(name, maxBytes)
    }.getOrNull()

    /**
     * The root element of [part] with its attributes and without children, read from the first
     * bytes of the part only. Null when those bytes hold no whole start tag (#385).
     */
    fun rootTag(part: String): KiteXmlNode.Element? {
        val head = readPrefix(part, ROOT_TAG_BYTES) ?: return null
        // The tokenizer of the full parse, so the tag reads the same either way.
        val tag = KiteXml.tokenize(TextEncoding.decode(head)).firstOrNull { it is KiteXmlToken.Open } as KiteXmlToken.Open?
        return tag?.let { KiteXmlNode.Element(it.name, it.attrs) }
    }

    /**
     * The parsed markup of the fixed page [part]. The package keeps it for the next render or
     * text read, within its budget of markup, so a page no longer keeps its tree for
     * the life of the document (#385).
     */
    fun page(part: String): KiteXmlNode.Element? {
        val key = part.lowercase()
        lock.withLock { pages.get(key) }?.let { return it.root }
        val bytes = read(part) ?: return null
        val root = runCatching { KiteXml.parse(TextEncoding.decode(bytes)).elements().firstOrNull() }.getOrNull()
            ?.takeIf { it.tag == "fixedpage" } ?: return null
        lock.withLock { pages.put(key, ParsedPage(root, bytes.size.toLong())) }
        return root
    }

    /**
     * The decoded image brush of [part]. The package keeps it for later renders within its
     * budget, and runs [decode] only on a miss (#385).
     */
    fun image(part: String, decode: () -> XpsBrushes.Image?): XpsBrushes.Image? {
        val key = part.lowercase()
        lock.withLock { images.get(key) }?.let { return it }
        return decode()?.also { image -> lock.withLock { images.put(key, image) } }
    }

    private class ParsedPage(val root: KiteXmlNode.Element, val markupBytes: Long)

    private val pages = ByteBudgetCache<ParsedPage>(parsedPageBytes) { it.markupBytes }
    private val images = ByteBudgetCache<XpsBrushes.Image>(imageBytes) { image ->
        (image.data.pixelBytes?.size ?: image.data.encodedBytes.size).toLong() + (image.data.softMaskAlpha?.size ?: 0)
    }

    fun xml(part: String): KiteXmlNode.Element? = read(part)?.let { bytes ->
        runCatching { KiteXml.parse(TextEncoding.decode(bytes)).elements().firstOrNull() }.getOrNull()
    }

    fun sequencePart(): String? {
        val relationships = xml("_rels/.rels")
        for (rel in relationships?.elements().orEmpty()) {
            if (rel.tag != "relationship" || rel.attrs["targetmode"].equals("External", true)) continue
            if (rel.attrs["type"] !in START_RELATIONSHIPS) continue
            val part = resolvePart("", rel.attrs["target"] ?: continue) ?: continue
            if (xml(part)?.tag == "fixeddocumentsequence") return part
        }
        // Broken producers occasionally omit the start relationship. Salvage
        // only a real sequence root, not an arbitrary ZIP with a renamed entry.
        val types = xml("[Content_Types].xml")
        for (entry in types?.elements().orEmpty()) {
            if (entry.tag == "override" && entry.attrs["contenttype"] in SEQUENCE_TYPES) {
                val part = resolvePart("", entry.attrs["partname"] ?: continue) ?: continue
                if (xml(part)?.tag == "fixeddocumentsequence") return part
            }
        }
        for (name in zip.names) {
            if (!name.endsWith(".fdseq", true)) continue
            if (xml(name)?.tag == "fixeddocumentsequence") return resolvePart("", name)
        }
        return null
    }

    fun font(base: String, uri: String): XpsFont? {
        val part = resolvePart(base, uri) ?: return null
        val face = if ('#' in uri) uri.substringAfterLast('#').toIntOrNull() ?: return null else 0
        if (face < 0) return null
        val key = "${part.lowercase()}#$face"
        lock.withLock { if (fonts.containsKey(key)) return fonts[key] }
        val font = runCatching {
            var data = read(part) ?: return@runCatching null
            val type = contentType(part)
            if (part.endsWith(".odttf", true) || part.endsWith(".odttc", true) ||
                type == "application/vnd.ms-package.obfuscated-opentype"
            ) data = deobfuscateFont(data, part) ?: return@runCatching null
            XpsFont.parse(data, face)
        }.getOrNull()
        if (font == null) kiteWarn { "xps: unreadable font '$part'; using a substitute" }
        return lock.withLock {
            if (fonts.containsKey(key)) fonts[key] else font.also { fonts[key] = it }
        }
    }

    private val types by lazy { xml("[Content_Types].xml")?.elements().orEmpty() }

    private fun contentType(part: String): String? =
        types.firstOrNull { it.tag == "override" &&
            resolvePart("", it.attrs["partname"].orEmpty())?.equals(part, true) == true
        }?.attrs?.get("contenttype") ?: types.firstOrNull {
            it.tag == "default" && it.attrs["extension"].equals(part.substringAfterLast('.'), true)
        }?.attrs?.get("contenttype")

    private companion object {
        val START_RELATIONSHIPS = setOf(
            "http://schemas.microsoft.com/xps/2005/06/fixedrepresentation",
            "http://schemas.openxps.org/oxps/v1.0/fixedrepresentation",
        )
        val SEQUENCE_TYPES = setOf(
            "application/vnd.ms-package.xps-fixeddocumentsequence+xml",
            "application/oxps-fixeddocumentsequence+xml",
        )

        /** The bytes read for a root start tag, which comes after at most a declaration and comments. */
        const val ROOT_TAG_BYTES = 16 * 1024

        /** The markup of the parsed pages a package keeps. */
        const val PARSED_PAGE_BYTES = 4L * 1024 * 1024

        /** The decoded image brushes a package keeps, the same budget as the decoded scans of a comic. */
        const val IMAGE_BYTES = 64L * 1024 * 1024
    }
}

/**
 * Values by key, the least recently used dropped first once their sizes pass [maxBytes]. A value
 * larger than the whole budget is not kept, so it cannot push every other one out. Callers hold a lock.
 */
private class ByteBudgetCache<V : Any>(private val maxBytes: Long, private val sizeOf: (V) -> Long) {
    /** Oldest use first. */
    private val entries = LinkedHashMap<String, V>()
    private var bytes = 0L

    // Taken out and put back, so the entry moves to the newest end.
    fun get(key: String): V? = entries.remove(key)?.also { entries[key] = it }

    fun put(key: String, value: V) {
        val size = sizeOf(value)
        if (size > maxBytes) return
        entries.remove(key)?.let { bytes -= sizeOf(it) }
        entries[key] = value
        bytes += size
        val oldest = entries.entries.iterator()
        while (bytes > maxBytes && oldest.hasNext()) {
            val entry = oldest.next()
            if (entry.key == key) continue
            bytes -= sizeOf(entry.value)
            oldest.remove()
        }
    }
}

internal fun KiteXmlNode.Element.elements(): List<KiteXmlNode.Element> =
    children.filterIsInstance<KiteXmlNode.Element>()

/** Package-local URI resolution only, ECMA-388 §9.1 and RFC 3986 §5.2. */
internal fun resolvePart(base: String, reference: String): String? {
    val raw = reference.substringBefore('#').trim()
    if (raw.isEmpty() || raw.startsWith("//") || '\\' in raw || '?' in raw ||
        ':' in raw.substringBefore('/')
    ) return null
    // The base is already a canonical, decoded part name. Decode only the
    // new URI; decoding the base again would turn a literal %25 into another
    // escape and make every relative reference below that directory fail.
    val segments = if (raw.startsWith('/')) ArrayList<String>() else {
        ArrayList(base.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() })
    }
    for (encoded in raw.split('/')) {
        val segment = decodeSegment(encoded) ?: return null
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
            else -> segments.add(segment)
        }
    }
    return segments.joinToString("/").takeIf { it.isNotEmpty() }
}

private fun decodeSegment(raw: String): String? {
    if ('%' !in raw) return raw.takeUnless { it.any { c -> c.isISOControl() } }
    val bytes = ArrayList<Byte>()
    var at = 0
    while (at < raw.length) {
        if (raw[at] == '%') {
            if (at + 2 >= raw.length) return null
            val n = raw.substring(at + 1, at + 3).toIntOrNull(16) ?: return null
            if (n == 0 || n == 47 || n == 92) return null
            bytes.add(n.toByte()); at += 3
        } else {
            val end = raw.indexOf('%', at).let { if (it < 0) raw.length else it }
            bytes.addAll(raw.substring(at, end).encodeToByteArray().toList()); at = end
        }
    }
    return bytes.toByteArray().decodeToString().takeUnless { it.any { c -> c.isISOControl() } }
}

/** XOR the first 32 bytes with the reversed GUID twice, ECMA-388 §9.1.7.3. */
internal fun deobfuscateFont(bytes: ByteArray, part: String): ByteArray? {
    if (bytes.size < 32) return null
    val name = part.substringAfterLast('/').substringBeforeLast('.')
    val guid = name.replace("-", "")
    if (guid.length != 32) return null
    val key = ByteArray(16) { i -> guid.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: return null }
    return bytes.copyOf().also { data ->
        for (i in 0 until 32) data[i] = (data[i].toInt() xor key[15 - i % 16].toInt()).toByte()
    }
}
