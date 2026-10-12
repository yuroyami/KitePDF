# EPUB audio and video

An EPUB can have `<video>` and `<audio>` elements. The engine lays them out and paints the poster of each one (or a plain gray box), but it plays nothing, because a player needs megabytes of codecs on each platform and most reading apps do not want that. The optional `kitepdf-media` artifact adds a player. It plays each element on its box in `KiteDocView`, with [KitePlayer](https://github.com/yuroyami/KitePlayer), which decodes through FFmpeg.

Without `kitepdf-media`, nothing changes: the viewer shows the posters, and your app has no player and no codec.

## Add it

```kotlin
commonMain.dependencies {
    implementation("io.github.yuroyami:kitepdf-compose-viewer:0.13.0")
    implementation("io.github.yuroyami:kitepdf-media:0.13.0")
}
```

`kitepdf-media` has the targets of KitePlayer's Compose video:

| Target | Plays |
| --- | --- |
| Android | Yes, from `minSdk` 26, as KitePlayer requires. The app must compile against API 37.2 or newer (`compileSdk`). |
| iOS (`iosArm64`, `iosSimulatorArm64`) | Yes |
| Desktop JVM | Yes |
| macOS native, JS, Wasm | There is no artifact. The viewer shows the posters. |

!!! warning
    On iOS, KitePlayer needs linker flags for a static framework, and every iOS app needs two privacy manifest entries for it. Without the linker flags, the build fails. Without the manifest entries, Apple flags the app during review. The [KitePlayer install guide](https://github.com/yuroyami/KitePlayer#install) lists both.

## Use it

Pass `KiteMediaOverlay` to the viewer's `pageOverlay`:

```kotlin
KiteDocView(
    state = rememberKiteDocViewState(book),
    pageOverlay = { KiteMediaOverlay() },
)
```

Each media element of a page then gets a player on its box, so the player moves and zooms with the page:

- Before it starts, the element shows a play button over its poster. A tap starts it.
- A video plays in its box. When the element has `controls`, a bar along the bottom of the box has a play and pause button and a progress line.
- A tap on the progress line jumps to that point, and a drag moves through the media. A screen reader moves the line as a slider, five seconds for each step. The elapsed time and the total time show on each side of the line.
- The bar has a mute button and a speed menu from 0.5× to 2×. A change of speed keeps the pitch. The device's own buttons control the volume.
- A video loads its subtitle and caption tracks (the `<track>` elements of kind `subtitles` or `captions`). The track that the element marks `default` shows over the picture at once. A menu on the bar selects another track, or none.
- In a narrow box, the bar hides the times first, then the two menus.
- The bar of a video ends with a full-screen button. Full screen shows the same player over the whole window, so the video does not stop. The button, a back gesture or Escape returns the video to its box.
- An audio element shows the same bar in its own box.
- `autoplay` starts the element by itself, but muted, because browsers start only muted media without a tap. The first touch on its controls turns the sound on. `muted` mutes the element from the start, and `loop` plays it again when it ends.
- The player plays the first source of the element that it can play. When it can play none, the poster stays and the play button goes away.

An element makes no player until it starts, so a page of posters costs nothing. The player closes when its page leaves the screen.

### Elements share a session

All elements share one `KiteMediaSession`:

- An element keeps its place while the book is open. When its page comes back, the element shows where it stopped, paused, with the sound and the speed that it had.
- One element plays at a time. When one starts, any other element that plays pauses.
- When the app leaves the screen on Android or iOS, a video pauses, and by default an audio element pauses too. It plays again when the app comes back.

The overlay uses `KiteMediaSession.Default` unless you pass a session of your own. To keep the narration of a book playing in the background, make a session with `BackgroundPolicy.ContinueAudio`:

```kotlin
val media = remember { KiteMediaSession(audioInBackground = BackgroundPolicy.ContinueAudio) }
KiteDocView(state, pageOverlay = { KiteMediaOverlay(session = media) })
```

On Android, sound in the background also needs a media notification that keeps the process alive. KitePlayer's `attachMediaNotification` makes one. A desktop window keeps playing when it is hidden, as desktop players do.

On a phone, full screen also changes the screen:

- On Android, full screen hides the system bars, and a swipe from an edge shows them for a moment. A landscape video turns the screen to landscape, and back when full screen ends. This works only in an activity that handles the turn itself, because Android recreates any other activity when the screen turns, which closes the player. Declare `android:configChanges="orientation|screenSize"` on the activity.
- On iOS 16 and later, a landscape video asks for landscape, and asks for the earlier orientation when full screen ends. The supported orientations of the app must include landscape.

The overlay draws nothing on a page that is not an EPUB page, so a viewer that also shows PDFs can keep it on.

### Translate the controls

The controls give English names to a screen reader. Pass `KiteMediaLabels` for other words:

```kotlin
pageOverlay = {
    KiteMediaOverlay(labels = KiteMediaLabels(play = "Lire", pause = "Pause", mute = "Couper le son"))
}
```

`KiteMediaLabels` also has `unmute`, `seek`, `speed`, `fullScreen`, `exitFullScreen`, `subtitles` and `subtitlesOff`.

## Sources outside the book

A source can name an `https` URL instead of a file in the book. A request to that URL tells its server that the reader opened the book, and the book's author chose that server. So the overlay plays only the book's own files unless you pass `allowRemote = true`:

```kotlin
pageOverlay = { KiteMediaOverlay(allowRemote = true) }
```

A source with any other scheme never plays. Someone on the network can watch and change a plain `http` stream, and a `file` URL would reach files on the device, which EPUB Reading Systems 3.3 does not allow (sections 3.3 and 3.5).

The file of a subtitle or caption track follows the same rules as a source.

## Read a book aloud

A book with media overlays pairs each piece of its text with a clip of narration. `KiteReadAloud` reads the book with the same player and follows the text in the viewer. Put it next to the viewer and turn it on and off from your own controls:

```kotlin
val state = rememberKiteDocViewState(book)
var playing by remember { mutableStateOf(false) }
KiteDocView(state)
KiteReadAloud(state, playing, onFinished = { playing = false })
```

- The reading starts at the first clip whose text is on the reader's page or after it. It continues through the next chapters that have an overlay (EPUB Reading Systems 3.3, 9.1).
- Each clip plays from its `clipBegin` to its `clipEnd`, or to the end of its file when it has no `clipEnd` (9.2.2). Clips that follow each other in the same file play without a seek.
- The element of the current clip gets the book's `media:active-class`. The root element of its chapter gets the `media:playback-active-class` while the reading plays (EPUB Reading Systems 3.3, 9.2.3).
- When the style rules of the book do not style the active class, the text of the clip gets one entry in `state.highlights` instead, with the id `READ_ALOUD_HIGHLIGHT_ID`, next to your own entries. `color` sets its fill.
- When the reading gets to text on another page, the viewer turns to that page.
- `playing = false` pauses on the current clip, and `true` continues from there.
- A clip without audio goes to `speak` when you give one. Otherwise the reading skips it.
- The reading skips a clip whose audio the player cannot open.

A class can change the style of an element, so KitePDF lays out a chapter whose rules use one of the two classes again for each clip. Pass `bookStyles = false` to keep every chapter as it is and mark the text with the highlight only.

`onClip` gives each clip when it starts, and null at the end. `onFinished` runs after the last clip, and at once for a book with no narration from the reader's page on. When `KiteReadAloud` leaves the composition, the reading stops, its player closes and the classes go away.

### Speak clips without audio

Some books leave the audio out of their overlays and expect the reading system to speak the text (EPUB Reading Systems 3.3, the text-to-speech rules for media overlays). `speak` gives each such clip to your speech engine. It gets the clip and the text of its element as `KiteReadingItem` values, with the pronunciation hints of the book, and returns after it has said all of the text:

```kotlin
KiteReadAloud(
    state,
    playing,
    speak = { _, text ->
        for (item in text) mySpeechEngine.say(item.text, phoneme = item.pronunciation)
    },
)
```

A pause, or a move by the reader, cancels `speak`, so stop the engine when its coroutine is cancelled. When the reading continues, it speaks the clip again from its start. `EpubDocument.readingOrderOf` gives the same text for any element.

## Your own player settings

`newPlayer` makes the player of an element when the element starts, and the player of `KiteReadAloud`. It returns null where the platform cannot play, and the element then keeps its poster:

```kotlin
pageOverlay = {
    KiteMediaOverlay(
        newPlayer = {
            if (KitePlayer.isAvailable) KitePlayer(PlayerConfig { }) else null
        },
    )
}
```

To place a player yourself, call `EpubMediaPlayer(media, book, modifier)` for one element of `EpubPage.media`, for example in your own `pageOverlay` with `Modifier.displayRect(media.rect)`.
