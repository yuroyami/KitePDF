package io.github.yuroyami.kitepdf.compose

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
)

/** The viewer's tap, which a link or a field node runs at its centre, as a finger would. */
internal val LocalViewerTap = compositionLocalOf<((androidx.compose.ui.geometry.Offset) -> Unit)?> { null }

/**
 * The nodes of [page]: its reading order, then its links, then its form fields when [formState]
 * holds them. Reads the page's text, so a caller runs it off the main thread.
 */
internal fun accessNodes(page: KitePage, formState: PdfFormState?, strings: KiteViewerStrings): List<PageAccessNode> {
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
    if (page is PdfPage && formState != null) {
        for (annotation in runCatching { page.annotations }.getOrDefault(emptyList())) {
            if (annotation.subtype != PdfAnnotation.Subtype.Widget) continue
            val r = annotation.rect
            // The widget with its field, as a tap at its centre finds it, and with its live visibility.
            val hit = runCatching { page.widgetAt((r.left + r.right) / 2, (r.bottom + r.top) / 2, formState) }.getOrNull() ?: continue
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
            out += PageAccessNode(page.pageToDisplay(r), name, kind, state = value?.takeIf { checked == null }, checked = checked, enabled = !formState.isReadOnly(field.fullyQualifiedName))
        }
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
    var nodes by remember(page) { mutableStateOf<List<PageAccessNode>>(emptyList()) }
    val shown by remember(state, pageIndex) { derivedStateOf { state.inZoomedView(pageIndex) && state.pageGeometry.containsKey(pageIndex) } }
    LaunchedEffect(page, formState, strings, state.formRevision) {
        // The text is read once the page shows and the view rests, not for every page scrolled past.
        snapshotFlow { shown && state.adapter?.isScrollInProgress != true }.first { it }
        val built = try {
            withContext(kitepdfRasterDispatcher()) { accessNodes(page, formState, strings) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            io.github.yuroyami.kitepdf.core.kiteWarn { "accessibility: the content of page $pageIndex cannot be read: ${failure.message}" }
            emptyList()
        }
        // The nodes are state that composition reads, so they are written on its thread (#443).
        backOnComposeThread()
        nodes = built
    }
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
            node.state?.let { stateDescription = it }
            onClick { act() }
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
            onClick { act() }
        }
        AccessKind.RADIO -> {
            contentDescription = node.label
            role = Role.RadioButton
            selected = node.checked == true
            onClick { act() }
        }
        AccessKind.BUTTON -> {
            contentDescription = node.label
            role = Role.Button
            onClick { act() }
        }
    }
}

/** The `/Ff` flag of a push button (ISO 32000-1, Table 226). */
private const val PUSH_BUTTON = 1 shl 16

/** The `/Ff` flag of a radio button (ISO 32000-1, Table 226). */
private const val RADIO = 1 shl 15
