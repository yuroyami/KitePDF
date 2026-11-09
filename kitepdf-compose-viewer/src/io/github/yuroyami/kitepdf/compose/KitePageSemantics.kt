package io.github.yuroyami.kitepdf.compose

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import io.github.yuroyami.kitepdf.PdfAnnotation
import io.github.yuroyami.kitepdf.PdfFormField
import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.KiteRole
import io.github.yuroyami.kitepdf.core.KiteStructuredText
import io.github.yuroyami.kitepdf.epub.EpubPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** What a node of the page is to a screen reader. */
internal enum class AccessKind { TEXT, HEADING, IMAGE, LINK, TEXT_FIELD, CHECKBOX, RADIO, BUTTON, CHOICE }

/**
 * One node a screen reader finds on a page: its place in display space, or null for the whole
 * page, its name, what it is, and for a field its value (#427).
 */
internal class PageAccessNode(
    val rect: KiteRectangle?,
    val label: String,
    val kind: AccessKind,
    val state: String? = null,
    val checked: Boolean? = null,
    val enabled: Boolean = true,
    /** A password field: its node says it is one, and carries no value (#953). */
    val password: Boolean = false,
)

/** The viewer's tap, which a link or a field node runs at its centre, as a finger would. */
internal val LocalViewerTap = compositionLocalOf<((androidx.compose.ui.geometry.Offset) -> Unit)?> { null }

/**
 * The nodes of [page]: its reading order, then its links, then its form fields when [formState]
 * holds them. Reads the page's text, so a caller runs it off the main thread.
 */
internal fun accessNodes(page: KitePage, formState: PdfFormState?, strings: KiteViewerStrings): List<PageAccessNode> =
    contentNodes(page, strings) + widgetNodes(page, formState, strings)

/** The nodes of [page] that do not follow the form: its reading order, then its links (#666). */
internal fun contentNodes(page: KitePage, strings: KiteViewerStrings): List<PageAccessNode> {
    val out = ArrayList<PageAccessNode>()
    for (item in runCatching { page.readingOrder() }.getOrDefault(emptyList())) {
        val kind = when (item.role) {
            KiteRole.HEADING -> AccessKind.HEADING
            KiteRole.IMAGE -> AccessKind.IMAGE
            else -> AccessKind.TEXT
        }
        if (item.text.isNotBlank() || kind == AccessKind.IMAGE) out += PageAccessNode(item.bounds, item.text, kind)
    }
    val text = runCatching { page.textContent() }.getOrNull()
    when (page) {
        is PdfPage -> for (annotation in runCatching { page.annotations }.getOrDefault(emptyList())) {
            if (annotation.subtype != PdfAnnotation.Subtype.Link || annotation.isHidden) continue
            val rect = page.pageToDisplay(annotation.rect)
            out += PageAccessNode(rect, textIn(text, rect) ?: annotation.uri ?: strings.link, AccessKind.LINK)
        }
        is EpubPage -> for (link in runCatching { page.links }.getOrDefault(emptyList())) {
            out += PageAccessNode(link.rect, textIn(text, link.rect) ?: link.href.substringAfterLast('/').ifEmpty { strings.link }, AccessKind.LINK)
        }
        else -> for (link in runCatching { page.hyperlinks }.getOrDefault(emptyList())) {
            out += PageAccessNode(link.rect, textIn(text, link.rect) ?: link.uri ?: strings.link, AccessKind.LINK)
        }
    }
    return out
}

/**
 * The form field nodes of [page], when [formState] holds them: one for each widget that shows,
 * named and valued from the field that a tap at its centre finds (#666).
 */
