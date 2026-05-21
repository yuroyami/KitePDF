package com.yuroyami.kitepdf.writer

/**
 * The 14 standard Type 1 fonts (ISO 32000-1 §9.6.2.2) that every conforming
 * reader provides, so they need no embedding. [baseFont] is the `/BaseFont`
 * name written into the font dictionary.
 */
enum class StandardFont(val baseFont: String) {
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
