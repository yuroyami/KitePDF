package io.github.yuroyami.kitepdf.crypto.sig

import io.github.yuroyami.kitepdf.PdfDate

/** A UTCTime (two-digit year, RFC 5280, 4.1.2.5.1) or a GeneralizedTime as a [PdfDate]. */
internal fun asn1Date(text: String?): PdfDate? = runCatching {
    val t = text?.trim() ?: return null
    val digits = t.takeWhile { it.isDigit() }
    val full = if (digits.length == 10 || digits.length == 12) {
        (if (digits.substring(0, 2).toInt() < 50) "20" else "19") + digits
    } else {
        digits
    }
    val rest = t.substring(digits.length).dropWhile { it == '.' || it == ',' || it.isDigit() }
    val zone = when {
        rest.startsWith("Z") -> "Z"
        rest.startsWith("+") || rest.startsWith("-") -> "${rest[0]}${rest.substring(1, 3)}'${rest.substring(3, 5)}'"
        else -> ""
    }
    PdfDate.parse("D:" + full.padEnd(14, '0') + zone)
}.getOrNull()

/** [d] as seconds since 1970 in UTC, by the days-from-civil count of the proleptic Gregorian calendar. */
internal fun utcSeconds(d: PdfDate): Long {
    val y = d.year - if (d.month <= 2) 1 else 0
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (d.month + 9) % 12
    val doy = (153 * mp + 2) / 5 + d.day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era * 146097L + doe - 719468
    val offset = if (d.tzSign == 'Z') 0 else (d.tzHour * 3600 + d.tzMinute * 60) * (if (d.tzSign == '-') -1 else 1)
    return days * 86400 + d.hour * 3600 + d.minute * 60 + d.second - offset
}

/** [text], an ASN.1 time, as seconds since 1970 in UTC, or null when it does not read. */
internal fun asn1Seconds(text: String?): Long? = asn1Date(text)?.let(::utcSeconds)
