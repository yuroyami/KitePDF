package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.parser.IndirectResolver
import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfReference
import io.github.yuroyami.kitepdf.parser.PdfString

/**
 * One article thread from the document — ISO 32000-1 §12.4.3.
 *
 * An "article" is a reading order superimposed on the layout: a sequence of
 * rectangular [beads], each anchored to a page region. Readers use threads
 * to jump from "column 1 → column 2 → next page column 1 …" while ignoring
 * the visual flow.
 *
 * Metadata fields (title/author/etc.) come from the thread's /I info dict,
 * which is optional and may be missing or partially populated.
 */
data class PdfArticleThread(
    val title: String? = null,
    val author: String? = null,
    val subject: String? = null,
    val keywords: String? = null,
    /** Beads in reading order (the /F chain followed via /N until cycle). */
    val beads: List<ArticleBead> = emptyList(),
) {

    /**
     * One bead — a rectangular region on a page that belongs to the thread.
     * [pageIndex] is `null` only when the bead's /P doesn't resolve to a
     * known page (rare; usually a malformed PDF).
     */
    data class ArticleBead(
        val pageIndex: Int?,
        val rect: Rectangle,
    )

    companion object {

        internal fun parseAll(
            catalog: PdfDictionary,
            refs: IndirectResolver,
            pageRefToIndex: Map<Long, Int>,
        ): List<PdfArticleThread> {
            val threadsArr = catalog.getArray("Threads", refs) ?: return emptyList()
            val out = mutableListOf<PdfArticleThread>()
            for (entry in threadsArr) {
                val threadDict = when (entry) {
                    is PdfReference -> refs.resolve(entry) as? PdfDictionary
                    is PdfDictionary -> entry
                    else -> null
                } ?: continue
                out += parseOne(threadDict, refs, pageRefToIndex)
            }
            return out
        }

        private fun parseOne(
            threadDict: PdfDictionary,
            refs: IndirectResolver,
            pageRefToIndex: Map<Long, Int>,
        ): PdfArticleThread {
            val info = threadDict.getDict("I", refs)
            val title = (info?.get("Title") as? PdfString)?.asText()
            val author = (info?.get("Author") as? PdfString)?.asText()
            val subject = (info?.get("Subject") as? PdfString)?.asText()
            val keywords = (info?.get("Keywords") as? PdfString)?.asText()

            val beads = walkBeads(threadDict, refs, pageRefToIndex)
            return PdfArticleThread(title, author, subject, keywords, beads)
        }

        private fun walkBeads(
            threadDict: PdfDictionary,
            refs: IndirectResolver,
            pageRefToIndex: Map<Long, Int>,
        ): List<ArticleBead> {
            val firstRef = threadDict["F"] as? PdfReference ?: return emptyList()
            val seen = HashSet<Long>()
            val out = mutableListOf<ArticleBead>()
            var cur: PdfReference? = firstRef
            var hops = 0
            while (cur != null && hops < MAX_BEADS) {
                if (!seen.add(cur.objectNumber)) break  // cycle reached: back to /F
                val beadDict = refs.resolve(cur) as? PdfDictionary ?: break
                val pageRef = beadDict["P"] as? PdfReference
                val pageIndex = pageRef?.let { pageRefToIndex[it.objectNumber] }
                val rect = (beadDict.getArray("R"))?.let(Rectangle::fromPdfArray)
                if (rect != null) out += ArticleBead(pageIndex, rect)
                cur = beadDict["N"] as? PdfReference
                hops++
            }
            return out
        }

        private const val MAX_BEADS = 8192
    }
}
