package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfString

/**
 * Optional Content metadata — ISO 32000-1 §8.11. Optional Content Groups
 * (OCGs) are the PDF spec's name for "layers": chunks of content that can
 * be selectively shown or hidden via /OC marked-content sections and
 * Form XObjects with /OC entries.
 *
 * KitePDF v0.0.x exposes the OCG topology and default visibility state
 * (read-only). The renderer doesn't yet honour /OC visibility — that's a
 * future round. Until then this view lets you build a layer-toggle UI
 * even if the underlying renderer still draws everything.
 */
public data class PdfOptionalContent(
    /** All OCGs declared in /OCProperties /OCGs. */
    val groups: List<OptionalContentGroup>,
    /** Identifiers of OCGs that are ON in the default configuration. */
    val onByDefault: Set<String>,
    /** Identifiers of OCGs that are explicitly OFF in the default configuration. */
    val offByDefault: Set<String>,
    /** Display name of the default config (/D /Name), if present. */
    val defaultConfigName: String?,
) {

    /**
     * One Optional Content Group. The [id] is the OCG's PDF object number
     * stringified — stable inside the document and what /OC marked-content
     * sections refer to.
     */
    public data class OptionalContentGroup(
        val id: String,
        val name: String,
        /** Spec /Intent — typically "View" or "Design". Empty when not declared. */
        val intent: List<String>,
        /** /Usage dict for purpose-specific defaults (Print, Export, View). Raw — opaque to us. */
        val usage: PdfDictionary?,
    )

    /** True if the named OCG is visible per the default configuration. */
    public fun isVisibleByDefault(id: String): Boolean = id in onByDefault && id !in offByDefault

    public companion object {
        public val EMPTY: PdfOptionalContent = PdfOptionalContent(emptyList(), emptySet(), emptySet(), null)

        internal fun parse(catalog: PdfDictionary, refs: IndirectResolver): PdfOptionalContent? {
            val props = catalog.getDict("OCProperties", refs) ?: return null

            val ocgs = (props.getArray("OCGs", refs))?.let { arr ->
                buildList {
                    for (item in arr) {
                        val (dict, id) = unwrap(item, refs) ?: continue
                        val name = (dict["Name"] as? PdfString)?.asText() ?: id
                        val intent = parseIntent(dict["Intent"])
                        val usage = dict.getDict("Usage", refs)
                        add(OptionalContentGroup(id, name, intent, usage))
                    }
                }
            } ?: emptyList()

            val defaultConfig = props.getDict("D", refs)
            val defaultName = (defaultConfig?.get("Name") as? PdfString)?.asText()

            // /BaseState: ON (all on by default), OFF (all off), Unchanged. Default ON.
            val baseState = defaultConfig?.getName("BaseState") ?: "ON"
            val allIds = ocgs.map { it.id }.toSet()

            val onIds = mutableSetOf<String>()
            val offIds = mutableSetOf<String>()
            when (baseState) {
                "OFF" -> offIds += allIds
                "Unchanged" -> {}
                else -> onIds += allIds
            }
            // /ON list overrides BaseState off → on.
            defaultConfig?.getArray("ON", refs)?.forEach { obj ->
                (obj as? PdfReference)?.objectNumber?.toString()?.let {
                    onIds += it
                    offIds -= it
                }
            }
            // /OFF list overrides BaseState on → off.
            defaultConfig?.getArray("OFF", refs)?.forEach { obj ->
                (obj as? PdfReference)?.objectNumber?.toString()?.let {
                    offIds += it
                    onIds -= it
                }
            }

            return PdfOptionalContent(ocgs, onIds, offIds, defaultName)
        }

        private fun unwrap(obj: io.github.yuroyami.kitepdf.core.parser.PdfObject, refs: IndirectResolver): Pair<PdfDictionary, String>? {
            return when (obj) {
                is PdfReference -> {
                    val d = refs.resolve(obj) as? PdfDictionary ?: return null
                    d to obj.objectNumber.toString()
                }
                is PdfDictionary -> obj to ""
                else -> null
            }
        }

        private fun parseIntent(obj: io.github.yuroyami.kitepdf.core.parser.PdfObject?): List<String> = when (obj) {
            null -> emptyList()
            is PdfName -> listOf(obj.value)
            is PdfArray -> obj.mapNotNull { (it as? PdfName)?.value }
            else -> emptyList()
        }
    }
}
