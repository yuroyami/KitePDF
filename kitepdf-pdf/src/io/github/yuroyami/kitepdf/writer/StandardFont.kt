package io.github.yuroyami.kitepdf.writer

/**
 * The 14 standard Type 1 fonts (ISO 32000-1 §9.6.2.2) that every conforming
 * reader provides, so they need no embedding. [baseFont] is the `/BaseFont`
 * name written into the font dictionary.
 */
public enum class StandardFont(public val baseFont: String) {
    Helvetica("Helvetica"),
    HelveticaBold("Helvetica-Bold"),
    HelveticaOblique("Helvetica-Oblique"),
    HelveticaBoldOblique("Helvetica-BoldOblique"),
    TimesRoman("Times-Roman"),
    TimesBold("Times-Bold"),
    TimesItalic("Times-Italic"),
    TimesBoldItalic("Times-BoldItalic"),
    Courier("Courier"),
    CourierBold("Courier-Bold"),
    CourierOblique("Courier-Oblique"),
    CourierBoldOblique("Courier-BoldOblique"),
    Symbol("Symbol"),
    ZapfDingbats("ZapfDingbats"),
}

/**
 * The entries of a standard font dictionary [entries], with `/Encoding /WinAnsiEncoding` for a Latin
 * font, which the writer's text encoder writes for: without one a reader uses the built-in
 * StandardEncoding, where `é` is `Oslash` (ISO 32000-1, 9.6.6.2; #668). Symbol and ZapfDingbats
 * keep their own encodings.
 */
internal fun Map<String, io.github.yuroyami.kitepdf.core.parser.PdfObject>.withTextEncoding(
    font: StandardFont,
): LinkedHashMap<String, io.github.yuroyami.kitepdf.core.parser.PdfObject> =
    LinkedHashMap<String, io.github.yuroyami.kitepdf.core.parser.PdfObject>(this).apply {
        if (font != StandardFont.Symbol && font != StandardFont.ZapfDingbats) {
            put("Encoding", io.github.yuroyami.kitepdf.core.parser.PdfName("WinAnsiEncoding"))
        }
    }
