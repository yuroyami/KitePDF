package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.crypto.StandardSecurityHandler

/**
 * Document usage permissions: the typed view of the Standard Security
 * Handler's `/P` bit-flags (ISO 32000-1 §7.6.3.2 Table 22).
 *
 * Bit numbering in the spec is 1-based; the mask names below use the same
 * 1-based bit number (so [BIT_PRINT] = `1 shl (3 - 1)`).
 *
 * An unencrypted document permits everything, as does an encrypted one
 * opened with its owner password, which gives full access (ISO 32000-1,
 * 7.6.3.1): both are [allowAll], the owner's with [rawFlags] still the
 * document's `/P`. One opened with its user password, or with none, permits
 * what `/P` permits (7.6.3.2).
 *
 * **Important**: PDF permissions are advisory and rely on cooperating
 * readers. KitePDF surfaces them so applications built on top can honour
 * the document author's intent; the library itself does not enforce them.
 */
public data class PdfPermissions(
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

    public companion object {
        // 1-based spec bit positions translated to 0-based shifts.
        public const val BIT_PRINT: Int = 1 shl (3 - 1)            // 0x004
        public const val BIT_MODIFY: Int = 1 shl (4 - 1)           // 0x008
        public const val BIT_COPY: Int = 1 shl (5 - 1)             // 0x010
        public const val BIT_ANNOTATE: Int = 1 shl (6 - 1)         // 0x020
        public const val BIT_FILL_FORMS: Int = 1 shl (9 - 1)       // 0x100
        public const val BIT_ACCESSIBILITY: Int = 1 shl (10 - 1)   // 0x200
        public const val BIT_ASSEMBLE: Int = 1 shl (11 - 1)        // 0x400
        public const val BIT_PRINT_HIGHRES: Int = 1 shl (12 - 1)   // 0x800

        /** Everything allowed: the default for unencrypted docs. */
        public val allowAll: PdfPermissions = PdfPermissions(
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

        /**
         * `/P` from typed permissions: bits 1-2 clear, bits 7-8 and 13-32 set (reserved, "shall
         * be 1"), permission bits from the flags (ISO 32000-1, Table 22).
         */
        internal fun PdfPermissions.toP(): Int {
            var v = 0xFFFFF0C0.toInt()
            if (canPrint) v = v or BIT_PRINT
            if (canModifyContents) v = v or BIT_MODIFY
            if (canCopyContents) v = v or BIT_COPY
            if (canModifyAnnotations) v = v or BIT_ANNOTATE
            if (canFillForms) v = v or BIT_FILL_FORMS
            if (canExtractForAccessibility) v = v or BIT_ACCESSIBILITY
            if (canAssembleDocument) v = v or BIT_ASSEMBLE
            if (canPrintHighResolution) v = v or BIT_PRINT_HIGHRES
            return v
        }

        internal fun from(security: StandardSecurityHandler?): PdfPermissions {
            if (security == null) return allowAll
            val p = security.p
            // The owner password opens everything, as MuPDF's pdf_has_permission grants it (#998).
            if (security.ownerAuthenticated) return allowAll.copy(rawFlags = p)
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
