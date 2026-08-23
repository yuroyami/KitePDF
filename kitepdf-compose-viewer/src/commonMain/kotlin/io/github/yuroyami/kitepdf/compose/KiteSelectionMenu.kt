package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One action in a [KiteSelectionMenu]: a label, an optional leading icon slot,
 * and what to do with the selected text.
 *
 * @param label the action's text.
 * @param icon optional leading glyph, any composable (an `Icon`, an image, an
 *   emoji in a `BasicText`). Null renders the label alone.
 * @param clearsSelection whether the selection is dismissed after [onClick].
 *   True for terminal actions (copy, highlight); false for actions that keep
 *   the words selected (say, a share sheet the user may cancel).
 * @param onClick receives the selection the user acted on.
 */
public class KiteSelectionMenuItem(
    public val label: String,
    public val icon: (@Composable () -> Unit)? = null,
    public val clearsSelection: Boolean = true,
    public val onClick: (KiteTextSelection) -> Unit,
)

/** Default look of [KiteSelectionMenu]'s built-in chrome. */
public object KiteSelectionMenuDefaults {
    public val ContainerColor: Color = Color.White
    public val ContentColor: Color = Color(0xFF20242C)
    public val BorderColor: Color = Color(0x14000000)
    public val Shape: RoundedCornerShape = RoundedCornerShape(14.dp)
}

/**
 * The selection context menu: place it in [KiteDocView]'s `overlay` slot and it
 * appears whenever text is selected, listing [items] and, when
 * [highlightColors] is non-empty, a wrapping row of colour swatches.
 *
 * It waits for the finger to lift. While [KiteDocViewState.selectionInProgress] is
 * true the menu stays hidden, because a popup over the page mid-drag covers
 * the very words being chosen; it appears the moment the drag ends.
 *
 * Every visual layer is replaceable:
 *  - [container] swaps the card the menu sits in (the default is a white
 *    rounded card); its `content` argument is the laid-out menu body.
 *  - [itemContent] swaps how one action renders (the default is a pill chip).
 *  - [colorSwatch] swaps how one colour renders (the default is a circle).
 *  - For a completely different menu, skip this composable and build your own
 *    against [KiteDocViewState.selection] and [KiteDocViewState.selectionInProgress];
 *    this one is the default, not the contract.
 *
 * ```kotlin
 * KiteDocView(state, overlay = {
 *     KiteSelectionMenu(
 *         state = state,
 *         items = listOf(
 *             KiteSelectionMenuItem("Copy") { clipboard.setText(AnnotatedString(it.text)) },
 *             KiteSelectionMenuItem("Highlight") { marks.add(it) },
 *         ),
 *         highlightColors = listOf(Color.Yellow, Color.Green, Color.Cyan),
 *         onHighlightColorPicked = { sel, color -> marks.add(sel, color) },
 *     )
 * })
 * ```
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun BoxScope.KiteSelectionMenu(
    state: KiteDocViewState,
    items: List<KiteSelectionMenuItem>,
    modifier: Modifier = Modifier,
    highlightColors: List<Color> = emptyList(),
    onHighlightColorPicked: ((KiteTextSelection, Color) -> Unit)? = null,
    clearSelectionOnColorPick: Boolean = true,
    alignment: Alignment = Alignment.TopCenter,
    /** Show the menu even while the selection drag is still under the finger. */
    showWhileSelecting: Boolean = false,
    containerColor: Color = KiteSelectionMenuDefaults.ContainerColor,
    contentColor: Color = KiteSelectionMenuDefaults.ContentColor,
    container: (@Composable (selection: KiteTextSelection, content: @Composable () -> Unit) -> Unit)? = null,
    itemContent: (@Composable (item: KiteSelectionMenuItem, selection: KiteTextSelection) -> Unit)? = null,
    colorSwatch: (@Composable (color: Color, onPick: () -> Unit) -> Unit)? = null,
) {
    val selection = state.selection ?: return
    if (state.selectionInProgress && !showWhileSelecting) return

    val body: @Composable () -> Unit = {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (item in items) {
                    if (itemContent != null) {
                        itemContent(item, selection)
                    } else {
                        DefaultMenuChip(item, selection, state, contentColor)
                    }
                }
            }
            if (highlightColors.isNotEmpty() && onHighlightColorPicked != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (color in highlightColors) {
                        val pick = {
                            onHighlightColorPicked(selection, color)
                            if (clearSelectionOnColorPick) state.clearSelection()
                        }
                        if (colorSwatch != null) {
                            colorSwatch(color, pick)
                        } else {
                            DefaultColorSwatch(color, pick)
                        }
                    }
                }
            }
        }
    }

    Box(modifier = modifier.align(alignment).padding(10.dp)) {
        if (container != null) {
            container(selection, body)
        } else {
            Box(
                modifier = Modifier
                    .shadow(4.dp, KiteSelectionMenuDefaults.Shape)
                    .clip(KiteSelectionMenuDefaults.Shape)
                    .background(containerColor)
                    .border(1.dp, KiteSelectionMenuDefaults.BorderColor, KiteSelectionMenuDefaults.Shape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                body()
            }
        }
    }
}

@Composable
private fun DefaultMenuChip(
    item: KiteSelectionMenuItem,
    selection: KiteTextSelection,
    state: KiteDocViewState,
    contentColor: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(contentColor.copy(alpha = 0.06f))
            .clickable(role = Role.Button) {
                item.onClick(selection)
                if (item.clearsSelection) state.clearSelection()
            }
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        item.icon?.invoke()
        BasicText(
            text = item.label,
            style = TextStyle(
                color = contentColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun DefaultColorSwatch(color: Color, onPick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, Color(0x33000000), CircleShape)
            .clickable(role = Role.Button, onClick = onPick),
    )
}
