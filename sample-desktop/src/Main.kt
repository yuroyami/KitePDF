package sample

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "KitePDF Sample",
        state = rememberWindowState(size = DpSize(880.dp, 720.dp)),
    ) {
        App()
    }
}