internal fun widgetNodes(page: KitePage, formState: PdfFormState?, strings: KiteViewerStrings): List<PageAccessNode> {
    if (page !is PdfPage || formState == null) return emptyList()
    val out = ArrayList<PageAccessNode>()
    val widgets = runCatching { page.visibleWidgets(formState) }.getOrDefault(emptyList())
    for (own in widgets) {
        val r = own.widget.rect?.normalized() ?: continue
        val cx = (r.left + r.right) / 2
        val cy = (r.bottom + r.top) / 2
        // The widget with its field, as a tap at its centre finds it: the last that shows there.
        val hit = widgets.lastOrNull { w -> w.widget.rect?.normalized()?.let { cx >= it.left && cx <= it.right && cy >= it.bottom && cy <= it.top } == true } ?: own
        val field = hit.field
        val name = field.tooltip?.takeIf { it.isNotBlank() } ?: field.partialName?.takeIf { it.isNotBlank() } ?: strings.formField
        val value = if (field.type == PdfFormField.FieldType.Choice) {
            val chosen = formState.choiceSelection(field.fullyQualifiedName)
            chosen?.let { selection ->
                field.choiceOptions.filter { it.index in selection.indices }.map { it.label }
                    .plus(listOfNotNull(selection.freeText)).plus(selection.unresolvedValues).joinToString(", ")
            }
        } else formState.value(field.fullyQualifiedName)
        val kind = when (field.type) {
            PdfFormField.FieldType.Text -> AccessKind.TEXT_FIELD
            PdfFormField.FieldType.Choice -> AccessKind.CHOICE
            PdfFormField.FieldType.Button -> when {
                field.flags and PUSH_BUTTON != 0 -> AccessKind.BUTTON
                field.flags and RADIO != 0 -> AccessKind.RADIO
                else -> AccessKind.CHECKBOX
            }
            else -> AccessKind.BUTTON
        }
        val onState = hit.widget.onStateName
        val checked = if (kind == AccessKind.CHECKBOX || kind == AccessKind.RADIO) onState != null && value == onState else null
        // ISO 32000-1, 12.7.4.3, Table 228: a password "should not be echoed visibly", and a screen
        // reader or any accessibility service reads this tree, so the node never holds the value (#953).
        val password = kind == AccessKind.TEXT_FIELD && field.flags and PASSWORD != 0
        val state = if (password) null else value?.takeIf { checked == null }
        out += PageAccessNode(page.pageToDisplay(r), name, kind, state = state, checked = checked, enabled = !formState.isReadOnly(field.fullyQualifiedName), password = password)
    }
    return out
}

/** The words of [text] whose middle lies inside [rect], or null when none does. */
private fun textIn(text: KiteStructuredText?, rect: KiteRectangle): String? {
    text ?: return null
    val r = rect.normalized()
    val sb = StringBuilder()
    for (line in text.blocks.flatMap { it.lines }) {
        if (line.vertical) continue
        val middle = (line.bounds.bottom + line.bounds.top) / 2
        if (middle < r.bottom || middle > r.top) continue
        val start = sb.length
        for (i in line.text.indices) {
            val x = (line.charEdges[i] + line.charEdges[i + 1]) / 2
            if (x >= r.left && x <= r.right) sb.append(line.text[i])
        }
        if (sb.length > start && start > 0 && !sb[start - 1].isWhitespace()) sb.insert(start, ' ')
    }
    return sb.toString().trim().takeIf { it.isNotEmpty() }
}

/**
 * The page's nodes for a screen reader, over the page in its own frame: its text in reading
 * order at its place, its links as buttons and its form fields, a link or a field acting as a
 * tap at its centre (#427). A page builds them once the reader rests on it, off the main thread.
 */
@Composable
internal fun PageSemantics(state: KiteDocViewState, page: KitePage, pageIndex: Int, modifier: Modifier) {
    val tap = LocalViewerTap.current ?: return
    val strings = LocalKiteViewerStrings.current
    val formState = state.scripts?.formState
    // The nodes follow the content version the pixels follow: a script's change to the chapter, a
    // :target restyle or a layer change keeps the page object, and nodes of the old layout would
    // name text and tap links that are no longer there (#673). A new version drops them at once.
    val contentVersion = state.contentVersionOf(page)
    // The text and the links follow the page's content only, and the fields the form too, so a
    // form that changes at frame rate rebuilds only its field nodes, at most every
    // WIDGET_NODES_INTERVAL (#666).
    var content by remember(page, contentVersion) { mutableStateOf<List<PageAccessNode>>(emptyList()) }
    var widgets by remember(page, contentVersion) { mutableStateOf<List<PageAccessNode>>(emptyList()) }
    val shown by remember(state, pageIndex) { derivedStateOf { state.inZoomedView(pageIndex) && state.pageGeometry.containsKey(pageIndex) } }
    LaunchedEffect(page, contentVersion, strings) {
        // The text is read once the page shows and the view rests, not for every page scrolled past.
        snapshotFlow { shown && state.adapter?.isScrollInProgress != true }.first { it }
        val built = buildNodes(pageIndex) { contentNodes(page, strings) }
        // The nodes are state that composition reads, so they are written on its thread (#443).
        backOnComposeThread()
        content = built
        state.accessNodeBuilds++
    }
    val lastWidgetBuild = remember(page) { arrayOfNulls<TimeSource.Monotonic.ValueTimeMark>(1) }
    LaunchedEffect(page, contentVersion, formState, strings, state.formRevision) {
        if (page !is PdfPage || formState == null) {
            widgets = emptyList()
            return@LaunchedEffect
        }
        snapshotFlow { shown && state.adapter?.isScrollInProgress != true }.first { it }
        val wait = lastWidgetBuild[0]?.let { WIDGET_NODES_INTERVAL - it.elapsedNow() }
        if (wait != null && wait.isPositive()) delay(wait)
        val built = buildNodes(pageIndex) { widgetNodes(page, formState, strings) }
        backOnComposeThread()
        lastWidgetBuild[0] = TimeSource.Monotonic.markNow()
        widgets = built
        state.widgetNodeBuilds++
    }
    val nodes = if (widgets.isEmpty()) content else content + widgets
    if (nodes.isEmpty()) return
    Layout(
        content = {
            for ((i, node) in nodes.withIndex()) {
                Box(
                    Modifier.semantics {
                        traversalIndex = i.toFloat()
                        describe(node) {
                            val centre = node.rect?.let { state.displayRectToViewport(pageIndex, it)?.center }
                            if (centre != null) tap(centre)
                            centre != null
                        }
                    },
                )
            }
        },
        modifier = modifier.semantics { isTraversalGroup = true },
    ) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
        val sx = if (page.displayWidth > 0.0) width / page.displayWidth else 0.0
        val sy = if (page.displayHeight > 0.0) height / page.displayHeight else 0.0
        fun edge(v: Double, scale: Double, side: Int) = (v * scale).takeIf { it.isFinite() }?.coerceIn(0.0, side.toDouble())?.roundToInt() ?: 0
        val placed = measurables.mapIndexed { i, m ->
            val r = nodes.getOrNull(i)?.rect?.normalized()
            if (r == null) {
                m.measure(Constraints.fixed(width, height)) to IntOffset.Zero
            } else {
                val left = edge(r.left, sx, width)
                val top = edge(r.bottom, sy, height)
                val w = (edge(r.right, sx, width) - left).coerceAtLeast(1)
                val h = (edge(r.top, sy, height) - top).coerceAtLeast(1)
                m.measure(Constraints.fixed(w, h)) to IntOffset(left, top)
            }
        }
        layout(width, height) { for ((p, at) in placed) p.place(at) }
    }
}

