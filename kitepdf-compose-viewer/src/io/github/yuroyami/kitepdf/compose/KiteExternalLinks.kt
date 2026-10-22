package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What [KiteDocView] does with a link to an address outside the document, such as a web page or
 * a mail address, when `onLinkTap` does not take it (#519).
 *
 * EPUB Reading Systems 3.3 asks a reading system to open such a link in the browser, or in the
 * mail app for `mailto:`, after it asks the reader. The viewer does that for every format: it
 * asks with a small prompt in the view, and then opens the address through the platform's
 * `LocalUriHandler`. Translate the prompt through [LocalKiteViewerStrings]. Pass
 * `externalLinks = null` to [KiteDocView] and the viewer opens nothing.
 *
 * @param schemes the schemes the viewer opens, in lower case. A document can name any scheme,
 *   `file:`, `intent:` and `javascript:` included, so the viewer ignores every scheme not in
 *   this set.
 * @param askFirst whether the viewer asks the reader before it opens an address.
 */
@Immutable
public data class KiteExternalLinks(
    val schemes: Set<String> = setOf("http", "https", "mailto"),
    val askFirst: Boolean = true,
)

/** The lower-case scheme of [uri], or null when it has none. */
internal fun schemeOf(uri: String): String? =
    uri.trim().takeIf { hasScheme(it) }?.substringBefore(':')?.lowercase()

/**
 * Asks the reader about [uri], or opens it, when it leads out of the document through a scheme
 * that [KiteDocViewState.externalLinks] allows. Returns true when the viewer took the link.
 */
internal fun KiteDocViewState.offerExternalLink(uri: String?): Boolean {
    val links = externalLinks ?: return false
    val address = uri?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    if (schemeOf(address) !in links.schemes) return false
    if (links.askFirst) pendingExternalLink = address else openExternalLink(address)
    return true
}

/** Opens [uri] through the platform. A platform that cannot open it logs a warning. */
internal fun KiteDocViewState.openExternalLink(uri: String) {
    val handler: UriHandler = uriHandler ?: return
    try {
        handler.openUri(uri)
    } catch (failure: Throwable) {
        io.github.yuroyami.kitepdf.core.kiteWarn { "link: the platform cannot open $uri: ${failure.message}" }
    }
}

/** The prompt that asks the reader before the viewer opens an address outside the document (#519). */
@Composable
internal fun KiteExternalLinkPrompt(state: KiteDocViewState) {
    val address = state.pendingExternalLink ?: return
    val strings = LocalKiteViewerStrings.current
    val focus = remember(address) { FocusRequester() }
    fun cancel() { state.pendingExternalLink = null }
    fun open() {
        state.pendingExternalLink = null
        state.openExternalLink(address)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            // A tap beside the prompt closes it, and no tap reaches the pages below.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { cancel() }
            .focusRequester(focus)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.Escape, Key.Back -> { cancel(); true }
                    Key.Enter, Key.NumPadEnter -> { open(); true }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(max = 360.dp)
                .background(Color.White, RoundedCornerShape(8.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .semantics { paneTitle = strings.openLinkQuestion }
                .padding(16.dp),
        ) {
            BasicText(strings.openLinkQuestion, style = TextStyle(color = PROMPT_TEXT, fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(8.dp))
            BasicText(address, style = TextStyle(color = PROMPT_TEXT, fontSize = 14.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PromptButton(strings.cancel) { cancel() }
                PromptButton(strings.openLink) { open() }
            }
        }
    }
    LaunchedEffect(focus) { runCatching { focus.requestFocus() } }
}

@Composable
private fun PromptButton(label: String, onClick: () -> Unit) {
    BasicText(
        label,
        style = TextStyle(color = PROMPT_ACCENT, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

private val PROMPT_TEXT = Color(0xFF1F1F1F)
private val PROMPT_ACCENT = Color(0xFF1A5FB4)
