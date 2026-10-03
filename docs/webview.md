# Scripted books

An EPUB 3 can be scripted: a chapter can embed a quiz or a game in an `<iframe>` or an `<object>`, and a fixed-layout page can run a script of its own. KitePDF runs no JavaScript, so it lays these regions out and leaves them to a web engine: a frame's box stays empty, an object shows its fallback, and a fixed-layout page shows its markup without its scripts. The optional `kitepdf-webview` artifact hands exactly those regions to the platform's own web engine, in islands over the page, and leaves the rest of the book on the library's own rendering.

Without `kitepdf-webview`, nothing changes: the pages show what the library renders, and your app carries no web engine.

## Add it

```kotlin
commonMain.dependencies {
    implementation("io.github.yuroyami:kitepdf-compose-viewer:0.12.0")
    implementation("io.github.yuroyami:kitepdf-webview:0.12.0")
}
```

| Target | Web views |
|---|---|
| Desktop JVM | JavaFX's web view, drawn on the page. Add JavaFX for your platform, as below |
| Android (minSdk 24) | The system's `WebView`, over the page |
| Other targets | No artifact yet. The pages show the library's own rendering |

### JavaFX on the desktop

The desktop's web views are JavaFX's, in process. The artifact compiles against JavaFX and does not bring it, since each platform needs its own build. Add the five modules for yours, with the classifier `linux`, `linux-aarch64`, `mac`, `mac-aarch64` or `win`:

```kotlin
jvmMain.dependencies {
    for (module in listOf("base", "graphics", "controls", "media", "web")) {
        implementation("org.openjfx:javafx-$module:21.0.12:linux")
    }
}
```

A JDK that carries JavaFX, such as a full build of Liberica or Zulu, needs nothing more. The book's files reach the web view over HTTP on the loopback address, through the JDK's `jdk.httpserver` module, so a runtime image that `jlink` cuts down must keep that module. Without JavaFX, or without a display for it to start on, `isWebViewAvailable()` is false and the pages show the library's own rendering.

### Android

The web views are the system's own `WebView`. The book's files reach them under an `https` origin whose host name ends in `.invalid`, so it can never resolve: the web view's client answers every request for it from the book, and every other request with an empty 404 before it leaves the device. Your app needs no permission for this, not even `INTERNET`.

## Use it

Pass `KiteScriptOverlay` to the viewer's `pageOverlay`:

```kotlin
KiteDocView(
    state = rememberKiteDocViewState(book),
    pageOverlay = { KiteScriptOverlay() },
)
```

Each island of a page then gets a web view over its box:

- The document of an `<iframe>`, and of an `<object>` whose type is HTML or XHTML, over the element's box. The document lays out in the box's size in CSS pixels and scales with the page, so it zooms as the page does. An object taller than a page goes on from page to page, so it keeps its fallback.
- A whole scripted fixed-layout page: a page of a pre-paginated chapter that the manifest marks `scripted`, or that has a `<script>`. Its web view loads the chapter's own document at the size its viewport gives, and embedded documents run inside it.

Every file a web view asks for comes from the book, under an origin of the book's own: relative paths, paths from the root of the container, `fetch` and `XMLHttpRequest` all resolve inside the book. Every answer carries a content security policy that keeps the page to that origin, so an island loads nothing from the network: a picture or a script at another address is never asked for. An embedded document at an `https` address is an island only with `KiteScriptOverlay(allowRemote = true)`, and then it loads from the network on its own terms.

A link that a web view opens goes the way of a tapped link of the page: `onLinkTap` sees it first, and when that does not take it, a link inside the book moves the view to its target. A link to a place in the same document scrolls the island instead. A script that sets the page's location counts as a link too, and the island goes back to its own document.

The reader's mouse and keys reach the web view: a click gives it the keyboard, a wheel over an embedded document scrolls the document, and a wheel over a whole page scrolls the book, since the page has no more to show. On Android the touches over a web view go to it, as they go to any view.

A web view lives while its page is composed. When the viewer drops the page, scrolled far away or rebuilt for a new zoom, the island's state goes with it: the values of its forms and the state of its scripts start over when the page comes back.

## One island at a time

`page.webIslands()` lists the islands of an `EpubPage`, and `EpubWebView` shows one of them anywhere, for a layout of your own:

```kotlin
for (island in page.webIslands()) {
    EpubWebView(island, book, Modifier.size(300.dp, 200.dp), onLink = { href -> openLink(href) })
}
```

`onLink` gets a zip path with its fragment for a file of the book, as `EpubLink.href` gives one, or the address of anything else.

## Another engine on the desktop

On the desktop, `EpubWebView` draws whatever a `KiteDesktopWebEngine` hands it: frames of premultiplied pixels, and the links the page opens. It sends the engine the reader's mouse, wheel and keys in CSS pixels. `JavaFxWebEngine` is the default. To use Chromium instead, implement the interface over an embedder that renders offscreen, such as JCEF, and provide it:

```kotlin
CompositionLocalProvider(LocalKiteDesktopWebEngine provides myChromiumEngine) {
    KiteDocView(state, pageOverlay = { KiteScriptOverlay() })
}
```
