package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.ByteReader
import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.Lexer
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfString
import io.github.yuroyami.kitepdf.core.xml.KiteXml
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import io.github.yuroyami.kitepdf.parser.Parser
import io.github.yuroyami.kitepdf.writer.PdfObjectWriter
import io.github.yuroyami.kitepdf.writer.PdfText

/**
 * Form data exchange (#950): the values of a form as XFDF (ISO 19444-1) or FDF (ISO 32000-1,
 * 12.7.7), and the values such a file carries, by fully qualified field name.
 */
internal object FormData {

    /** One field's value: its fully qualified name, its kind, and its values, several for a list box. */
    class Value(val name: String, val button: Boolean, val values: List<String>)

    /** The values of [doc]'s fields that hold one: text, check box and radio button states, choices. */
    fun valuesOf(doc: PdfDocument): List<Value> = doc.formFields.mapNotNull { field ->
        when (field.type) {
            PdfFormField.FieldType.Text -> field.value?.let { Value(field.fullyQualifiedName, false, listOf(it)) }
            // A push button holds no value (Table 226, bit 17).
            PdfFormField.FieldType.Button -> if (field.flags and (1 shl 16) != 0) null else field.value?.let { Value(field.fullyQualifiedName, true, listOf(it)) }
            PdfFormField.FieldType.Choice -> {
                val s = field.choiceSelection
                val picked = s.indices.mapNotNull { i -> field.choiceOptions.firstOrNull { it.index == i }?.exportValue } + s.unresolvedValues + listOfNotNull(s.freeText)
                picked.takeIf { it.isNotEmpty() }?.let { Value(field.fullyQualifiedName, false, it) }
            }
            else -> null
        }
    }

    /** A tree of partial names, for the nesting both formats write. */
    private class Node(val name: String) {
        val kids = LinkedHashMap<String, Node>()
        var value: Value? = null
    }

    private fun tree(values: List<Value>): Node {
        val root = Node("")
        for (v in values) {
            var at = root
            for (part in v.name.split('.')) at = at.kids.getOrPut(part) { Node(part) }
            at.value = v
        }
        return root
    }

