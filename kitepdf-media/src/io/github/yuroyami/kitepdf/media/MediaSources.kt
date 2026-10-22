package io.github.yuroyami.kitepdf.media

import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackException
import io.github.yuroyami.kiteplayer.SubtitleSource
import io.github.yuroyami.kiteplayer.from
import io.github.yuroyami.kiteplayer.ofBytes
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubMedia
import io.github.yuroyami.kitepdf.epub.EpubMediaKind
import io.github.yuroyami.kitepdf.epub.EpubTrackKind

/**
 * The items [media] can play, in the order the element lists its sources (HTML, 4.8.11.5). A source
 * inside the book reads its zip entry once, as an array that every open of the item seeks in
 * without a copy. A source with a URL scheme plays only when it is `https` and [allowRemote] is
 * true, because a remote source tells its server that the book was opened, and the book's author
 * chose the server. A source of any other scheme never plays: a plain `http` stream can be watched
 * and changed on its way, and a `file` URL would reach files on the device, which a reading system
 * must prevent (EPUB Reading Systems 3.3, 3.3 and 3.5). A source whose entry is missing has no item.
 * Every item carries the element's [subtitleSources].
 */
internal fun mediaItems(media: EpubMedia, document: EpubDocument, allowRemote: Boolean): Sequence<MediaItem> {
    val subtitles by lazy { subtitleSources(media, document, allowRemote) }
    return media.sources.asSequence().mapNotNull { source ->
        when {
            isHttps(source.href) -> if (allowRemote) MediaItem(source.href) else null
            hasScheme(source.href) -> null
            else -> bookItem(document, source.href)
        }?.copy(externalSubtitles = subtitles)
    }
}

/**
 * The subtitle and caption tracks of a video [media] as subtitle files for the player, in document
 * order (#483). An audio element has no picture to show them on, so it gets none, as in a browser. The first track that the element marks `default` shows at open, as the HTML rules for automatic text track selection
 * asks. The other kinds of track hold no text to show over the picture. A track follows the
 * rules of a source: a remote one loads only over `https` while [allowRemote] is true, and a
 * track whose entry is missing has no file.
 */
internal fun subtitleSources(media: EpubMedia, document: EpubDocument, allowRemote: Boolean): List<SubtitleSource> {
    if (media.kind != EpubMediaKind.VIDEO) return emptyList()
    val shown = media.tracks.filter { it.kind == EpubTrackKind.SUBTITLES || it.kind == EpubTrackKind.CAPTIONS }
    val chosen = shown.firstOrNull { it.isDefault }
    return shown.mapNotNull { track ->
        val io = when {
            isHttps(track.href) -> if (allowRemote) null else return@mapNotNull null
            hasScheme(track.href) -> return@mapNotNull null
            else -> {
                val bytes = document.resource(track.href) ?: return@mapNotNull null
                MediaIo.ofBytes(bytes)
            }
        }
        SubtitleSource(track.href, title = track.label, language = track.language, selectImmediately = track === chosen, io = io)
    }
}

/** The file [href] of [document]'s zip as an item, read once into an array that every open seeks in, or null without it. */
internal fun bookItem(document: EpubDocument, href: String): MediaItem? =
    document.resource(href)?.let { MediaItem.from(MediaIo.ofBytes(it), label = href) }

/** True when [href] is an absolute `https` URL, the one scheme a remote source plays over (EPUB Reading Systems 3.3, 3.3). */
internal fun isHttps(href: String): Boolean = href.startsWith("https://", ignoreCase = true)

/** True when [href] starts with a URL scheme, such as `https:` (RFC 3986, 3.1), rather than a zip path. */
internal fun hasScheme(href: String): Boolean {
    val colon = href.indexOf(':')
    if (colon <= 0) return false
    return href[0].isLetter() && href.substring(0, colon).all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
}

/**
 * Opens the first item of [items] that this player can play, and returns it, or null when none
 * opens. The player reports a source it cannot play by failing its open, the way a browser skips
 * to the next `<source>` (HTML, 4.8.11.5), and an open is legal again after a failed one.
 */
internal suspend fun KitePlayer.openFirstPlayable(items: Sequence<MediaItem>): MediaItem? {
    for (item in items) {
        try {
            open(item)
            return item
        } catch (failure: PlaybackException) {
            // The next source, as a browser does.
        }
    }
    return null
}
