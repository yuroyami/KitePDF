package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yuroyami.kitepdf.core.xml.KiteXmlError
import io.github.yuroyami.kitepdf.epub.EpubPage
import kotlinx.coroutines.withContext

/**
 * A notice across the top of the first page of a chapter whose markup is not well-formed XML
 * (#517). EPUB Reading Systems 3.3 asks a reading system to treat such a document as in error;
 * the viewer still shows what it could lay out, below the notice.
 */
@Composable
internal fun KiteMarkupErrorNotice(state: KiteDocViewState, page: EpubPage, slot: Int, modifier: Modifier = Modifier) {
    if (state.locationOf(slot)?.page != 0) return
    val errors by produceState(emptyList<KiteXmlError>(), page.document, page.chapter) {
        // The check reads the chapter's markup again, so it runs off the main thread.
        value = withContext(kitepdfRasterDispatcher()) {
            runCatching { page.document.markupErrors(page.chapter) }.getOrDefault(emptyList())
        }
    }
    val first = errors.firstOrNull() ?: return
    val text = LocalKiteViewerStrings.current.markupErrors(first, errors.size)
    Box(
        modifier
            .fillMaxWidth()
            .background(NOTICE_BACKGROUND)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        BasicText(text, style = TextStyle(color = NOTICE_TEXT, fontSize = 12.sp))
    }
}

private val NOTICE_BACKGROUND = Color(0xFFFDECEA)
private val NOTICE_TEXT = Color(0xFF8A1C12)
