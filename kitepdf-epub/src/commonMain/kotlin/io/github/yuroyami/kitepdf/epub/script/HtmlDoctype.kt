package io.github.yuroyami.kitepdf.epub.script

/**
 * The document type at the start of an HTML document, as HTML's tokenizer reads it (13.2.5.53 to
 * 13.2.5.68), and the quirks mode it puts the document in (13.2.6.4.1). The layout's parser skips
 * a document type, so a script's DOM reads it here (#546).
 */
internal object HtmlDoctype {

    /**
     * What [read] found: the document type, or null for none, whether the document is in quirks
     * mode, and how many comments come before the document type.
     */
    class Found(val doctype: XmlReader.Doctype?, val quirks: Boolean, val commentsBefore: Int = 0)

    /**
     * The document type of [markup]. HTML's parser ignores white space before it and keeps the
     * comments; any other content first means there is none, and the document is in quirks mode.
     */
    fun read(markup: String): Found {
        var i = if (markup.startsWith('\uFEFF')) 1 else 0
        var comments = 0
        while (i < markup.length) {
            when {
                markup[i].isAsciiSpace() -> i++
                markup.startsWith("<!--", i) -> {
                    i = markup.indexOf("-->", i + 2).let { if (it < 0) markup.length else it + 3 }
                    comments++
                }
                markup.regionMatches(i, "<!DOCTYPE", 0, 9, ignoreCase = true) -> return doctype(markup, i + 9, comments)
                else -> break
            }
        }
        return Found(null, quirks = true)
    }

    private fun doctype(s: String, start: Int, comments: Int): Found {
        var i = start
        fun skipSpace() { while (i < s.length && s[i].isAsciiSpace()) i++ }
        skipSpace()
        if (i >= s.length || s[i] == '>') return Found(XmlReader.Doctype("", "", ""), quirks = true, comments)
        val nameStart = i
        while (i < s.length && !s[i].isAsciiSpace() && s[i] != '>') i++
        val name = asciiLower(s.substring(nameStart, i))
        var publicId: String? = null
        var systemId: String? = null
        var forceQuirks = false
        fun quoted(): String? {
            skipSpace()
            val quote = s.getOrNull(i)
            if (quote != '"' && quote != '\'') return null
            val close = s.indexOf(quote, i + 1)
            val gt = s.indexOf('>', i + 1)
            // A > inside the quotes ends the document type and forces quirks mode.
            if (close < 0 || (gt in 0 until close)) {
                val end = if (gt < 0) s.length else gt
                return s.substring(i + 1, end).also { i = end; forceQuirks = true }
            }
            return s.substring(i + 1, close).also { i = close + 1 }
        }
        skipSpace()
        when {
            i >= s.length -> forceQuirks = true
            s[i] == '>' -> {}
            s.regionMatches(i, "PUBLIC", 0, 6, ignoreCase = true) -> {
                i += 6
                publicId = quoted()
                if (publicId == null) forceQuirks = true
                else if (!forceQuirks) {
                    skipSpace()
                    if (i < s.length && s[i] != '>') systemId = quoted() ?: run { forceQuirks = true; null }
                }
            }
            s.regionMatches(i, "SYSTEM", 0, 6, ignoreCase = true) -> {
                i += 6
                systemId = quoted()
                if (systemId == null) forceQuirks = true
            }
            else -> forceQuirks = true
        }
        val doctype = XmlReader.Doctype(name, publicId.orEmpty(), systemId.orEmpty())
        return Found(doctype, forceQuirks || quirks(name, publicId, systemId), comments)
    }

    /** HTML 13.2.6.4.1: whether a document type of [name] and these ids puts its document in quirks mode. */
    private fun quirks(name: String, publicId: String?, systemId: String?): Boolean {
        if (name != "html") return true
        val pub = publicId?.let(::asciiLower)
        val sys = systemId?.let(::asciiLower)
        if (pub != null && (pub in QUIRKS_PUBLIC_IDS || QUIRKS_PUBLIC_PREFIXES.any { pub.startsWith(it) })) return true
        if (sys == "http://www.ibm.com/data/dtd/v11/ibmxhtml1-transitional.dtd") return true
        return pub != null && sys == null && FRAMESET_PREFIXES.any { pub.startsWith(it) }
    }

