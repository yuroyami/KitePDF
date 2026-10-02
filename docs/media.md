# Book audio and video

An EPUB can carry `<video>` and `<audio>` elements. The engine lays them out and paints each one's poster, or a plain grey box, but it plays nothing: a player is megabytes of codecs per platform, and most reading apps do not want it. The optional `kitepdf-media` artifact adds one. It plays each element on its box in `KiteDocView`, with [KitePlayer](https://github.com/yuroyami/KitePlayer), which decodes through FFmpeg.

Without `kitepdf-media`, nothing changes: the viewer shows the posters, and your app carries no player and no codec.

## Add it

```kotlin
commonMain.dependencies {
    implementation("io.github.yuroyami:kitepdf-compose-viewer:0.12.0")
    implementation("io.github.yuroyami:kitepdf-media:0.12.0")
}
```

It publishes the targets that KitePlayer's Compose video publishes:

| Target | Plays |
|---|---|
| Android | Yes, from `minSdk` 26, as KitePlayer requires |
| iOS (`iosArm64`, `iosSimulatorArm64`) | Yes |
| Desktop JVM | Yes |
| macOS native, JS, Wasm | No artifact. The viewer shows the posters |

KitePlayer has a few setup steps of its own on some platforms: linker flags for a static iOS framework, and two privacy manifest entries for any iOS app. Its [install guide](https://github.com/yuroyami/KitePlayer#install) lists them.

## Use it

Pass `KiteMediaOverlay` to the viewer's `pageOverlay`:

```kotlin
KiteDocView(
    state = rememberKiteDocViewState(book),
    pageOverlay = { KiteMediaOverlay() },
)
```

Each media element of a page then gets a player on its box, so it moves and zooms with the page:

- Before it starts, the element shows a play button over its poster. A tap starts it.
- A video plays in its box. With the element's `controls`, a bar along its bottom pauses and plays, and shows how far it is.
- An audio element shows that bar in its own box.
- `autoplay` starts the element by itself, muted, since browsers let only muted media start unasked. The first touch of its controls turns the sound on. `muted` mutes it from the start, and `loop` plays it again when it ends.
- The player plays the first of the element's sources that it can. When it can play none, the poster stays and the button goes.

An element makes no player until it starts, so a page of posters costs nothing, and the player closes when its page leaves the screen.

The overlay draws nothing on a page that is not an EPUB page, so a viewer that shows PDFs too can keep it on.

## Sources outside the book

A source can name a URL, such as `https://`, instead of a file inside the book. Such a source tells its server that the book was opened, and the book's author chose the server, so the overlay plays only the book's own files. Pass `allowRemote = true` to play URLs too:

```kotlin
pageOverlay = { KiteMediaOverlay(allowRemote = true) }
```

## Your own player settings

`newPlayer` makes the player of an element when it starts. It returns null where the platform cannot play, and the element then keeps its poster:

```kotlin
pageOverlay = {
    KiteMediaOverlay(newPlayer = { KitePlayerPlatform.createOrNull(PlayerConfig(/* ... */)) })
}
```

To place a player yourself, call `EpubMediaPlayer(media, book, modifier)` for one element of `EpubPage.media`, for example in your own `pageOverlay` with `Modifier.displayRect(media.rect)`.
