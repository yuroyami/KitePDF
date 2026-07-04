package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfName
import io.github.yuroyami.kitepdf.parser.PdfObject
import io.github.yuroyami.kitepdf.parser.PdfStream

/**
 * Packs non-stream indirect objects into a single object stream (`/Type /ObjStm`,
 * ISO 32000-1 §7.5.7) — the compression that lets modern PDFs store dozens of
 * small dictionaries in one Flate-compressed stream. The inverse of the reader's
 * `decodeObjectStream`.
 *
 * Stream objects, and any object with a non-zero generation, must NOT be packed;
 * the caller is responsible for that filtering.
 */
internal object ObjectStreamWriter {

    /**
     * Build the ObjStm for [members] (object number → already-renumbered value).
     * Returns the Flate-compressed stream, ready to write as a normal object.
     */
    fun build(members: List<Pair<Long, PdfObject>>): PdfStream {
        val bodies = members.map { (_, obj) ->
            val b = ByteArrayBuilder()
            PdfObjectWriter.writeObject(obj, b)
            b.toByteArray()
        }
        // Offsets are relative to /First (the start of the first object body).
        val offsets = IntArray(members.size)
        var cursor = 0
        for (i in members.indices) {
            offsets[i] = cursor
            cursor += bodies[i].size + 1   // +1 for the newline separator
        }

        val header = StringBuilder()
        for (i in members.indices) header.append("${members[i].first} ${offsets[i]} ")
        val headerBytes = header.toString().encodeToByteArray()
        val first = headerBytes.size

        val body = ByteArrayBuilder(headerBytes.size + cursor)
        body.append(headerBytes)
        for (b in bodies) { body.append(b); body.append('\n'.code.toByte()) }

        return PdfStreams.flate(
            body.toByteArray(),
            extra = linkedMapOf<String, PdfObject>(
                "Type" to PdfName("ObjStm"),
                "N" to PdfInt(members.size.toLong()),
                "First" to PdfInt(first.toLong()),
            ),
        )
    }
}
