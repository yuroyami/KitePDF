package io.github.yuroyami.kitepdf.media

import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackException
import io.github.yuroyami.kiteplayer.from
import io.github.yuroyami.kiteplayer.ofBytes
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubMedia

/**
 * The items [media] can play, in the order the element lists its sources (HTML, 4.8.11.5). A source
 * inside the book reads its zip entry once, as an array that every open of the item seeks in
 * without a copy. A source with a URL scheme plays only when [allowRemote] is true, because a
 * remote source tells its server that the book was opened, and the book's author chose the server.
 * A source whose entry is missing has no item.
 */
internal fun mediaItems(media: EpubMedia, document: EpubDocument, allowRemote: Boolean): Sequence<MediaItem> =
    media.sources.asSequence().mapNotNull { source ->
        if (hasScheme(source.href)) {
            if (allowRemote) MediaItem(source.href) else null
        } else {
            document.resource(source.href)?.let { MediaItem.from(MediaIo.ofBytes(it), label = source.href) }
        }
    }

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
