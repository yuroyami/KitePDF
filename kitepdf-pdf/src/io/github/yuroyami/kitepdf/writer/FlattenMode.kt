package io.github.yuroyami.kitepdf.writer

/** Which annotations [PdfEditor.flatten] draws into the page, by their flags (ISO 32000-1, 12.5.3, Table 165; #949). */
public enum class FlattenMode {
    /** What prints: an annotation with the Print flag and without the Hidden flag, as PDFium's `FLAT_PRINT`. */
    Print,

    /** What shows on screen: an annotation without the Hidden or NoView flag, as PDFium's `FLAT_NORMALDISPLAY`. */
    Display,
}
