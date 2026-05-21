package com.yuroyami.kitepdf.writer

import com.yuroyami.kitepdf.content.Operation
import com.yuroyami.kitepdf.core.ByteArrayBuilder

/**
 * Serializes a list of [Operation]s back to content-stream bytes — the inverse
 * of [com.yuroyami.kitepdf.content.ContentStreamParser]. Each operation writes
 * its operands (space-separated, via [PdfObjectWriter]) then the operator
 * keyword; inline images are written back verbatim from their captured source.
 */
object ContentStreamWriter {

    fun serialize(operations: List<Operation>): ByteArray {
        val out = ByteArrayBuilder(256)
        for (op in operations) {
            val inline = op.inlineImage
            if (inline != null) {
                out.append(inline)
                out.append('\n'.code.toByte())
                continue
            }
            for (operand in op.operands) {
                PdfObjectWriter.writeObject(operand, out)
                out.append(' '.code.toByte())
            }
            out.append(op.operator.encodeToByteArray())
            out.append('\n'.code.toByte())
        }
        return out.toByteArray()
    }
}
