package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfNull
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.parser.PdfString
import io.github.yuroyami.kitepdf.core.ByteArrayBuilder

/**
 * Writes a linearized file, which a viewer can show the first page of before the rest has arrived
 * (ISO 32000-1, Annex F; #907). The layout is the one qpdf writes and checks:
 *
 * 1. the header;
 * 2. the linearization parameter dictionary;
 * 3. the first-page cross-reference table and trailer;
 * 4. the catalog and the objects a viewer needs to open the document;
 * 5. the primary hint stream, with the page offset, the shared object and the outline tables;
 * 6. the first page and every object it uses;
 * 7. each other page, its page object followed by the objects only it uses;
 * 8. the objects that more than one other page uses;
 * 9. everything else: the page tree, the outlines and the rest;
 * 11. the main cross-reference table and trailer.
 *
 * Objects are sorted into the parts as qpdf's `calculateLinearizationData` sorts them, by who uses
 * them, since `qpdf --check-linearization` sorts the file again and compares. The second half,
 * parts 7 to 9, is numbered from 1, and the first half after it, so each page's objects, the first
 * page section and the shared objects have consecutive numbers, as the hint tables count them.
 *
 * The hint tables give offsets as if the hint stream were not in the file (F.4), so the file is
 * laid out once without it, the tables are made from that layout, and the stream goes in after
 * part 4. Every number in parts 2 and 3 that depends on the layout is written with ten digits,
 * so the insertion moves nothing but what follows the stream, by its length.
 */
