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
- A tap on the line of the bar seeks there, and a drag scrubs. A screen reader moves the line as a slider, five seconds a step. The elapsed and the total time stand on each side of the line.
- The bar has a mute button, and a speed menu from 0.5× to 2×. A change of speed keeps the pitch. Volume stays with the device's own buttons.
- Where the box is narrow, the times leave the bar first, then the speed.
- The bar of a video ends with a full-screen button. Full screen shows the same player over the whole window, so the video goes on without a break, and the button, a back gesture or Escape brings it back to its box.
- An audio element shows that bar in its own box.
- `autoplay` starts the element by itself, muted, since browsers let only muted media start unasked. The first touch of its controls turns the sound on. `muted` mutes it from the start, and `loop` plays it again when it ends.
- The player plays the first of the element's sources that it can. When it can play none, the poster stays and the button goes.

An element makes no player until it starts, so a page of posters costs nothing, and the player closes when its page leaves the screen.

On a phone, full screen does a little more:

- On Android it hides the system bars, and a swipe from an edge shows them for a moment. A landscape video turns the screen to landscape, and back when full screen ends, but only in an activity that handles the turn itself. Declare `android:configChanges="orientation|screenSize"` on the activity for that: Android recreates any other activity when the screen turns, which would close the player.
- On iOS 16 and later, a landscape video asks for landscape, and for the orientation it found when full screen ends. The app's supported orientations must include landscape.

The overlay draws nothing on a page that is not an EPUB page, so a viewer that shows PDFs too can keep it on.

### Translate the controls

The controls say English words to a screen reader. Pass `KiteMediaLabels` to say others:

```kotlin
pageOverlay = {
    KiteMediaOverlay(labels = KiteMediaLabels(play = "Lire", pause = "Pause", mute = "Couper le son"))
}
```

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
- The element of the clip being read gets the book's `media:active-class`, and its chapter's root element gets the `media:playback-active-class` while the reading plays (EPUB Reading Systems 3.3, 9.2.3).
- When the book's style rules do not style the active class, the text of the clip gets one entry in `state.highlights` instead, with the id `READ_ALOUD_HIGHLIGHT_ID`, next to your own entries. `color` sets its fill.
- When the reading reaches text on another page, the viewer turns to it.
- `playing = false` pauses on the clip, and `true` goes on from there.
- A clip without audio goes to `speak`, when you give one, and is skipped otherwise.
- A clip whose audio the player cannot open is skipped.

A class can change the style of an element, so a chapter whose rules use one of the two classes is laid out again for each clip. Pass `bookStyles = false` to keep every chapter as it is and mark the text with the highlight only.

`onClip` reports each clip as it starts, and null at the end. `onFinished` is called past the last clip, and at once for a book with no narration from the reader's page on. Leaving the composition stops the reading, closes its player and takes the classes off.

### Speak clips without audio

Some books leave the audio out of their overlays and expect the reading system to speak the text (EPUB Reading Systems 3.3, the text-to-speech rules for media overlays). `speak` hands each such clip to your speech engine. It gets the clip and the text of its element as `KiteReadingItem` values, with the pronunciation hints of the book, and returns once it has said it all:

```kotlin
KiteReadAloud(
    state, playing,
    speak = { _, text -> for (item in text) mySpeechEngine.say(item.text, phoneme = item.pronunciation) },
)
```

A pause or a move by the reader cancels `speak`, so stop the engine when its coroutine is cancelled. The reading speaks the clip again from its start when it goes on. `EpubDocument.readingOrderOf` gives the same text for any element.

## Your own player settings

`newPlayer` makes the player of an element when it starts, and of a reading with `KiteReadAloud`. It returns null where the platform cannot play, and the element then keeps its poster:

```kotlin
pageOverlay = {
    KiteMediaOverlay(newPlayer = { KitePlayerPlatform.createOrNull(PlayerConfig(/* ... */)) })
}
```

To place a player yourself, call `EpubMediaPlayer(media, book, modifier)` for one element of `EpubPage.media`, for example in your own `pageOverlay` with `Modifier.displayRect(media.rect)`.
