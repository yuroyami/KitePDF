# Module kitepdf-media

Optional. Plays the audio and video elements of an EPUB page in `KiteDocView`, and reads a book aloud with its media overlays.

This is the only artifact that brings KitePlayer, and FFmpeg through it, into the build. So the other artifacts carry no player and no codec. Add it only when your app plays the media of a book:

- Pass `KiteMediaOverlay` to the `pageOverlay` of the viewer. Without it, the viewer shows the poster of each element.
- Place `KiteReadAloud` next to the viewer. It plays the narration of a book and highlights the text that it reads.

It builds for the targets of KitePlayer: Android (`minSdk` 26), iOS and the desktop JVM. See [EPUB audio and video](https://yuroyami.github.io/KitePDF/media/).
