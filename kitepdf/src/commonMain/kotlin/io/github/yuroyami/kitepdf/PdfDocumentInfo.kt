package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfName
import io.github.yuroyami.kitepdf.parser.PdfString

/**
 * Contents of the trailer's `/Info` dictionary (ISO 32000-1 §14.3.3).
 *
 * Every field is optional in the spec; missing entries are `null`. Custom
 * (non-standard) string entries are preserved in [custom] so callers can
 * inspect them. `/Trapped` is a `/Name` per spec — exposed as the typed
 * [Trapped] enum.
 *
 * Note: PDF 2.0 deprecates /Info in favour of the XMP metadata stream on
 * the document catalog. We still surface /Info because almost every PDF in
 * the wild uses it; XMP support is a separate session.
 */
data class PdfDocumentInfo(
    val title: String? = null,
    val author: String? = null,
    val subject: String? = null,
    val keywords: String? = null,
    val creator: String? = null,
    val producer: String? = null,
    val creationDate: PdfDate? = null,
    val modDate: PdfDate? = null,
    val trapped: Trapped = Trapped.Unknown,
    /** Any /Info entry whose key is not one of the standard nine, as raw text. */
    val custom: Map<String, String> = emptyMap(),
) {

    enum class Trapped { True, False, Unknown }

    companion object {
        private val STANDARD_KEYS = setOf(
            "Title", "Author", "Subject", "Keywords",
            "Creator", "Producer", "CreationDate", "ModDate", "Trapped",
        )

        internal fun parse(dict: PdfDictionary): PdfDocumentInfo {
            fun text(key: String): String? = (dict[key] as? PdfString)?.asText()
            fun date(key: String): PdfDate? =
                (dict[key] as? PdfString)?.asAsciiOrNull()?.let(PdfDate::parse)

            val trapped = when ((dict["Trapped"] as? PdfName)?.value) {
                "True" -> Trapped.True
                "False" -> Trapped.False
                else -> Trapped.Unknown
            }
            val custom = buildMap {
                for ((k, v) in dict.map) {
                    if (k in STANDARD_KEYS) continue
                    val s = (v as? PdfString)?.asText() ?: continue
                    put(k, s)
                }
            }
            return PdfDocumentInfo(
                title = text("Title"),
                author = text("Author"),
                subject = text("Subject"),
                keywords = text("Keywords"),
                creator = text("Creator"),
                producer = text("Producer"),
                creationDate = date("CreationDate"),
                modDate = date("ModDate"),
                trapped = trapped,
                custom = custom,
            )
        }
    }
}

/**
 * A PDF date-time (ISO 32000-1 §7.9.4): `D:YYYYMMDDHHmmSSOHH'mm'`.
 *
 * Each component except `year` is optional; missing tail bytes default to
 * the smallest sensible value (1 for month/day, 0 otherwise). The `D:`
 * prefix is also optional in practice (writers occasionally omit it).
 *
 * [tzSign] is one of `'+'`, `'-'`, `'Z'`. `'Z'` means UTC and forces the
 * hour/minute offsets to zero.
 */
data class PdfDate(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val tzSign: Char,
    val tzHour: Int,
    val tzMinute: Int,
) {
    /** Reformat back to PDF wire-form. */
    override fun toString(): String = buildString {
        append("D:")
        append(year.toString().padStart(4, '0'))
        append(month.toString().padStart(2, '0'))
        append(day.toString().padStart(2, '0'))
        append(hour.toString().padStart(2, '0'))
        append(minute.toString().padStart(2, '0'))
        append(second.toString().padStart(2, '0'))
        if (tzSign == 'Z') {
            append('Z')
        } else {
            append(tzSign)
            append(tzHour.toString().padStart(2, '0'))
            append('\'')
            append(tzMinute.toString().padStart(2, '0'))
            append('\'')
        }
    }

    companion object {
        fun parse(raw: String): PdfDate? {
            val s = raw.removePrefix("D:")
            if (s.length < 4) return null

            fun digits(off: Int, len: Int): Int? {
                if (off + len > s.length) return null
                val chunk = s.substring(off, off + len)
                if (chunk.any { it !in '0'..'9' }) return null
                return chunk.toInt()
            }

            val year = digits(0, 4) ?: return null
            val month = (digits(4, 2) ?: 1).coerceIn(1, 12)
            val day = (digits(6, 2) ?: 1).coerceIn(1, 31)
            val hour = (digits(8, 2) ?: 0).coerceIn(0, 23)
            val minute = (digits(10, 2) ?: 0).coerceIn(0, 59)
            val second = (digits(12, 2) ?: 0).coerceIn(0, 59)

            val tzMarker = s.getOrNull(14)
            val tzSign: Char
            val tzHour: Int
            val tzMinute: Int
            when (tzMarker) {
                null -> { tzSign = '+'; tzHour = 0; tzMinute = 0 }
                'Z' -> { tzSign = 'Z'; tzHour = 0; tzMinute = 0 }
                '+', '-' -> {
                    tzSign = tzMarker
                    tzHour = (digits(15, 2) ?: 0).coerceIn(0, 23)
                    // Spec form ends with "HH'mm'" but writers vary; find the apostrophe leniently.
                    val apos = s.indexOf('\'', startIndex = 17)
                    tzMinute = if (apos < 0 || apos + 1 >= s.length) {
                        0
                    } else {
                        val end = minOf(s.length, apos + 3)
                        s.substring(apos + 1, end).filter { it in '0'..'9' }
                            .toIntOrNull()?.coerceIn(0, 59) ?: 0
                    }
                }
                else -> { tzSign = '+'; tzHour = 0; tzMinute = 0 }
            }
            return PdfDate(year, month, day, hour, minute, second, tzSign, tzHour, tzMinute)
        }
    }
}
