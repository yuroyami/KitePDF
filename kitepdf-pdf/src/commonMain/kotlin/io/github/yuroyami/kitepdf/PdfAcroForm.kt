package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfBoolean
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfString

/**
 * Top-level interactive form metadata — catalog `/AcroForm` (ISO 32000-1
 * §12.7.2).
 *
 * KitePDF v0.0.x exposes the catalog-level view only: how many fields,
 * what the default appearance and quadding are, and the document-level
 * signing flags. The full field tree (the per-field `/Fields` entries with
 * their parent/kid relationships and widget links) is a deliberate
 * follow-up; we want to land form *filling* and form *appearance
 * generation* as a coherent feature rather than dribble fields in piece
 * by piece.
 */
public data class PdfAcroForm(
    /** Number of root fields (top of the field tree). */
    val fieldCount: Int,
    /**
     * If true, the conforming reader must regenerate field appearances on
     * open — the document's `/AP` streams are out of date. PDF readers
     * that don't regenerate (like our current renderer) should not display
     * stale appearance streams when this is true.
     */
    val needAppearances: Boolean,
    /** Document-level signing flags. Bit 1 = SignaturesExist; bit 2 = AppendOnly. */
    val signatureFlags: Int,
    /** Default field-text appearance string (the same syntax as a content stream). */
    val defaultAppearance: String?,
    /** Default quadding for variable text: 0 = left, 1 = centre, 2 = right. */
    val defaultQuadding: Int,
    /** Raw dict for callers needing fields we didn't model (DR, CO, XFA, …). */
    val raw: PdfDictionary,
) {

    /** True if /SigFlags & 1 — at least one signature field exists. */
    val hasSignatures: Boolean get() = (signatureFlags and 1) != 0

    /**
     * True if /SigFlags & 2 — modifications must be append-only (the
     * signed-bytes invariant). Save-side enforcement is on a future
     * round's docket.
     */
    val appendOnly: Boolean get() = (signatureFlags and 2) != 0

    /**
     * True if the document carries an XFA payload (PDF 1.5 dynamic forms,
     * deprecated in PDF 2.0). Most modern viewers ignore XFA; we expose
     * the flag for compatibility scanning but don't parse the payload.
     */
    val hasXfa: Boolean get() = raw["XFA"] != null

    public companion object {
        internal fun parse(catalog: PdfDictionary, refs: IndirectResolver): PdfAcroForm? {
            val dict = catalog.getDict("AcroForm", refs) ?: return null
            val fields = dict.getArray("Fields", refs)?.size ?: 0
            val needAp = (dict["NeedAppearances"] as? PdfBoolean)?.value ?: false
            val sigFlags = dict.getInt("SigFlags")?.toInt() ?: 0
            val da = (dict["DA"] as? PdfString)?.asText()
            val q = (dict["Q"] as? PdfInt)?.value?.toInt() ?: 0
            return PdfAcroForm(fields, needAp, sigFlags, da, q, dict)
        }
    }
}