    private fun Char.isAsciiSpace(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\u000C' || this == '\r'

    private fun asciiLower(s: String): String = buildString(s.length) { for (c in s) append(if (c in 'A'..'Z') c + 32 else c) }

    private val QUIRKS_PUBLIC_IDS = setOf("-//w3o//dtd w3 html strict 3.0//en//", "-/w3c/dtd html 4.0 transitional/en", "html")

    private val FRAMESET_PREFIXES = listOf("-//w3c//dtd html 4.01 frameset//", "-//w3c//dtd html 4.01 transitional//")

    private val QUIRKS_PUBLIC_PREFIXES = listOf(
        "+//silmaril//dtd html pro v0r11 19970101//", "-//as//dtd html 3.0 aswedit + extensions//",
        "-//advasoft ltd//dtd html 3.0 aswedit + extensions//", "-//ietf//dtd html 2.0 level 1//", "-//ietf//dtd html 2.0 level 2//",
        "-//ietf//dtd html 2.0 strict level 1//", "-//ietf//dtd html 2.0 strict level 2//", "-//ietf//dtd html 2.0 strict//",
        "-//ietf//dtd html 2.0//", "-//ietf//dtd html 2.1e//", "-//ietf//dtd html 3.0//", "-//ietf//dtd html 3.2 final//",
        "-//ietf//dtd html 3.2//", "-//ietf//dtd html 3//", "-//ietf//dtd html level 0//", "-//ietf//dtd html level 1//",
        "-//ietf//dtd html level 2//", "-//ietf//dtd html level 3//", "-//ietf//dtd html strict level 0//",
        "-//ietf//dtd html strict level 1//", "-//ietf//dtd html strict level 2//", "-//ietf//dtd html strict level 3//",
        "-//ietf//dtd html strict//", "-//ietf//dtd html//", "-//metrius//dtd metrius presentational//",
        "-//microsoft//dtd internet explorer 2.0 html strict//", "-//microsoft//dtd internet explorer 2.0 html//",
        "-//microsoft//dtd internet explorer 2.0 tables//", "-//microsoft//dtd internet explorer 3.0 html strict//",
        "-//microsoft//dtd internet explorer 3.0 html//", "-//microsoft//dtd internet explorer 3.0 tables//",
        "-//netscape comm. corp.//dtd html//", "-//netscape comm. corp.//dtd strict html//", "-//o'reilly and associates//dtd html 2.0//",
        "-//o'reilly and associates//dtd html extended 1.0//", "-//o'reilly and associates//dtd html extended relaxed 1.0//",
        "-//sq//dtd html 2.0 hotmetal + extensions//",
        "-//softquad software//dtd hotmetal pro 6.0::19990601::extensions to html 4.0//",
        "-//softquad//dtd hotmetal pro 4.0::19971010::extensions to html 4.0//", "-//spyglass//dtd html 2.0 extended//",
        "-//sun microsystems corp.//dtd hotjava html//", "-//sun microsystems corp.//dtd hotjava strict html//",
        "-//w3c//dtd html 3 1995-03-24//", "-//w3c//dtd html 3.2 draft//", "-//w3c//dtd html 3.2 final//", "-//w3c//dtd html 3.2//",
        "-//w3c//dtd html 3.2s draft//", "-//w3c//dtd html 4.0 frameset//", "-//w3c//dtd html 4.0 transitional//",
        "-//w3c//dtd html experimental 19960712//", "-//w3c//dtd html experimental 970421//", "-//w3c//dtd w3 html//",
        "-//w3o//dtd w3 html 3.0//", "-//webtechs//dtd mozilla html 2.0//", "-//webtechs//dtd mozilla html//",
    )
}
