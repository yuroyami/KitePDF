package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.parser.IndirectResolver
import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfBoolean
import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfName
import io.github.yuroyami.kitepdf.parser.PdfObject
import io.github.yuroyami.kitepdf.parser.PdfReference
import io.github.yuroyami.kitepdf.parser.PdfStream
import io.github.yuroyami.kitepdf.parser.PdfString

/**
 * A PDF action — ISO 32000-1 §12.6. Actions describe what happens when the
 * user activates a link, button, form field, or document open/close event.
 *
 * Common variants:
 *  - [GoTo] — jump to a destination in this document
 *  - [GoToR] — jump to a destination in another PDF
 *  - [Launch] — open an external file/application
 *  - [Uri] — open a URL
 *  - [Named] — execute a predefined viewer action (NextPage, Print, …)
 *  - [JavaScript] — run an embedded script
 *  - [SubmitForm] / [ResetForm] — interactive-form actions
 *
 * Any action type we don't classify (Sound, Movie, Hide, SetOCGState,
 * SetState, Trans, GoTo3DView, Rendition, ImportData, …) is exposed as
 * [Unknown] with its raw dict preserved for caller inspection.
 *
 * Each variant carries the source [raw] dict so callers can pick up fields
 * we didn't extract (notably the `/Next` chain for compound actions).
 */
public sealed class PdfAction {
    public abstract val raw: PdfDictionary

    public data class GoTo(
        /** Unresolved /D — pass through [PdfDocument.resolveDestination] for the typed view. */
        val destination: PdfObject,
        override val raw: PdfDictionary,
    ) : PdfAction()

    public data class GoToR(
        /** Target file path (PDFDocEncoded text). */
        val filename: String,
        val destination: PdfObject?,
        val newWindow: Boolean,
        override val raw: PdfDictionary,
    ) : PdfAction()

    public data class GoToE(
        /** Target /T spec for the embedded file. Kept raw — embedded-target chains are rare. */
        val target: PdfDictionary?,
        val destination: PdfObject?,
        val newWindow: Boolean,
        override val raw: PdfDictionary,
    ) : PdfAction()

    public data class Launch(
        val filename: String,
        val newWindow: Boolean,
        override val raw: PdfDictionary,
    ) : PdfAction()

    public data class Uri(
        val uri: String,
        /** /IsMap — true if the URL is an image-map and the click point should be appended. */
        val isMap: Boolean,
        override val raw: PdfDictionary,
    ) : PdfAction()

    public data class Named(
        val name: NamedActionType,
        /** Original /N name string (preserved for [NamedActionType.Other] cases). */
        val nameRaw: String,
        override val raw: PdfDictionary,
    ) : PdfAction()

    public data class JavaScript(
        /** Script source (decoded from /JS string or stream). */
        val script: String,
        override val raw: PdfDictionary,
    ) : PdfAction()

    public data class SubmitForm(
        val url: String?,
        /** Field names or refs the action targets — null means "all fields". */
        val fields: List<PdfObject>?,
        val flags: Int,
        override val raw: PdfDictionary,
    ) : PdfAction()

    public data class ResetForm(
        val fields: List<PdfObject>?,
        val flags: Int,
        override val raw: PdfDictionary,
    ) : PdfAction()

    public data class Thread(
        /** Reference to the article thread; can be a /Th dict or an integer index into /Threads. */
        val threadRef: PdfObject?,
        /** Optional bead reference within the thread. */
        val beadRef: PdfObject?,
        override val raw: PdfDictionary,
    ) : PdfAction()

    /**
     * Any action whose /S is unknown to this version of the parser, or which
     * we decided not to fully model. The dict is preserved verbatim.
     */
    public data class Unknown(val type: String, override val raw: PdfDictionary) : PdfAction()

    /** Standard /Named action types (ISO 32000-1 §12.6.4.11 Table 211). */
    public enum class NamedActionType {
        NextPage, PrevPage, FirstPage, LastPage, Print, Other,
    }

