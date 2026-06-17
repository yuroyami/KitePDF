package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfName
import io.github.yuroyami.kitepdf.parser.PdfObject
import io.github.yuroyami.kitepdf.parser.PdfReal
import io.github.yuroyami.kitepdf.parser.PdfReference
import io.github.yuroyami.kitepdf.parser.PdfString

/**
 * Creates a brand-new PDF from scratch — the full (non-incremental) write path.
 *
 * ```
 * val bytes = PdfBuilder()
 *     .setInfo(title = "Demo", author = "KitePDF")
 *     .page { text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "Hello, world!") }
 *     .build()
 * ```
 *
 * Builds the object graph (catalog → page tree → pages, each with an inlined
 * `/Resources` and a content stream), then serializes a complete file: header,
 * objects, a classic xref table, and a trailer. Page content is drawn with
 * [ContentStreamBuilder]; text uses the standard-14 fonts (no embedding).
 *
 * Content streams are FlateDecode-compressed by default (see [build]).
 */
class PdfBuilder {

    private class PageSpec(val width: Double, val height: Double, val content: ByteArray)

    private val pages = ArrayList<PageSpec>()
    private val fontResourceNames = LinkedHashMap<StandardFont, String>()
    private val imageResourceNames = LinkedHashMap<PdfImage, String>()
    private val infoEntries = LinkedHashMap<String, PdfObject>()

    /** Map a font to its `/Resources` name, assigning F1, F2, … on first use. */
    private fun resolveFont(font: StandardFont): String =
        fontResourceNames.getOrPut(font) { "F${fontResourceNames.size + 1}" }

    /** Map an image to its `/Resources` name, assigning Im1, Im2, … on first use. */
    private fun resolveImage(image: PdfImage): String =
        imageResourceNames.getOrPut(image) { "Im${imageResourceNames.size + 1}" }

    /**
     * Append a page sized [width]×[height] points (default US Letter), drawn by
     * [block]. The block runs immediately, so fonts it uses are registered
     * before [build].
     */
    fun page(
        width: Double = 612.0,
        height: Double = 792.0,
        block: ContentStreamBuilder.() -> Unit,
    ): PdfBuilder {
        val csb = newPageContent()
        csb.block()
        return addPage(width, height, csb)
    }

    /**
     * A fresh [ContentStreamBuilder] wired to this builder's font/image
     * resources. Pair with [addPage] when page drawing must be driven
     * imperatively — e.g. from a `suspend` layout routine that [page]'s
     * synchronous block can't host. Fonts/images used here are registered when
     * [addPage] commits the builder.
     */
    fun newPageContent(): ContentStreamBuilder = ContentStreamBuilder(::resolveFont, ::resolveImage)

    /**
     * Commit a page sized [width]×[height] points from [content] (obtained via
     * [newPageContent]). Pages appear in call order.
     */
    fun addPage(width: Double = 612.0, height: Double = 792.0, content: ContentStreamBuilder): PdfBuilder {
        pages.add(PageSpec(width, height, content.toByteArray()))
        return this
    }

    /** Set document metadata (`/Info`). Only non-null fields are written. */
    fun setInfo(
        title: String? = null,
        author: String? = null,
        subject: String? = null,
        keywords: String? = null,
        creator: String? = null,
        producer: String? = null,
    ): PdfBuilder {
        fun put(key: String, value: String?) {
            if (value != null) infoEntries[key] = PdfString(PdfText.encodeTextString(value))
        }
        put("Title", title)
        put("Author", author)
        put("Subject", subject)
        put("Keywords", keywords)
        put("Creator", creator)
        put("Producer", producer)
        return this
    }

