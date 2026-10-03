# Module kitepdf-webview

Optional. Shows the scripted content of an EPUB in the platform's own web engine, over
`KiteDocView`: the documents of its inline frames and HTML objects, and its scripted
fixed-layout pages. The rest of each page stays on the library's own rendering.

The engine and viewer artifacts run no script, and this one exists so that stays true: it
is the single place a web engine enters the build. Pass `KiteScriptOverlay` to the viewer's
`pageOverlay`. Every file a web view asks for comes from the book, under an origin of the
book's own, and nothing comes from the network. A link that a web view opens goes through
the view's `onLinkTap`, as a tapped link of the page does.

On Android the web views are the system's `WebView`, answered from the book by its client.
On the desktop JVM they are JavaFX's, drawn offscreen and placed on the page, so an app adds
JavaFX for its platform; `KiteDesktopWebEngine` lets it plug in another engine.
