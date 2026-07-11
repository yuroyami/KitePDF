package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.content.Operation
import io.github.yuroyami.kitepdf.core.ByteArrayBuilder

/**
 * Serializes a list of [Operation]s back to content-stream bytes — the inverse
 * of [io.github.yuroyami.kitepdf.content.ContentStreamParser]. Each operation writes
 * its operands (space-separated, via [PdfObjectWriter]) then the operator
 * keyword; inline images are written back verbatim from their captured source.
 */
public object ContentStreamWriter {

    public fun serialize(operations: List<Operation>): ByteArray {
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
            out.appendAscii(op.operator)
            out.append('\n'.code.toByte())
        }
        return out.toByteArray()
    }
}
