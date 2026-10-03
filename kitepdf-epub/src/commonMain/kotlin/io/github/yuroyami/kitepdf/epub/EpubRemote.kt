package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLock
import io.github.yuroyami.kitepdf.core.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Fetches a resource that a book names by an absolute `https` URL instead of a file in its
 * container, such as a font or an image on a web server (EPUB 3.3, 3.6). Reading systems should
 * support such remote resources, and should load them over `https` only (EPUB Reading Systems
 * 3.3, 3.3), so the book never passes another scheme here: an `http` URL is refused before it
 * reaches the fetcher (#38).
 *
 * Set one on [EpubSettings.resourceFetcher]. `kitepdf-net` ships one on a Ktor client. A fetch
 * tells the URL's server that the book was opened, and the book's author chose that server, so
 * fetching is opt-in: without a fetcher, a remote image takes its manifest fallback, else keeps
 * the room its width and height give, and a remote font takes the next source of its rule.
 *
 * The book calls [fetch] once per URL, on [Dispatchers.Default], however many documents and
 * chapters name it, and keeps the bytes it returns for its whole life, so a chapter that lays
 * out again finds what it found the first time.
 */
public fun interface EpubResourceFetcher {
    /**
     * The bytes at [url], an absolute `https` URL, or null when they cannot be had. A throw counts
     * as null. Cap the size: the book holds every resource in memory.
     */
    public suspend fun fetch(url: String): ByteArray?
}

/** True for an absolute `http` or `https` URL: a resource outside the container (EPUB 3.3, 3.6). */
internal fun isRemoteUrl(href: String): Boolean =
    href.startsWith("https://", ignoreCase = true) || href.startsWith("http://", ignoreCase = true)

/** True for an `https` URL, the only scheme a remote resource is fetched over (EPUB Reading Systems 3.3, 3.3). */
internal fun isHttpsUrl(href: String): Boolean = href.startsWith("https://", ignoreCase = true)

/** True when [path] names an SVG file: a remote URL by its path without its query, as it is fetched with one. */
internal fun namesSvg(path: String): Boolean =
    (if (isRemoteUrl(path)) path.substringBefore('?') else path).endsWith(".svg", ignoreCase = true)

/** The remote URLs that one chapter names (#38): [layout] holds those its layout needs, and [all] every one. */
internal class RemoteRefs(val layout: List<String>, val all: List<String>)

/**
 * The bytes of a book's remote resources, by URL, shared by every [EpubDocument] over one parse
 * (#38). Bytes are never dropped once they land: a chapter laid out again after the layout budget
 * dropped it must find what its first layout found. The store holds [MAX_REMOTE_BYTES] at most,
 * and a resource past that counts as failed, as does one the fetcher returns no bytes for. A
 * failed URL is not fetched again.
 *
 * Fetches run in the store's own scope, so a caller that stops waiting, as a viewer does at its
 * time limit, does not cancel them, and their bytes land for the next paint.
 */
internal class RemoteResources(chapters: Int) {

    private val lock = KiteLock()
    private val landedBytes = HashMap<String, ByteArray>()
    private val failed = HashSet<String>()
    private val inFlight = HashMap<String, Deferred<ByteArray?>>()
    private var held = 0L

    /** The chapters that painted each URL before it landed, which paint again once it has. */
    private val painters = HashMap<String, HashSet<Int>>()
    private val versions = IntArray(chapters)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val landed = MutableStateFlow(0)

    /** How many resources have landed. */
    val arrivals: StateFlow<Int> = landed.asStateFlow()

    /** The bytes of [url], or null until they land. */
    fun bytes(url: String): ByteArray? = lock.withLock { landedBytes[url] }

    /** True when [url] may still land: it is https, and has neither landed nor failed. */
    fun mayLand(url: String): Boolean = isHttpsUrl(url) && lock.withLock { url !in landedBytes && url !in failed }

    /**
     * Starts the fetch of [url] through [fetcher] unless it has landed, failed or started, and
     * returns what to await for its bytes. Null for a URL that is not https or has failed.
     */
    fun request(url: String, fetcher: EpubResourceFetcher): Deferred<ByteArray?>? {
        if (!isHttpsUrl(url)) return null
        val fetch = lock.withLock {
            landedBytes[url]?.let { return CompletableDeferred(it) }
            if (url in failed) return null
            inFlight[url]?.let { return it }
            // Started lazily, so it is in the table before it can finish and leave it.
            scope.async(start = CoroutineStart.LAZY) { fetchOne(url, fetcher) }.also { inFlight[url] = it }
        }
        fetch.start()
        return fetch
    }

    private suspend fun fetchOne(url: String, fetcher: EpubResourceFetcher): ByteArray? {
        var got: ByteArray? = null
        try {
            got = try {
                fetcher.fetch(url)
            } catch (failure: Throwable) {
                // The fetcher's own cancellation, such as its timeout, is a failed fetch; the store's is not.
                if (failure is CancellationException) currentCoroutineContext().ensureActive()
                null
            }
        } finally {
            lock.withLock {
                inFlight.remove(url)
                val bytes = got
                if (bytes == null || bytes.isEmpty() || held + bytes.size > MAX_REMOTE_BYTES) {
                    failed += url
                    got = null
                } else {
                    landedBytes[url] = bytes
                    held += bytes.size
                    painters.remove(url)?.forEach { versions[it]++ }
                }
            }
        }
        if (got != null) landed.update { it + 1 }
        return got
    }

    /** Records that [chapter] painted [url], so that [versionOf] it moves when [url] lands. */
    fun notePainter(url: String, chapter: Int) {
        if (chapter !in versions.indices) return
        lock.withLock { if (url !in landedBytes) painters.getOrPut(url) { HashSet() }.add(chapter) }
    }

    /** How many of the resources that [chapter] painted before they landed have landed since. */
    fun versionOf(chapter: Int): Int = lock.withLock { versions.getOrElse(chapter) { 0 } }

    companion object {
        /** What the remote resources of one book may hold in memory together, in bytes. */
        const val MAX_REMOTE_BYTES: Long = 32L * 1024 * 1024
    }
}
