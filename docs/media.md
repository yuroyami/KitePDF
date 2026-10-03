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
- The bar of a video ends with a full-screen button. Full screen shows the same player over the whole window, so the video goes on without a break, and the button, a back gesture or Escape brings it back to its box.
- An audio element shows that bar in its own box.
- `autoplay` starts the element by itself, muted, since browsers let only muted media start unasked. The first touch of its controls turns the sound on. `muted` mutes it from the start, and `loop` plays it again when it ends.
- The player plays the first of the element's sources that it can. When it can play none, the poster stays and the button goes.

An element makes no player until it starts, so a page of posters costs nothing, and the player closes when its page leaves the screen.

On a phone, full screen does a little more:

- On Android it hides the system bars, and a swipe from an edge shows them for a moment. A landscape video turns the screen to landscape, and back when full screen ends, but only in an activity that handles the turn itself. Declare `android:configChanges="orientation|screenSize"` on the activity for that: Android recreates any other activity when the screen turns, which would close the player.
- On iOS 16 and later, a landscape video asks for landscape, and for the orientation it found when full screen ends. The app's supported orientations must include landscape.

The overlay draws nothing on a page that is not an EPUB page, so a viewer that shows PDFs too can keep it on.

## Sources outside the book

A source can name an `https` URL instead of a file inside the book. Such a source tells its server that the book was opened, and the book's author chose the server, so the overlay plays only the book's own files. Pass `allowRemote = true` to play `https` URLs too:

```kotlin
pageOverlay = { KiteMediaOverlay(allowRemote = true) }
```

A source of any other scheme never plays. A plain `http` stream can be watched and changed on its way, and a `file` URL would reach files on the device, which EPUB Reading Systems 3.3 forbids (3.3 and 3.5).

## Read a book aloud

A book with media overlays pairs each piece of its text with a clip of narration. `KiteReadAloud` reads it with the same player and follows the text in the viewer. Place it next to the viewer and switch it from your own controls:

```kotlin
val state = rememberKiteDocViewState(book)
var playing by remember { mutableStateOf(false) }
KiteDocView(state)
KiteReadAloud(state, playing, onFinished = { playing = false })
```

- It starts at the first clip whose text is on the reader's page or after it, and goes on through the next chapters that have an overlay (EPUB Reading Systems 3.3, 9.1).
- Each clip plays from its `clipBegin` to its `clipEnd`, or to the end of its file without one (9.2.2). Clips that follow on in the same file play without a seek.
- The text of the clip being read gets one entry in `state.highlights`, with the id `READ_ALOUD_HIGHLIGHT_ID`, next to your own entries. `color` sets its fill.
- When the reading reaches text on another page, the viewer turns to it.
- `playing = false` pauses on the clip, and `true` goes on from there.
- A clip without audio, or whose audio the player cannot open, is skipped.

The book's active class shows as that highlight and is never added to the element, since a class could change the element's style and lay the chapter out again. `onClip` reports each clip as it starts, and null at the end. `onFinished` is called past the last clip, and at once for a book with no narration from the reader's page on. Leaving the composition stops the reading and closes its player.

## Your own player settings

`newPlayer` makes the player of an element when it starts, and of a reading with `KiteReadAloud`. It returns null where the platform cannot play, and the element then keeps its poster:

```kotlin
pageOverlay = {
    KiteMediaOverlay(newPlayer = { KitePlayerPlatform.createOrNull(PlayerConfig(/* ... */)) })
}
```

To place a player yourself, call `EpubMediaPlayer(media, book, modifier)` for one element of `EpubPage.media`, for example in your own `pageOverlay` with `Modifier.displayRect(media.rect)`.
