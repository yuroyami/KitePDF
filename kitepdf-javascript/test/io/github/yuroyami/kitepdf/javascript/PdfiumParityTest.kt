package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfAction
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import kotlin.test.Test
import kotlinx.coroutines.test.TestResult
import kotlin.test.assertEquals

/**
 * The surface a script may reach, against the one Chrome's PDF engine registers.
 *
 * A file in the wild is written against a viewer, and almost always against Chrome, whose engine
 * is PDFium. So the bar is not Acrobat's whole reference, which runs to thousands of members: it
 * is what PDFium registers, name for name. The lists below are those names, and each test says
 * which of them a script cannot reach here.
 *
 * A name being present does not make its behaviour right, which is what the other tests are for.
 * This one stops a script failing at its first line because something is simply not there.
 */
class PdfiumParityTest {

    private fun emptyForm(): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = LinkedHashMap<Int, Int>()
        fun obj(n: Int, body: String) {
            offsets[n] = buf.size()
            buf.append("$n 0 obj\n$body\nendobj\n".encodeToByteArray())
        }
        buf.append("%PDF-1.7\n%Äå\n".encodeToByteArray())
        obj(1, "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] >> >>")
        obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /CropBox [10 10 190 190] /TrimBox [20 20 180 180] /Annots [4 0 R] >>")
        obj(4, "<< /Type /Annot /Subtype /Widget /FT /Tx /T (field) /V (x) /Rect [10 10 90 30] >>")
        val xref = buf.size()
        val maxN = offsets.keys.max()
        buf.append("xref\n0 ${maxN + 1}\n0000000000 65535 f \n".encodeToByteArray())
        for (n in 1..maxN) {
            val off = offsets[n]
            buf.append(
                (
                    if (off == null) "0000000000 65535 f \n"
                    else "${off.toString().padStart(10, '0')} 00000 n \n"
                    ).encodeToByteArray(),
            )
        }
        buf.append("trailer\n<< /Size ${maxN + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".encodeToByteArray())
        return buf.toByteArray()
    }

    /** Runs [script] in a runner over a one field form and answers what it produced. */
    private fun eval(script: String): String {
        val doc = PdfDocument.open(emptyForm())
        return PdfScriptRunner(doc).use { runner ->
            runner.run(PdfAction.JavaScript(script, PdfDictionary(emptyMap()))) ?: ""
        }
    }

    /** The names of [expected] that the expression [owner] does not carry. */
    private fun missingOn(owner: String, expected: List<String>): List<String> {
        val names = expected.joinToString(",") { "'$it'" }
        val answer = eval(
            "(function () { var o = $owner; var out = [];" +
                " var names = [$names];" +
                " for (var i = 0; i < names.length; i++) { if (!(names[i] in o)) out.push(names[i]); }" +
                " return out.join(','); })()",
        )
        return answer.split(",").filter { it.isNotEmpty() }
    }

    @Test
    fun the_document_members_are_all_reachable(): TestResult = scriptTest {
        val members = listOf(
            "ADBE", "author", "baseURL", "bookmarkRoot", "calculate", "Collab", "creationDate",
            "creator", "delay", "dirty", "documentFileName", "external", "filesize", "icons",
            "info", "keywords", "layout", "media", "modDate", "mouseX", "mouseY", "numFields",
            "numPages", "pageNum", "pageWindowRect", "path", "producer", "subject", "title",
            "URL", "zoom", "zoomType",
            "addAnnot", "addField", "addIcon", "addLink", "calculateNow", "closeDoc",
            "createDataObject", "deletePages", "exportAsFDF", "exportAsText", "extractPages",
            "getAnnot", "getAnnots", "getField", "getIcon", "getLinks", "getNthFieldName",
            "getOCGs", "getPageBox", "getPageNthWord", "getPageNthWordQuads", "getPageNumWords",
            "getPrintParams", "gotoNamedDest", "importAnFDF", "insertPages", "mailDoc",
            "mailForm", "print", "removeField", "removeIcon", "replacePages", "resetForm",
            "saveAs", "submitForm", "syncAnnotScan",
        )
        assertEquals(emptyList(), missingOn("globalThis", members), "missing from the document")
    }

    @Test
    fun the_app_members_are_all_reachable(): TestResult = scriptTest {
        val members = listOf(
            "activeDocs", "calculate", "formsVersion", "fs", "fullscreen", "language", "media",
            "platform", "plugIns", "runtimeHighlight", "viewerType", "viewerVariation",
            "viewerVersion",
            "alert", "beep", "clearInterval", "clearTimeOut", "execDialog", "execMenuItem",
            "getNthPlugInName", "goBack", "goForward", "launchURL", "mailMsg", "newDoc",
            "openDoc", "popUpMenu", "popUpMenuEx", "response", "setInterval", "setTimeOut",
        )
        assertEquals(emptyList(), missingOn("app", members), "missing from app")
    }

    @Test
    fun the_field_members_are_all_reachable(): TestResult = scriptTest {
        val members = listOf(
            "alignment", "borderStyle", "buttonAlignX", "buttonAlignY", "buttonFitBounds",
            "buttonPosition", "buttonScaleHow", "buttonScaleWhen", "calcOrderIndex", "charLimit",
            "comb", "commitOnSelChange", "currentValueIndices", "defaultStyle", "defaultValue",
            "delay", "display", "doc", "doNotScroll", "doNotSpellCheck", "editable",
            "exportValues", "fileSelect", "fillColor", "hidden", "highlight", "lineWidth",
            "multiline", "multipleSelection", "name", "numItems", "page", "password", "print",
            "radiosInUnison", "readonly", "rect", "required", "richText", "richValue", "rotation",
            "strokeColor", "style", "submitName", "textColor", "textFont", "textSize", "type",
            "userName", "value", "valueAsString",
            "browseForFileToSubmit", "buttonGetCaption", "buttonGetIcon", "buttonImportIcon",
            "buttonSetCaption", "buttonSetIcon", "checkThisBox", "clearItems", "defaultIsChecked",
            "deleteItemAt", "getArray", "getItemAt", "getLock", "insertItemAt", "isBoxChecked",
            "isDefaultChecked", "setAction", "setFocus", "setItems", "setLock",
            "signatureGetModifications", "signatureGetSeedValue", "signatureInfo",
            "signatureSetSeedValue", "signatureSign", "signatureValidate",
        )
        assertEquals(emptyList(), missingOn("getField('field')", members), "missing from a Field")
    }

    @Test
    fun the_event_members_are_all_reachable(): TestResult = scriptTest {
        val members = listOf(
            "change", "changeEx", "commitKey", "fieldFull", "keyDown", "modifier", "name", "rc",
            "richChange", "richChangeEx", "richValue", "selEnd", "selStart", "shift", "source",
            "target", "targetName", "type", "value", "willCommit",
        )
        assertEquals(emptyList(), missingOn("event", members), "missing from event")
    }

    @Test
    fun the_helper_objects_are_all_reachable(): TestResult = scriptTest {
        assertEquals(
            emptyList(),
            missingOn("util", listOf("printd", "printf", "printx", "scand", "byteToChar")),
            "missing from util",
        )
        assertEquals(
            emptyList(),
            missingOn(
                "color",
                listOf(
                    "black", "blue", "cyan", "dkGray", "gray", "green", "ltGray", "magenta",
                    "red", "transparent", "white", "yellow", "convert", "equal",
                ),
            ),
            "missing from color",
        )
        assertEquals(
            emptyList(),
            missingOn("console", listOf("clear", "hide", "println", "show")),
            "missing from console",
        )
        assertEquals(
            emptyList(),
            missingOn("global", listOf("setPersistent", "subscribe")),
            "missing from global",
        )
    }

    @Test
    fun the_helper_library_is_all_reachable(): TestResult = scriptTest {
        val functions = listOf(
            "AFNumber_Format", "AFNumber_Keystroke", "AFPercent_Format", "AFPercent_Keystroke",
            "AFDate_Format", "AFDate_FormatEx", "AFDate_Keystroke", "AFDate_KeystrokeEx",
            "AFTime_Format", "AFTime_FormatEx", "AFTime_Keystroke", "AFTime_KeystrokeEx",
            "AFSpecial_Format", "AFSpecial_Keystroke", "AFSpecial_KeystrokeEx", "AFSimple",
            "AFSimple_Calculate", "AFRange_Validate", "AFMergeChange", "AFParseDateEx",
            "AFExtractNums", "AFMakeNumber", "AFMakeArrayFromList",
        )
        val missing = eval(
            "(function () { var out = []; var names = [${functions.joinToString(",") { "'$it'" }}];" +
                " for (var i = 0; i < names.length; i++) { if (typeof globalThis[names[i]] !== 'function') out.push(names[i]); }" +
                " return out.join(','); })()",
        ).split(",").filter { it.isNotEmpty() }
        assertEquals(emptyList(), missing, "missing helper functions")
    }

    @Test
    fun printf_truncates_d_and_x_and_prints_six_decimals_for_f(): TestResult = scriptTest {
        // The Acrobat reference and PDFium's ToInt32 (#763).
        assertEquals("2|-2|ff|ffffffff|1.500000", eval(
            "[util.printf('%d', 2.7), util.printf('%d', -2.7), util.printf('%x', 255.9), util.printf('%x', -1), util.printf('%f', 1.5)].join('|')",
        ))
    }

    @Test
    fun printf_separator_styles_set_the_decimal_mark_too(): TestResult = scriptTest {
        // The nDecSep flag of util.printf (#761).
        assertEquals("1,234.56|1234.56|1.234,56|1234,56|1'234.56", eval(
            "[0, 1, 2, 3, 4].map(function (k) { return util.printf('%,' + k + '.2f', 1234.56); }).join('|')",
        ))
    }

    @Test
    fun special_format_puts_every_value_through_its_mask(): TestResult = scriptTest {
        // PDFium, MuPDF and pdf.js (#776).
        fun special(style: Int, value: String) = eval(
            "(function () { var e = { value: '$value' }; var saved = event; event = e; try { AFSpecial_Format($style); } finally { event = saved; } return e.value; })()",
        )
        assertEquals("(123) 456-7890", special(2, "1234567890"))
        assertEquals("123-4567", special(2, "12345678"))
        assertEquals("123-45-678", special(3, "12345678"))
    }

    @Test
    fun get_page_box_answers_the_box_it_is_asked_for(): TestResult = scriptTest {
        // The Acrobat reference, Doc.getPageBox, as [left, top, right, bottom] (#787).
        assertEquals("0,200,200,0|10,190,190,10|20,180,180,20|10,190,190,10|10,190,190,10", eval(
            "['Media', 'Crop', 'Trim', 'Art', 'Bleed'].map(function (b) { return this.getPageBox(b, 0).join(','); }, this).join('|')",
        ))
    }
}