    fun xfdf(doc: PdfDocument, source: String?): String {
        val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8"?>""" + "\n")
        sb.append("""<xfdf xmlns="http://ns.adobe.com/xfdf/" xml:space="preserve">""").append('\n')
        if (source != null) sb.append("""<f href="${escape(source)}"/>""").append('\n')
        sb.append("<fields>\n")
        fun write(node: Node) {
            sb.append("""<field name="${escape(node.name)}">""")
            node.value?.values?.forEach { sb.append("<value>").append(escape(it)).append("</value>") }
            for (kid in node.kids.values) write(kid)
            sb.append("</field>\n")
        }
        for (kid in tree(valuesOf(doc)).kids.values) write(kid)
        sb.append("</fields>\n</xfdf>\n")
        return sb.toString()
    }

    fun fdf(doc: PdfDocument, source: String?): ByteArray {
        fun text(s: String) = PdfString(PdfText.encodeTextString(s))
        fun fieldOf(node: Node): PdfDictionary {
            val d = LinkedHashMap<String, PdfObject>()
            d["T"] = text(node.name)
            node.value?.let { v ->
                d["V"] = when {
                    v.button -> PdfName(v.values.first())
                    v.values.size == 1 -> text(v.values.first())
                    else -> PdfArray(v.values.map(::text))
                }
            }
            if (node.kids.isNotEmpty()) d["Kids"] = PdfArray(node.kids.values.map(::fieldOf))
            return PdfDictionary(d)
        }
        val fdf = LinkedHashMap<String, PdfObject>()
        fdf["Fields"] = PdfArray(tree(valuesOf(doc)).kids.values.map(::fieldOf))
        if (source != null) fdf["F"] = text(source)
        val out = ByteArrayBuilder()
        out.append("%FDF-1.2\n%âãÏÓ\n1 0 obj\n".encodeToByteArray())
        PdfObjectWriter.writeObject(PdfDictionary(linkedMapOf("FDF" to PdfDictionary(fdf))), out)
        out.append("\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n".encodeToByteArray())
        return out.toByteArray()
    }

    /** The values that [data], an XFDF or an FDF file, carries, by fully qualified name. */
    fun parse(data: ByteArray): List<Pair<String, List<String>>> {
        val start = data.indexOfFirst { it.toInt().toChar() !in " \t\r\n﻿" && it != 0xEF.toByte() && it != 0xBB.toByte() && it != 0xBF.toByte() }
        return if (start >= 0 && data[start] == '<'.code.toByte()) parseXfdf(data.decodeToString()) else parseFdf(data)
    }

    private fun parseXfdf(xml: String): List<Pair<String, List<String>>> {
        val out = ArrayList<Pair<String, List<String>>>()
        fun walk(el: KiteXmlNode.Element, prefix: String?) {
            for (c in el.children) {
                val child = c as? KiteXmlNode.Element ?: continue
                when (child.tag) {
                    // The annotations of an XFDF are not form values.
                    "annots", "value", "value-richtext" -> {}
                    "field" -> {
                        val name = child.attrs["name"] ?: continue
                        val full = if (prefix == null) name else "$prefix.$name"
                        val values = child.children.filterIsInstance<KiteXmlNode.Element>().filter { it.tag == "value" }.map(::textOf)
                        if (values.isNotEmpty()) out += full to values
                        walk(child, full)
                    }
                    // `xfdf` and `fields` hold the fields.
                    else -> walk(child, prefix)
                }
            }
        }
        walk(KiteXml.parse(xml), null)
        return out
    }

    private fun parseFdf(data: ByteArray): List<Pair<String, List<String>>> {
        // An FDF file has the syntax of PDF without its cross-reference table, so each "n g obj" is read where it stands.
        val objects = HashMap<Long, PdfObject>()
        val text = buildString(data.size) { for (b in data) append((b.toInt() and 0xFF).toChar()) }
        for (m in OBJ.findAll(text)) {
            val reader = ByteReader(data)
            reader.seek(m.range.first)
            runCatching { Parser(Lexer(reader)).readIndirectObject() }.getOrNull()?.let { objects[it.number] = it.value }
        }
        val refs = IndirectResolver { objects[it.objectNumber] }
        val fdf = objects.values.firstNotNullOfOrNull { (it as? PdfDictionary)?.getDict("FDF", refs) } ?: return emptyList()
        val out = ArrayList<Pair<String, List<String>>>()
        fun walk(fields: PdfArray?, prefix: String?) {
            for (item in fields.orEmpty()) {
                val field = item.resolve(refs) as? PdfDictionary ?: continue
                val partial = (field["T"]?.resolve(refs) as? PdfString)?.asText()
                val full = when {
                    partial == null -> prefix
                    prefix == null -> partial
                    else -> "$prefix.$partial"
                } ?: continue
                val values = when (val v = field["V"]?.resolve(refs)) {
                    is PdfString -> listOf(v.asText())
                    is PdfName -> listOf(v.value)
                    is PdfArray -> v.mapNotNull { (it.resolve(refs) as? PdfString)?.asText() }
                    else -> emptyList()
                }
                if (values.isNotEmpty()) out += full to values
                walk(field.getArray("Kids", refs), full)
            }
        }
        walk(fdf.getArray("Fields", refs), null)
        return out
    }

    private fun textOf(el: KiteXmlNode.Element): String = buildString {
        for (c in el.children) when (c) {
            is KiteXmlNode.Text -> append(c.text)
            is KiteXmlNode.Element -> append(textOf(c))
            else -> {}
        }
    }

    private fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }

    private val OBJ = Regex("""(?<![0-9])\d+\s+\d+\s+obj\b""")
}
