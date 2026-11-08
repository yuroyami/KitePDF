package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfString

/**
 * Optional Content metadata (ISO 32000-1 §8.11). Optional Content Groups
 * (OCGs) are the PDF spec's name for "layers": chunks of content that can
 * be selectively shown or hidden via /OC marked-content sections and
 * Form XObjects with /OC entries.
 *
 * This view exposes the OCG topology and default visibility state
 * (read-only). The renderer honours /OC visibility: marked-content
 * sections and XObjects whose OCG is hidden in the default configuration
 * are skipped while drawing.
 */
public data class PdfOptionalContent(
    /** All OCGs declared in /OCProperties /OCGs. */
    val groups: List<OptionalContentGroup>,
    /** OCGs ON after the default configuration's View usage rules (ISO 32000-1, 8.11.4.4). */
    val onByDefault: Set<String>,
    /** OCGs OFF after the default configuration's View usage rules (ISO 32000-1, 8.11.4.4). */
    val offByDefault: Set<String>,
    /** Display name of the default config (/D /Name), if present. */
    val defaultConfigName: String?,
    /**
     * The radio-button groups of the configuration (`/RBGroups`, ISO 32000-1, 8.11.4.3, Table
     * 101), by group id: turning one member on turns the others off (#938).
     */
    val radioGroups: List<Set<String>> = emptyList(),
    /** The groups the configuration locks (`/Locked`): a reader does not let a user switch them (#938). */
    val locked: Set<String> = emptySet(),
    /** The names of the alternate configurations (`/Configs`), for [withConfiguration]. */
    val configurations: List<String> = emptyList(),
) {
    /** The `/OCProperties` this was read from, for [withConfiguration] and [forEvent]. */
    internal var source: Pair<PdfDictionary, IndirectResolver>? = null

    /**
     * These states with group [id] switched on or off, as a layer panel switches it (#938).
     * Switching a group on switches the other members of its radio-button groups off. A locked
     * group, and an id the document does not declare, stay as they are.
     */
    public fun withVisible(id: String, visible: Boolean): PdfOptionalContent {
        if (id in locked || groups.none { it.id == id }) return this
        val on = onByDefault.toMutableSet()
        val off = offByDefault.toMutableSet()
        if (visible) {
            on += id; off -= id
            for (group in radioGroups) if (id in group) for (other in group) if (other != id && other !in locked) { off += other; on -= other }
        } else {
            off += id; on -= id
        }
        return copy(onByDefault = on, offByDefault = off).also { it.source = source }
    }

    /**
     * The states of the configuration named [name] in `/OCProperties /Configs` (8.11.4.3), or null
     * when the document has none of that name.
     */
    public fun withConfiguration(name: String): PdfOptionalContent? {
        val (props, refs) = source ?: return null
        val config = missingAsNull { props.getArray("Configs", refs) }?.firstNotNullOfOrNull { c ->
            (missingAsNull { c.resolve(refs) } as? PdfDictionary)?.takeIf { (it["Name"] as? PdfString)?.asText() == name }
        } ?: return null
        return fromConfiguration(props, config, refs, "View")
    }

    /**
     * The states that the default configuration's usage applications give for [event], `View`,
     * `Print` or `Export` (8.11.4.4, Table 103): the print states of the layers, for a print (#938).
     */
    public fun forEvent(event: String): PdfOptionalContent {
        val (props, refs) = source ?: return this
        val config = missingAsNull { props.getDict("D", refs) }
        return fromConfiguration(props, config, refs, event)
    }


    /**
     * One Optional Content Group. The [id] is the OCG's PDF object number
     * stringified. It is stable inside the document, and /OC marked-content
     * sections refer to it.
     */
    public data class OptionalContentGroup(
        val id: String,
        val name: String,
        /** Spec /Intent: typically "View" or "Design". Empty when not declared. */
        val intent: List<String>,
        /** Raw /Usage dict; View usage applications also affect the default visibility sets. */
        val usage: PdfDictionary?,
    )

    /**
     * True if the named OCG is visible per the default configuration. A group
     * is on unless the configuration turns it off: the `/Unchanged` base state
     * leaves every group on at load (ISO 32000-1, 8.11.4.3), and the renderer
     * decides visibility by this same rule (#58).
     */
    public fun isVisibleByDefault(id: String): Boolean = id !in offByDefault

    public companion object {
        public val EMPTY: PdfOptionalContent = PdfOptionalContent(emptyList(), emptySet(), emptySet(), null)

        internal fun parse(catalog: PdfDictionary, refs: IndirectResolver): PdfOptionalContent? {
            val props = missingAsNull { catalog.getDict("OCProperties", refs) } ?: return null
            return fromConfiguration(props, missingAsNull { props.getDict("D", refs) }, refs, "View")
        }

        /** The states of [defaultConfig], a configuration dictionary of [props], with the usage applications of [event]. */
        private fun fromConfiguration(props: PdfDictionary, defaultConfig: PdfDictionary?, refs: IndirectResolver, event: String): PdfOptionalContent {

            val ocgs = missingAsNull { props.getArray("OCGs", refs) }?.let { arr ->
                buildList {
                    for (item in arr) {
                        val (dict, id) = unwrap(item, refs) ?: continue
                        val name = (dict["Name"] as? PdfString)?.asText() ?: id
                        val intent = parseIntent(dict["Intent"])
                        val usage = missingAsNull { dict.getDict("Usage", refs) }
                        add(OptionalContentGroup(id, name, intent, usage))
                    }
                }
            } ?: emptyList()

            // A broken /D leaves every group on, the /BaseState default.
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
            missingAsNull { defaultConfig?.getArray("ON", refs) }?.forEach { obj ->
                (obj as? PdfReference)?.objectNumber?.toString()?.let {
                    onIds += it
                    offIds -= it
                }
            }
            // /OFF list overrides BaseState on → off.
            missingAsNull { defaultConfig?.getArray("OFF", refs) }?.forEach { obj ->
                (obj as? PdfReference)?.objectNumber?.toString()?.let {
                    offIds += it
                    onIds -= it
                }
            }

            // ViewState is applied only through /AS, not merely by declaring
            // /Usage. Repeated applicable entries combine with AND; an absent
            // /OCGs list selects nothing (ISO 32000-1, 8.11.4.4, Table 103).
            val groupsById = ocgs.associateBy { it.id }
            val viewStates = mutableMapOf<String, Boolean>()
            missingAsNull { defaultConfig?.getArray("AS", refs) }?.forEach { obj ->
                applyViewUsage(obj, refs, groupsById, viewStates, event)
            }
            for ((id, visible) in viewStates) {
                if (visible) { onIds += id; offIds -= id }
                else { offIds += id; onIds -= id }
            }

            fun ids(arr: PdfArray?): Set<String> = arr?.mapNotNull { (it as? PdfReference)?.objectNumber?.toString() }?.toSet() ?: emptySet()
            val radios = missingAsNull { defaultConfig?.getArray("RBGroups", refs) }?.mapNotNull { g ->
                ids(missingAsNull { g.resolve(refs) } as? PdfArray).takeIf { it.isNotEmpty() }
            } ?: emptyList()
            val locked = ids(missingAsNull { defaultConfig?.getArray("Locked", refs) })
            val configs = missingAsNull { props.getArray("Configs", refs) }?.mapNotNull { c ->
                ((missingAsNull { c.resolve(refs) } as? PdfDictionary)?.get("Name") as? PdfString)?.asText()
            } ?: emptyList()
            return PdfOptionalContent(ocgs, onIds, offIds, defaultName, radios, locked, configs).also { it.source = props to refs }
        }

        /** Folds one `/AS` usage application into [viewStates]; other events are ignored. */
        private fun applyViewUsage(
            obj: PdfObject,
            refs: IndirectResolver,
            groupsById: Map<String, OptionalContentGroup>,
            viewStates: MutableMap<String, Boolean>,
            event: String,
        ) {
            val application = missingAsNull { obj.resolve(refs) } as? PdfDictionary ?: return
            if (application.getName("Event") != event) return
            val categories = missingAsNull { application.getArray("Category", refs) }?.mapNotNull {
                (missingAsNull { it.resolve(refs) } as? PdfName)?.value
            } ?: return
            val targets = missingAsNull { application.getArray("OCGs", refs) } ?: return
            for (target in targets) {
                val id = (target as? PdfReference)?.objectNumber?.toString() ?: continue
                val usage = groupsById[id]?.usage ?: continue
                val states = categories.mapNotNull { category ->
                    val stateKey = when (category) {
                        "View" -> "ViewState"
                        "Print" -> "PrintState"
                        "Export" -> "ExportState"
                        else -> return@mapNotNull null
                    }
                    when (missingAsNull { usage.getDict(category, refs) }?.getName(stateKey)) {
                        "ON" -> true
                        "OFF" -> false
                        else -> null
                    }
                }
                if (states.isNotEmpty()) {
                    viewStates[id] = viewStates.getOrElse(id) { true } && states.all { it }
                }
            }
        }

        private fun unwrap(obj: PdfObject, refs: IndirectResolver): Pair<PdfDictionary, String>? {
            return when (obj) {
                is PdfReference -> {
                    val d = refs.resolve(obj) as? PdfDictionary ?: return null
                    d to obj.objectNumber.toString()
                }
                is PdfDictionary -> obj to ""
                else -> null
            }
        }

        private fun parseIntent(obj: PdfObject?): List<String> = when (obj) {
            null -> emptyList()
            is PdfName -> listOf(obj.value)
            is PdfArray -> obj.mapNotNull { (it as? PdfName)?.value }
            else -> emptyList()
        }
    }
}
