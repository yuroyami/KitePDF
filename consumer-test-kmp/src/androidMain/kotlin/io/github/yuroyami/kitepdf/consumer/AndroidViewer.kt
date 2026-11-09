package io.github.yuroyami.kitepdf.consumer

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.yuroyami.kitepdf.compose.KiteDocLayout
import io.github.yuroyami.kitepdf.compose.KiteDocView
import io.github.yuroyami.kitepdf.compose.rememberKiteDocViewState
import io.github.yuroyami.kitepdf.document.KiteDoc

/**
 * One page of a document in an Android screen, with the state-based viewer, so the Android
 * classes of the viewer's AAR and of Compose's are compiled against.
 */
@Composable
fun AndroidPageViewer(bytes: ByteArray, pageIndex: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val document = remember(bytes) { KiteDoc.open(bytes) }
    DisposableEffect(document) { onDispose { document.close() } }
    val state = rememberKiteDocViewState(document, initialPage = pageIndex)
    KiteDocView(
        state = state,
        modifier = modifier,
        layout = KiteDocLayout.SinglePage(pageIndex),
        onTap = { Toast.makeText(context, "Page ${pageIndex + 1} of ${document.pageCount}", Toast.LENGTH_SHORT).show() },
    )
}