internal class LinearizedWriter(
    /** Each object to write, by its number before the rewrite. */
    source: Map<Long, PdfObject>,
    private val catalog: Long,
    private val info: Long?,
    /** Turns an object into its encrypted form under its new number, or leaves it as it is. */
    private val encrypt: (Long, PdfObject) -> PdfObject,
    /** The encryption dictionary, written in clear, or null. */
    private val encryptionDictionary: PdfDictionary?,
    /** The trailer's `/ID` for the bytes written after the first page trailer. */
    private val id: (ByteArray) -> PdfArray,
) {

    private val objects = HashMap(source)

    /** The page objects in page order, each given the attributes it inherited (F.3, `/Linearized` needs them on the page). */
    private val pages: List<Long> = pushInheritedAttributes()

    private sealed class User {
        data class Page(val index: Int) : User()
        data class Thumb(val index: Int) : User()
        data class TrailerKey(val key: String) : User()
        data class RootKey(val key: String) : User()
        data object Root : User()
    }

    private val usersOf = HashMap<Long, MutableSet<User>>()
    private val objectsOf = HashMap<User, MutableSet<Long>>()

    private fun isPage(obj: PdfObject?): Boolean = (obj as? PdfDictionary)?.getName("Type") == "Page"

    /**
     * Records what [user] reaches from [start], as qpdf's `updateObjectMaps` does: through arrays,
     * dictionaries and stream dictionaries, never into another page, never up a page's `/Parent`,
     * and with a page's `/Thumb` counted for the thumbnail user.
     */
    private fun trace(user: User, start: PdfObject, top: Boolean) {
        val visited = HashSet<Long>()
        val pending = ArrayDeque<Triple<User, PdfObject, Boolean>>()
        pending.addLast(Triple(user, start, top))
        while (pending.isNotEmpty()) {
            val (u, item, isTop) = pending.removeLast()
            var obj = item
            if (item is PdfReference) {
                val target = objects[item.objectNumber] ?: continue
                if (isPage(target) && !isTop) continue
                if (!visited.add(item.objectNumber)) continue
                usersOf.getOrPut(item.objectNumber) { HashSet() }.add(u)
                objectsOf.getOrPut(u) { HashSet() }.add(item.objectNumber)
                obj = target
            } else if (isPage(item) && !isTop) {
                continue
            }
            val pageNode = isPage(obj)
            when (obj) {
                is PdfArray -> for (child in obj.items) pending.addLast(Triple(u, child, false))
                is PdfDictionary, is PdfStream -> {
                    val dict = if (obj is PdfStream) obj.dict else obj as PdfDictionary
                    for ((key, value) in dict.map) {
                        // The writer gives each stream its /Length directly, so an indirect one
                        // is not written, as qpdf's `skip_stream_parameters` leaves it out.
                        if (value is PdfNull || (obj is PdfStream && key == "Length")) continue
                        when {
                            pageNode && key == "Thumb" && u is User.Page -> pending.addLast(Triple(User.Thumb(u.index), value, false))
                            pageNode && key == "Parent" -> {}
                            else -> pending.addLast(Triple(u, value, false))
                        }
                    }
                }
                else -> {}
            }
        }
    }

    fun write(): ByteArray {
        val root = objects.getValue(catalog) as PdfDictionary
        for ((i, page) in pages.withIndex()) trace(User.Page(i), PdfReference(page, 0), top = true)
        info?.let { trace(User.TrailerKey("Info"), PdfReference(it, 0), top = false) }
        for ((key, value) in root.map) if (value !is PdfNull) trace(User.RootKey(key), value, top = false)
        usersOf.getOrPut(catalog) { HashSet() }.add(User.Root)
        objectsOf.getOrPut(User.Root) { HashSet() }.add(catalog)

        val openDocumentKeys = setOf("ViewerPreferences", "PageMode", "Threads", "OpenAction", "AcroForm")
        val outlinesFirst = root.getName("PageMode") == "UseOutlines" && root.map["Outlines"] != null
        val openDocument = HashSet<Long>()
        val firstPrivate = HashSet<Long>()
        val firstShared = HashSet<Long>()
        val otherPrivate = HashSet<Long>()
        val otherShared = HashSet<Long>()
        val thumbPrivate = HashSet<Long>()
        val thumbShared = HashSet<Long>()
        val other = HashSet<Long>()
        val outlines = HashSet<Long>()
        for ((obj, users) in usersOf) {
            var inOpen = false; var inFirst = false; var otherPages = 0; var thumbs = 0; var others = 0
            var inOutlines = false; var isRoot = false
            for (u in users) when (u) {
                is User.TrailerKey -> if (u.key == "Encrypt") inOpen = true else others++
                is User.Thumb -> thumbs++
                is User.RootKey -> when {
                    u.key in openDocumentKeys -> inOpen = true
                    u.key == "Outlines" -> inOutlines = true
                    else -> others++
                }
                is User.Page -> if (u.index == 0) inFirst = true else otherPages++
                User.Root -> isRoot = true
            }
            when {
                isRoot -> {}
                inOutlines -> outlines += obj
                inOpen -> openDocument += obj
                inFirst && others == 0 && otherPages == 0 && thumbs == 0 -> firstPrivate += obj
                inFirst -> firstShared += obj
                otherPages == 1 && others == 0 && thumbs == 0 -> otherPrivate += obj
                otherPages > 1 -> otherShared += obj
                thumbs == 1 && others == 0 -> thumbPrivate += obj
                thumbs > 1 -> thumbShared += obj
                else -> other += obj
            }
        }

        val part4 = listOf(catalog) + openDocument.sorted()
        val part6 = ArrayList<Long>()
        firstPrivate.remove(pages[0])
        part6 += pages[0]
        part6 += firstPrivate.sorted()
        part6 += firstShared.sorted()
        val outlineRoot = (root.map["Outlines"] as? PdfReference)?.objectNumber
        fun outlinesTo(part: MutableList<Long>): Int {
            if (outlineRoot == null || outlineRoot !in outlines) return 0
            val start = part.size
            part += outlineRoot
            outlines.remove(outlineRoot)
            part += outlines.sorted()
            outlines.clear()
            return part.size - start
        }
        val outlineCountFirst = if (outlinesFirst) outlinesTo(part6) else 0
        val pageObjectCount = IntArray(pages.size)
        pageObjectCount[0] = part6.size
        val part7 = ArrayList<Long>()
        for (i in 1 until pages.size) {
            otherPrivate.remove(pages[i])
            part7 += pages[i]
            var n = 1
            for (obj in objectsOf[User.Page(i)].orEmpty().sorted()) if (otherPrivate.remove(obj)) { part7 += obj; n++ }
            pageObjectCount[i] = n
        }
        val part8 = ArrayList(otherShared.sorted())
        val part9 = ArrayList<Long>()
        for (obj in objectsOf[User.RootKey("Pages")].orEmpty().sorted()) if (other.remove(obj)) part9 += obj
        for ((i, page) in pages.withIndex()) {
            val thumb = ((objects[page] as? PdfDictionary)?.map?.get("Thumb") as? PdfReference)?.objectNumber
            if (thumb != null && thumbPrivate.remove(thumb)) part9 += thumb
            for (obj in objectsOf[User.Thumb(i)].orEmpty().sorted()) if (thumbPrivate.remove(obj)) part9 += obj
        }
        part9 += thumbShared.sorted()
        val outlineStart9 = part9.size
        val outlineCount9 = if (!outlinesFirst) outlinesTo(part9) else 0
        part9 += other.sorted()
        // An object no user reaches, such as a stream's indirect /Length, is not written.

        // Numbers: the second half from 1, then the linearization dictionary, part 4, the
        // encryption dictionary, the hint stream and part 6.
        val renumber = HashMap<Long, Long>()
        var next = 1L
        for (obj in part7 + part8 + part9) renumber[obj] = next++
        val secondHalfSize = next
        val lindictNum = next++
        for (obj in part4) renumber[obj] = next++
        val encryptNum = if (encryptionDictionary != null) next++ else 0L
        val hintNum = next++
        for (obj in part6) renumber[obj] = next++
        val totalSize = next

        fun body(old: Long): ByteArray {
            val num = renumber.getValue(old)
            val out = ByteArrayBuilder(256)
            out.append("$num 0 obj\n".encodeToByteArray())
            val remapped = remap(objects.getValue(old), renumber)
            PdfObjectWriter.writeObject(encrypt(num, remapped), out)
            out.append("\nendobj\n".encodeToByteArray())
            return out.toByteArray()
        }
        val bodies = HashMap<Long, ByteArray>()
        for (obj in renumber.keys) bodies[obj] = body(obj)
        val encryptBody = encryptionDictionary?.let {
            val out = ByteArrayBuilder(256)
            out.append("$encryptNum 0 obj\n".encodeToByteArray())
            PdfObjectWriter.writeObject(it, out)
            out.append("\nendobj\n".encodeToByteArray())
            out.toByteArray()
        }

        fun pad(v: Long) = v.toString().padStart(10, '0')
        val header = byteArrayOf(
            '%'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte(), '-'.code.toByte(),
            '1'.code.toByte(), '.'.code.toByte(), '7'.code.toByte(), '\n'.code.toByte(),
            '%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte(),
        )
        val firstPage = renumber.getValue(pages[0])
        // Every number in parts 2 and 3 that the layout decides has ten digits, so a layout made
        // with zeros there has the offsets of the real one.
        fun lindict(fileLength: Long, hintOffset: Long, hintLength: Long, end: Long, t: Long): ByteArray =
            ("$lindictNum 0 obj\n<< /Linearized 1 /L ${pad(fileLength)} /H [ ${pad(hintOffset)} ${pad(hintLength)} ] " +
                "/O $firstPage /E ${pad(end)} /N ${pages.size} /T ${pad(t)} >>\nendobj\n").encodeToByteArray()

        fun firstXref(offsets: Map<Long, Long>, prev: Long, idArray: PdfArray): ByteArray {
            val out = StringBuilder("xref\n$lindictNum ${totalSize - lindictNum}\n")
            for (num in lindictNum until totalSize) out.append("${pad(offsets[num] ?: 0)} 00000 n \n")
            out.append("trailer\n<< /Size $totalSize /Root ${renumber.getValue(catalog)} 0 R")
            info?.let { out.append(" /Info ${renumber.getValue(it)} 0 R") }
            if (encryptionDictionary != null) out.append(" /Encrypt $encryptNum 0 R")
            // Hex, so the strings have one length whatever bytes they hold.
            out.append(" /ID [")
            for (item in idArray.items) out.append(" <").append((item as PdfString).bytes.toHex()).append('>')
            out.append(" ] /Prev ${pad(prev)} >>\nstartxref\n0\n%%EOF\n")
            return out.toString().encodeToByteArray()
        }

        // The layout without the hint stream: offsets as the hint tables give them.
        val zeroId = id(ByteArray(0))
        val lindictLength = lindict(0, 0, 0, 0, 0).size
        val prefixLength = header.size + lindictLength + firstXref(emptyMap(), 0, zeroId).size
        val offsets = HashMap<Long, Long>() // by new number
        var at = prefixLength.toLong()
        for (obj in part4) { offsets[renumber.getValue(obj)] = at; at += bodies.getValue(obj).size }
        if (encryptBody != null) { offsets[encryptNum] = at; at += encryptBody.size }
        val hintOffset = at
        for (obj in part6) { offsets[renumber.getValue(obj)] = at; at += bodies.getValue(obj).size }
        val part6End = at
        for (obj in part7 + part8 + part9) { offsets[renumber.getValue(obj)] = at; at += bodies.getValue(obj).size }
        val mainXrefOffset = at
        val mainXrefHead = "xref\n0 $secondHalfSize".encodeToByteArray()
        val spaceBeforeZero = mainXrefOffset + mainXrefHead.size
        fun length(old: Long): Long = bodies.getValue(old).size.toLong()

        // The hint stream.
        val hints = BitWriter()
        val lengths = LongArray(pages.size)
        lengths[0] = part6.sumOf(::length)
        var cursor = 0
        for (p in 1 until pages.size) {
            lengths[p] = part7.subList(cursor, cursor + pageObjectCount[p]).sumOf(::length)
            cursor += pageObjectCount[p]
        }
        val sharedIndex = HashMap<Long, Int>()
        val shared = part6 + part8
        for ((i, obj) in shared.withIndex()) sharedIndex[obj] = i
        val sharedOf = Array(pages.size) { p ->
            if (p == 0) emptyList() else objectsOf[User.Page(p)].orEmpty().sorted()
                .filter { (usersOf[it]?.size ?: 0) > 1 && it in sharedIndex }.map { sharedIndex.getValue(it) }
        }
        val minObjects = pageObjectCount.min()
        val maxObjects = pageObjectCount.max()
        val minLength = lengths.min()
        val maxLength = lengths.max()
        val maxShared = sharedOf.maxOf { it.size }
        val bitsObjects = nbits((maxObjects - minObjects).toLong())
        val bitsLength = nbits(maxLength - minLength)
        val bitsShared = nbits(maxShared.toLong())
        val bitsIdentifier = nbits(shared.size.toLong())
        hints.bits(minObjects.toLong(), 32)
        hints.bits(offsets.getValue(renumber.getValue(pages[0])), 32)
        hints.bits(bitsObjects.toLong(), 16)
        hints.bits(minLength, 32)
        hints.bits(bitsLength.toLong(), 16)
        hints.bits(0, 32) // least offset of a content stream
        hints.bits(0, 16)
        hints.bits(minLength, 32) // content lengths as page lengths, as Acrobat and qpdf write them
        hints.bits(bitsLength.toLong(), 16)
        hints.bits(bitsShared.toLong(), 16)
        hints.bits(bitsIdentifier.toLong(), 16)
        hints.bits(0, 16) // numerator bits
        hints.bits(4, 16) // denominator
        for (p in pages.indices) hints.bits((pageObjectCount[p] - minObjects).toLong(), bitsObjects); hints.align()
        for (p in pages.indices) hints.bits(lengths[p] - minLength, bitsLength); hints.align()
        for (p in pages.indices) hints.bits(sharedOf[p].size.toLong(), bitsShared); hints.align()
        for (p in pages.indices) for (s in sharedOf[p]) hints.bits(s.toLong(), bitsIdentifier); hints.align()
        hints.align() // numerators of 0 bits
        for (p in pages.indices) hints.bits(0, 0); hints.align()
        for (p in pages.indices) hints.bits(lengths[p] - minLength, bitsLength); hints.align()
        val sharedTableOffset = hints.size
        val groupLengths = shared.map(::length)
        val minGroup = groupLengths.minOrNull() ?: 0L
        val maxGroup = groupLengths.maxOrNull() ?: 0L
        val bitsGroup = nbits(maxGroup - minGroup)
        hints.bits(if (part8.isEmpty()) 0 else renumber.getValue(part8[0]), 32)
        hints.bits(if (part8.isEmpty()) 0 else offsets.getValue(renumber.getValue(part8[0])), 32)
        hints.bits(part6.size.toLong(), 32)
        hints.bits(shared.size.toLong(), 32)
        hints.bits(0, 16) // one object a group
        hints.bits(minGroup, 32)
        hints.bits(bitsGroup.toLong(), 16)
        for (g in groupLengths) hints.bits(g - minGroup, bitsGroup); hints.align()
        for (g in groupLengths) hints.bits(0, 1); hints.align() // no MD5 signatures
        hints.align() // objects a group minus one, in 0 bits
        val outlineCount = if (outlinesFirst) outlineCountFirst else outlineCount9
        var outlineTableOffset = -1
        if (outlineCount > 0 && outlineRoot != null) {
            outlineTableOffset = hints.size
            val firstOutline = renumber.getValue(outlineRoot)
            val group = if (outlinesFirst) part6.subList(part6.size - outlineCount, part6.size) else part9.subList(outlineStart9, outlineStart9 + outlineCount)
            hints.bits(firstOutline, 32)
            hints.bits(offsets.getValue(firstOutline), 32)
            hints.bits(outlineCount.toLong(), 32)
            hints.bits(group.sumOf(::length), 32)
        }
        val hintData = hints.toByteArray()
        val hintDict = LinkedHashMap<String, PdfObject>()
        hintDict["S"] = PdfInt(sharedTableOffset.toLong())
        if (outlineTableOffset >= 0) hintDict["O"] = PdfInt(outlineTableOffset.toLong())
        val hintBody = ByteArrayBuilder(hintData.size + 128).apply {
            append("$hintNum 0 obj\n".encodeToByteArray())
            PdfObjectWriter.writeObject(encrypt(hintNum, PdfStream(PdfDictionary(hintDict), hintData)), this)
            append("\nendobj\n".encodeToByteArray())
        }.toByteArray()
        val hintLength = hintBody.size.toLong()

        // The real layout: everything after part 4 moves by the hint stream's length.
        val real = HashMap<Long, Long>()
        for ((num, off) in offsets) real[num] = if (off >= hintOffset) off + hintLength else off
        real[hintNum] = hintOffset
        real[lindictNum] = header.size.toLong()
        val firstXrefOffset = (header.size + lindictLength).toLong()
        val fileLength = mainXrefOffset + hintLength + mainXref(secondHalfSize, real).size + mainTrailer(secondHalfSize, firstXrefOffset).size

        val out = ByteArrayBuilder(fileLength.toInt() + 64)
        out.append(header)
        out.append(lindict(fileLength, hintOffset, hintLength, part6End + hintLength, spaceBeforeZero + hintLength))
        // The /ID is made from the parts after the first trailer, which do not hold it.
        val after = ByteArrayBuilder(fileLength.toInt())
        for (obj in part4) after.append(bodies.getValue(obj))
        encryptBody?.let { after.append(it) }
        after.append(hintBody)
        for (obj in part6 + part7 + part8 + part9) after.append(bodies.getValue(obj))
        val idArray = id(after.toByteArray())
        out.append(firstXref(real, mainXrefOffset + hintLength, idArray))
        check(out.size() == prefixLength) { "the first page cross-reference section changed length" }
        out.append(after.toByteArray())
        check(out.size().toLong() == mainXrefOffset + hintLength) { "the objects moved" }
        out.append(mainXref(secondHalfSize, real))
        out.append(mainTrailer(secondHalfSize, firstXrefOffset))
        val bytes = out.toByteArray()
        check(bytes.size.toLong() == fileLength) { "the file is ${bytes.size} bytes, not $fileLength" }
        return bytes
    }

    private fun mainXref(size: Long, offsets: Map<Long, Long>): ByteArray {
        val out = StringBuilder("xref\n0 $size\n0000000000 65535 f \n")
        for (num in 1 until size) out.append("${(offsets[num] ?: 0).toString().padStart(10, '0')} 00000 n \n")
        return out.toString().encodeToByteArray()
    }

    private fun mainTrailer(size: Long, firstXrefOffset: Long): ByteArray =
        "trailer\n<< /Size $size >>\nstartxref\n$firstXrefOffset\n%%EOF\n".encodeToByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun remap(obj: PdfObject, renumber: Map<Long, Long>): PdfObject = when (obj) {
        is PdfReference -> renumber[obj.objectNumber]?.let { PdfReference(it, 0) } ?: PdfNull
        is PdfArray -> PdfArray(obj.items.map { remap(it, renumber) })
        is PdfDictionary -> PdfDictionary(LinkedHashMap<String, PdfObject>().also { m -> obj.map.forEach { (k, v) -> m[k] = remap(v, renumber) } })
        is PdfStream -> PdfStream(remap(obj.dict, renumber) as PdfDictionary, obj.rawBytes)
        else -> obj
    }

    /** Big-endian bits, as the hint tables pack them (F.4). */
    private class BitWriter {
        private val out = ByteArrayBuilder(256)
        private var acc = 0
        private var count = 0

        val size: Int get() = out.size()

        fun bits(value: Long, n: Int) {
            for (i in n - 1 downTo 0) {
                acc = (acc shl 1) or ((value ushr i) and 1L).toInt()
                count++
                if (count == 8) { out.append(acc.toByte()); acc = 0; count = 0 }
            }
        }

        /** Starts the next item on a byte, as each item of a table does (qpdf's `flush`). */
        fun align() {
            if (count > 0) bits(0, 8 - count)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private fun nbits(v: Long): Int {
        var n = 0
        var x = v
        while (x > 0) { n++; x = x ushr 1 }
        return n
    }

    /**
     * The leaves of the page tree in order, each given the `/MediaBox`, `/CropBox`, `/Resources`
     * and `/Rotate` it inherits, which then leave the tree's inner nodes (ISO 32000-1, 7.7.3.4).
     * A linearized file's first page section holds the page and what it uses, never the tree, so
     * the page must carry them itself, as qpdf's `pushInheritedAttributesToPage` makes it.
     */
    private fun pushInheritedAttributes(): List<Long> {
        val root = objects[catalog] as? PdfDictionary ?: error("the catalog is not a dictionary")
        val treeRoot = (root.map["Pages"] as? PdfReference)?.objectNumber ?: error("the catalog has no page tree")
        val leaves = ArrayList<Long>()
        val nodes = LinkedHashSet<Long>()
        fun walk(num: Long, inherited: Map<String, PdfObject>) {
            val dict = objects[num] as? PdfDictionary ?: return
            if (dict.getName("Type") == "Page" || (dict.getName("Type") == null && dict.map["Kids"] == null)) {
                if (num in leaves) return
                val missing = INHERITABLE.filter { it !in dict.map && it in inherited }
                if (missing.isNotEmpty()) {
                    val map = LinkedHashMap(dict.map)
                    for (key in missing) map[key] = inherited.getValue(key)
                    objects[num] = PdfDictionary(map)
                }
                leaves += num
                return
            }
            if (!nodes.add(num)) return
            val here = HashMap(inherited)
            for (key in INHERITABLE) dict.map[key]?.let { here[key] = it }
            for (kid in (dict.map["Kids"] as? PdfArray)?.items.orEmpty()) {
                (kid as? PdfReference)?.let { walk(it.objectNumber, here) }
            }
        }
        walk(treeRoot, emptyMap())
        for (num in nodes) {
            val dict = objects.getValue(num) as PdfDictionary
            if (INHERITABLE.any { it in dict.map }) objects[num] = PdfDictionary(LinkedHashMap(dict.map).apply { INHERITABLE.forEach { remove(it) } })
        }
        check(leaves.isNotEmpty()) { "the document has no pages" }
        return leaves
    }

    private companion object {
        /** An inherited page attribute (ISO 32000-1, 7.7.3.4), which a linearized file gives each page itself. */
        val INHERITABLE = listOf("MediaBox", "CropBox", "Resources", "Rotate")
    }
}
