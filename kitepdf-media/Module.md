# Module kitepdf-media

Optional. Plays the audio and video elements of an EPUB page in `KiteDocView`.

The engine and viewer artifacts carry no player and no codec, and this one exists
so that stays true: it is the single place KitePlayer, and FFmpeg through it, enter
the build. Add it only if your app plays the media of a book, and pass
`KiteMediaOverlay` to the viewer's `pageOverlay`. Without it, the viewer shows the
poster of each element.

It publishes the targets that KitePlayer's Compose video publishes: Android
(`minSdk` 26), iOS and the desktop JVM.