    public companion object {

        /**
         * Parse an /A action dict (or anything that has `/S` and the right
         * shape for the chosen subtype). Returns `null` only when [dict] is
         * `null`; an unknown `/S` falls through to [Unknown].
         */
        public fun parse(dict: PdfDictionary?, refs: IndirectResolver): PdfAction? {
            if (dict == null) return null
            val s = dict.getName("S") ?: return Unknown("", dict)
            return when (s) {
                "GoTo" -> {
                    val d = dict["D"] ?: return Unknown(s, dict)
                    GoTo(d, dict)
                }
                "GoToR" -> {
                    val filename = fileSpecToString(dict["F"], refs)
                    val newWindow = (dict["NewWindow"] as? PdfBoolean)?.value ?: false
                    GoToR(filename ?: "", dict["D"], newWindow, dict)
                }
                "GoToE" -> {
                    val target = dict.getDict("T", refs)
                    val newWindow = (dict["NewWindow"] as? PdfBoolean)?.value ?: false
                    GoToE(target, dict["D"], newWindow, dict)
                }
                "Launch" -> {
                    val filename = fileSpecToString(dict["F"], refs)
                        // Acrobat-platform-specific subdicts: /Win, /Mac, /Unix each can carry an /F.
                        ?: launchPlatformFilename(dict, refs)
                    val newWindow = (dict["NewWindow"] as? PdfBoolean)?.value ?: false
                    Launch(filename ?: "", newWindow, dict)
                }
                "URI" -> {
                    val uri = (dict["URI"] as? PdfString)?.asText() ?: return Unknown(s, dict)
                    val isMap = (dict["IsMap"] as? PdfBoolean)?.value ?: false
                    Uri(uri, isMap, dict)
                }
                "Named" -> {
                    val n = dict.getName("N") ?: return Unknown(s, dict)
                    val typed = when (n) {
                        "NextPage" -> NamedActionType.NextPage
                        "PrevPage" -> NamedActionType.PrevPage
                        "FirstPage" -> NamedActionType.FirstPage
                        "LastPage" -> NamedActionType.LastPage
                        "Print" -> NamedActionType.Print
                        else -> NamedActionType.Other
                    }
                    Named(typed, n, dict)
                }
                "JavaScript" -> {
                    val script = when (val js = dict["JS"]) {
                        is PdfString -> js.asText()
                        is PdfStream -> io.github.yuroyami.kitepdf.filters.FilterChain.decode(js).decodeToString()
                        is PdfReference -> when (val resolved = refs.resolve(js)) {
                            is PdfString -> resolved.asText()
                            is PdfStream -> io.github.yuroyami.kitepdf.filters.FilterChain.decode(resolved).decodeToString()
                            else -> ""
                        }
                        else -> ""
                    }
                    JavaScript(script, dict)
                }
                "SubmitForm" -> {
                    val url = fileSpecToString(dict["F"], refs)
                    val fields = (dict.getArray("Fields", refs))?.toList()
                    val flags = dict.getInt("Flags")?.toInt() ?: 0
                    SubmitForm(url, fields, flags, dict)
                }
                "ResetForm" -> {
                    val fields = (dict.getArray("Fields", refs))?.toList()
                    val flags = dict.getInt("Flags")?.toInt() ?: 0
                    ResetForm(fields, flags, dict)
                }
                "Thread" -> Thread(dict["D"], dict["B"], dict)
                else -> Unknown(s, dict)
            }
        }

        /**
         * The /F entry can be a string filename or a full FileSpec dict
         * (ISO 32000-1 §7.11). For the dict form, /UF (Unicode) is preferred
         * over /F. We return whichever flavour we can recover as text.
         */
        private fun fileSpecToString(obj: PdfObject?, refs: IndirectResolver): String? {
            val resolved = when (obj) {
                is PdfReference -> refs.resolve(obj)
                else -> obj
            } ?: return null
            return when (resolved) {
                is PdfString -> resolved.asText()
                is PdfDictionary -> (resolved["UF"] as? PdfString)?.asText()
                    ?: (resolved["F"] as? PdfString)?.asText()
                else -> null
            }
        }

        private fun launchPlatformFilename(dict: PdfDictionary, refs: IndirectResolver): String? {
            for (key in listOf("Win", "Mac", "Unix")) {
                val sub = dict.getDict(key, refs) ?: continue
                val f = fileSpecToString(sub["F"], refs) ?: continue
                return f
            }
            return null
        }
    }
}
