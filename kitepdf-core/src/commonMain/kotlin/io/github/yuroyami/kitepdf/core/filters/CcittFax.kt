package io.github.yuroyami.kitepdf.core.filters

import io.github.yuroyami.kiteimage.codec.CcittFax
import io.github.yuroyami.kiteimage.codec.CcittOptions
import io.github.yuroyami.kitepdf.core.parser.PdfBoolean
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary

/**
 * CCITTFaxDecode: ITU-T T.4 (Group 3) and T.6 (Group 4) facsimile
 * encoding, used as PDF stream filter `/CCITTFaxDecode` (ISO 32000-1
 * §7.4.7). The dominant encoding for monochrome scanned PDFs.
 *
 * Mode is selected by `/K` in DecodeParms:
 *   - `K < 0`  → Pure 2D ("Group 4", T.6): the modern default
 *   - `K = 0`  → Pure 1D ("Group 3 1D", T.4): common in older scans
 *   - `K > 0`  → Mixed 1D/2D: not implemented, so it falls back to 1D
 *
 * Output is 1 bit per pixel, packed MSB-first, padded to a byte boundary
 * per row. The bit polarity matches the spec default: 0 = black/foreground,
 * 1 = white/background, unless /BlackIs1 is true.
 */
public object CcittFaxFilter : PdfFilter {
    override val name: String = "CCITTFaxDecode"

    override fun decode(input: ByteArray, params: PdfDictionary?): ByteArray {
        val k = params?.getInt("K")?.toInt() ?: 0
        val columns = params?.getInt("Columns")?.toInt() ?: 1728
        val rows = params?.getInt("Rows")?.toInt() ?: 0
        val endOfBlock = (params?.get("EndOfBlock") as? PdfBoolean)?.value ?: true
        val blackIs1 = (params?.get("BlackIs1") as? PdfBoolean)?.value ?: false
        val encodedByteAlign = (params?.get("EncodedByteAlign") as? PdfBoolean)?.value ?: false
        val endOfLine = (params?.get("EndOfLine") as? PdfBoolean)?.value ?: false

        val opts = CcittOptions(columns, rows, endOfBlock, blackIs1, encodedByteAlign, endOfLine)
        // The algorithm (T.4/T.6 + the shared G4 core JBIG2's MMR regions use)
        // lives in KiteImage since the codec consolidation.
        return CcittFax.decode(input, k, opts)
    }
}