    /**
     * Serialize the document. When [compress] is true (default), each page's
     * content stream is FlateDecode-compressed.
     */
    fun build(compress: Boolean = true): ByteArray {
        require(pages.isNotEmpty()) { "A PDF must have at least one page." }

        val objects = ArrayList<Pair<Long, PdfObject>>()
        var next = 1L
        fun alloc(): Long = next++

        val catalogNum = alloc()
        val pagesNum = alloc()

        // One font object per used standard font.
        val fontNum = LinkedHashMap<StandardFont, Long>()
        for (font in fontResourceNames.keys) {
            val n = alloc()
            fontNum[font] = n
            objects.add(
                n to PdfDictionary(
                    linkedMapOf(
                        "Type" to PdfName("Font"),
                        "Subtype" to PdfName("Type1"),
                        "BaseFont" to PdfName(font.baseFont),
                    ),
                ),
            )
        }

        // One image XObject per used image (plus a /SMask sub-image when the
        // image carries an alpha channel). Allocated before the resources dict
        // so it can reference them.
        val imageNum = LinkedHashMap<PdfImage, Long>()
        for (image in imageResourceNames.keys) {
            var smaskRef: PdfReference? = null
            if (image.alpha != null) {
                val smaskN = alloc()
                objects.add(
                    smaskN to PdfStreams.flate(
                        image.alpha,
                        linkedMapOf(
                            "Type" to PdfName("XObject"),
                            "Subtype" to PdfName("Image"),
                            "Width" to PdfInt(image.width.toLong()),
                            "Height" to PdfInt(image.height.toLong()),
                            "ColorSpace" to PdfName("DeviceGray"),
                            "BitsPerComponent" to PdfInt(8L),
                        ),
                    ),
                )
                smaskRef = PdfReference(smaskN, 0)
            }
            val extra = LinkedHashMap<String, PdfObject>()
            extra["Type"] = PdfName("XObject")
            extra["Subtype"] = PdfName("Image")
            extra["Width"] = PdfInt(image.width.toLong())
            extra["Height"] = PdfInt(image.height.toLong())
            extra["ColorSpace"] = PdfName(image.colorSpace)
            extra["BitsPerComponent"] = PdfInt(image.bitsPerComponent.toLong())
            if (smaskRef != null) extra["SMask"] = smaskRef

            val n = alloc()
            val stream = if (image.filter == null) {
                // Raw samples → FlateDecode.
                PdfStreams.flate(image.samples, extra)
            } else {
                // Encoded passthrough (e.g. DCTDecode JPEG): store verbatim.
                extra["Filter"] = PdfName(image.filter)
                PdfStreams.raw(image.samples, extra)
            }
            objects.add(n to stream)
            imageNum[image] = n
        }

        // A single resources dict, inlined into every page (font/image entries
        // are indirect references to the shared objects above).
        val fontDict = LinkedHashMap<String, PdfObject>()
        for ((font, resName) in fontResourceNames) {
            fontDict[resName] = PdfReference(fontNum.getValue(font), 0)
        }
        val resourcesMap = LinkedHashMap<String, PdfObject>()
        resourcesMap["Font"] = PdfDictionary(fontDict)
        if (imageResourceNames.isNotEmpty()) {
            val xobjectDict = LinkedHashMap<String, PdfObject>()
            for ((image, resName) in imageResourceNames) {
                xobjectDict[resName] = PdfReference(imageNum.getValue(image), 0)
            }
            resourcesMap["XObject"] = PdfDictionary(xobjectDict)
        }
        val resourcesDict = PdfDictionary(resourcesMap)

        val pageNums = ArrayList<Long>(pages.size)
        for (page in pages) {
            val stream = if (compress) PdfStreams.flate(page.content) else PdfStreams.raw(page.content)
            val contentNum = alloc()
            objects.add(contentNum to stream)
            val pageNum = alloc()
            pageNums.add(pageNum)
            objects.add(
                pageNum to PdfDictionary(
                    linkedMapOf(
                        "Type" to PdfName("Page"),
                        "Parent" to PdfReference(pagesNum, 0),
                        "MediaBox" to PdfArray(
                            listOf(PdfReal(0.0), PdfReal(0.0), PdfReal(page.width), PdfReal(page.height)),
                        ),
                        "Resources" to resourcesDict,
                        "Contents" to PdfReference(contentNum, 0),
                    ),
                ),
            )
        }

        objects.add(
            pagesNum to PdfDictionary(
                linkedMapOf(
                    "Type" to PdfName("Pages"),
                    "Kids" to PdfArray(pageNums.map { PdfReference(it, 0) }),
                    "Count" to PdfInt(pageNums.size.toLong()),
                ),
            ),
        )
        objects.add(
            catalogNum to PdfDictionary(
                linkedMapOf(
                    "Type" to PdfName("Catalog"),
                    "Pages" to PdfReference(pagesNum, 0),
                ),
            ),
        )

        var infoNum: Long? = null
        if (infoEntries.isNotEmpty()) {
            infoNum = alloc()
            objects.add(infoNum to PdfDictionary(LinkedHashMap(infoEntries)))
        }

        return serialize(objects, catalogNum, infoNum, maxObjNum = next - 1)
    }

    private fun serialize(
        objects: List<Pair<Long, PdfObject>>,
        catalogNum: Long,
        infoNum: Long?,
        maxObjNum: Long,
    ): ByteArray {
        val out = ByteArrayBuilder(1024)
        out.append("%PDF-1.7\n".encodeToByteArray())
        // Binary marker comment: four bytes >= 0x80 so tools treat the file as
        // binary and don't mangle it with text-mode newline translation.
        out.append('%'.code.toByte())
        out.append(byteArrayOf(0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte()))
        out.append('\n'.code.toByte())

        val xrefEntries = ArrayList<ClassicXrefWriter.Entry>(objects.size)
        for ((num, value) in objects.sortedBy { it.first }) {
            xrefEntries.add(ClassicXrefWriter.Entry(num, out.size(), 0))
            out.append("$num 0 obj\n".encodeToByteArray())
            PdfObjectWriter.writeObject(value, out)
            out.append("\nendobj\n".encodeToByteArray())
        }

        val xrefOffset = out.size()
        ClassicXrefWriter.write(out, xrefEntries)

        val trailer = LinkedHashMap<String, PdfObject>()
        trailer["Size"] = PdfInt(maxObjNum + 1)
        trailer["Root"] = PdfReference(catalogNum, 0)
        if (infoNum != null) trailer["Info"] = PdfReference(infoNum, 0)
        out.append("trailer\n".encodeToByteArray())
        PdfObjectWriter.writeObject(PdfDictionary(trailer), out)
        out.append("\nstartxref\n$xrefOffset\n%%EOF\n".encodeToByteArray())
        return out.toByteArray()
    }
}