/** The name, role, state and action of [node] for a screen reader. */
private fun androidx.compose.ui.semantics.SemanticsPropertyReceiver.describe(node: PageAccessNode, act: () -> Boolean) {
    when (node.kind) {
        AccessKind.TEXT -> text = AnnotatedString(node.label)
        AccessKind.HEADING -> {
            text = AnnotatedString(node.label)
            heading()
        }
        AccessKind.IMAGE -> {
            contentDescription = node.label
            role = Role.Image
        }
        AccessKind.LINK -> {
            contentDescription = node.label
            role = Role.Button
            onClick { act() }
        }
        AccessKind.TEXT_FIELD -> {
            contentDescription = node.label
            if (node.password) password() else node.state?.let { stateDescription = it }
            // ISO 32000-1, 12.7.3.1, Table 221: a read-only field "shall not interact with the user" (#671).
            if (node.enabled) onClick { act() } else disabled()
        }
        AccessKind.CHOICE -> {
            contentDescription = node.label
            role = Role.DropdownList
            node.state?.let { stateDescription = it }
            if (node.enabled) onClick { act() } else disabled()
        }
        AccessKind.CHECKBOX -> {
            contentDescription = node.label
            role = Role.Checkbox
            toggleableState = ToggleableState(node.checked == true)
            // ISO 32000-1, 12.7.3.1, Table 221: a read-only field "shall not interact with the user" (#671).
            if (node.enabled) onClick { act() } else disabled()
        }
        AccessKind.RADIO -> {
            contentDescription = node.label
            role = Role.RadioButton
            selected = node.checked == true
            // ISO 32000-1, 12.7.3.1, Table 221: a read-only field "shall not interact with the user" (#671).
            if (node.enabled) onClick { act() } else disabled()
        }
        AccessKind.BUTTON -> {
            contentDescription = node.label
            role = Role.Button
            // ISO 32000-1, 12.7.3.1, Table 221: a read-only field "shall not interact with the user" (#671).
            if (node.enabled) onClick { act() } else disabled()
        }
    }
}

/** The `/Ff` flag of a push button (ISO 32000-1, Table 226). */
private const val PUSH_BUTTON = 1 shl 16

/** The `/Ff` flag of a radio button (ISO 32000-1, Table 226). */
private const val RADIO = 1 shl 15

/** `/Ff` bit 14 of a text field: it takes a password (ISO 32000-1, 12.7.4.3, Table 228). */
private const val PASSWORD = 1 shl 13

/** Runs [build] off the main thread; a page whose content cannot be read gives no nodes. */
private suspend fun buildNodes(pageIndex: Int, build: () -> List<PageAccessNode>): List<PageAccessNode> = try {
    withContext(kitepdfRasterDispatcher()) { build() }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    io.github.yuroyami.kitepdf.core.kiteWarn { "accessibility: the content of page $pageIndex cannot be read: ${failure.message}" }
    emptyList()
}

/** The least time between two builds of a page's field nodes, so a form that changes at frame rate costs a few builds a second (#666). */
private val WIDGET_NODES_INTERVAL = 250.milliseconds
