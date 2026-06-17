package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.crypto.StandardSecurityHandler

/**
 * Document usage permissions — the typed view of the Standard Security
 * Handler's `/P` bit-flags (ISO 32000-1 §7.6.3.2 Table 22).
 *
 * Bit numbering in the spec is 1-based; the mask names below use the same
 * 1-based bit number (so [BIT_PRINT] = `1 shl (3 - 1)`).
 *
 * For unencrypted documents — or encrypted documents we have authenticated —
 * the spec says "all operations are permitted". We model that as
 * [allowAll].
 *
 * **Important**: PDF permissions are advisory and rely on cooperating
 * readers. KitePDF surfaces them so applications built on top can honour
 * the document author's intent; the library itself does not enforce them.
 */
data class PdfPermissions(
    val canPrint: Boolean,
    val canModifyContents: Boolean,
    val canCopyContents: Boolean,
    val canModifyAnnotations: Boolean,
    val canFillForms: Boolean,
    val canExtractForAccessibility: Boolean,
    val canAssembleDocument: Boolean,
    val canPrintHighResolution: Boolean,
    /** Raw `/P` bit-flags as found in the encryption dict. */
    val rawFlags: Int,
) {

    companion object {
        // 1-based spec bit positions translated to 0-based shifts.
        const val BIT_PRINT = 1 shl (3 - 1)            // 0x004
        const val BIT_MODIFY = 1 shl (4 - 1)           // 0x008
        const val BIT_COPY = 1 shl (5 - 1)             // 0x010
        const val BIT_ANNOTATE = 1 shl (6 - 1)         // 0x020
        const val BIT_FILL_FORMS = 1 shl (9 - 1)       // 0x100
        const val BIT_ACCESSIBILITY = 1 shl (10 - 1)   // 0x200
        const val BIT_ASSEMBLE = 1 shl (11 - 1)        // 0x400
        const val BIT_PRINT_HIGHRES = 1 shl (12 - 1)   // 0x800

        /** Everything allowed — the default for unencrypted docs. */
        val allowAll: PdfPermissions = PdfPermissions(
            canPrint = true,
            canModifyContents = true,
            canCopyContents = true,
            canModifyAnnotations = true,
            canFillForms = true,
            canExtractForAccessibility = true,
            canAssembleDocument = true,
            canPrintHighResolution = true,
            rawFlags = -1,
        )

        internal fun from(security: StandardSecurityHandler?): PdfPermissions {
            if (security == null) return allowAll
            val p = security.p
            val r = security.r
            // Revision 2: only bits 3 (print), 4 (modify), 5 (copy), 6 (annot) are defined.
            // Revisions 3+ add bits 9-12 and split print into low/high res.
            val canPrint = p and BIT_PRINT != 0
            val canHighResPrint = if (r >= 3) p and BIT_PRINT_HIGHRES != 0 else canPrint
            val canFill = if (r >= 3) {
                p and BIT_FILL_FORMS != 0
            } else {
                // R2: form-fill rolls up under the modify-annotations bit.
                p and BIT_ANNOTATE != 0
            }
            val canAccess = if (r >= 3) p and BIT_ACCESSIBILITY != 0 else p and BIT_COPY != 0
            val canAssemble = if (r >= 3) {
                p and BIT_ASSEMBLE != 0
            } else {
                // R2: assemble rolls up under modify-contents.
                p and BIT_MODIFY != 0
            }
            return PdfPermissions(
                canPrint = canPrint,
                canModifyContents = p and BIT_MODIFY != 0,
                canCopyContents = p and BIT_COPY != 0,
                canModifyAnnotations = p and BIT_ANNOTATE != 0,
                canFillForms = canFill,
                canExtractForAccessibility = canAccess,
                canAssembleDocument = canAssemble,
                canPrintHighResolution = canHighResPrint,
                rawFlags = p,
            )
        }
    }
}
