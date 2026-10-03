package io.github.yuroyami.kitepdf.webview

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.yuroyami.kitepdf.epub.EpubDocument
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executors

/**
 * The files of one book for the desktop's web views, over HTTP on the loopback address (#41).
 * The book gets an origin of its own, a port, so books do not share storage, and every path
 * starts with a random token, so no other program on the machine can read the book. An answer
 * also sets the token as a cookie that scripts cannot read, so a path from the root of the
 * container, which a book may write as `/OEBPS/…`, resolves for the web view that has it. A
 * request with neither, or for a file the book does not have, gets a 404.
 *
 * Servers are shared by the web views of a book and counted: [acquire] starts one, and the last
 * [release] stops it.
 */
internal class BookServer private constructor(document: EpubDocument) {
    private val files = BookFiles(document)
    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private var users = 0

    /** Every zip path a web view asked for, in order, for tests. */
    val requested: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** The URL that every file of the book starts with. */
    val base: String = "http://${server.address.address.hostAddress}:${server.address.port}/$token/"

    init {
        server.createContext("/") { exchange -> exchange.use(::answer) }
        server.executor = Executors.newCachedThreadPool { task -> Thread(task, "kitepdf-book-server").apply { isDaemon = true } }
        server.start()
    }

    private val urls = BookUrls(base)

    /** The URL of [path], a zip path, with its fragment kept. */
    fun urlOf(path: String): String = urls.urlOf(path)

    /** The zip path, with its fragment, that [url] names, or null for a URL outside the book. */
    fun hrefOf(url: String): String? = urls.hrefOf(url)

    private fun answer(exchange: HttpExchange) {
        val raw = exchange.requestURI.rawPath
        val prefix = "/$token/"
        val known = exchange.requestHeaders["Cookie"].orEmpty().any { "$COOKIE=$token" in it }
        val path = when {
            exchange.requestMethod !in setOf("GET", "HEAD") -> null
            raw.startsWith(prefix) -> BookFiles.pathOf(raw.removePrefix(prefix))
            known -> BookFiles.pathOf(raw)
            else -> null
        }
        val response = if (path == null) BookResponse(404, "text/plain", ByteArray(0)) else {
            requested += path
            files.respond(path)
        }
        val headers = exchange.responseHeaders
        for ((name, value) in response.headers) headers.set(name, value)
        if (path != null) headers.set("Set-Cookie", "$COOKIE=$token; Path=/; HttpOnly; SameSite=Strict")
        headers.set("Content-Type", response.charset?.let { "${response.mimeType}; charset=$it" } ?: response.mimeType)
        val head = exchange.requestMethod == "HEAD"
        exchange.sendResponseHeaders(response.status, if (head || response.body.isEmpty()) -1 else response.body.size.toLong())
        if (!head && response.body.isNotEmpty()) exchange.responseBody.use { it.write(response.body) }
    }

    companion object {
        private const val COOKIE = "kitepdf-book"
        private val servers = IdentityHashMap<EpubDocument, BookServer>()

        /** The server of [document], started if no web view of the book uses one yet. */
        fun acquire(document: EpubDocument): BookServer = synchronized(servers) {
            servers.getOrPut(document) { BookServer(document) }.also { it.users++ }
        }

        /** Gives back a server from [acquire]; the last one given back stops. */
        fun release(server: BookServer) {
            synchronized(servers) {
                if (--server.users > 0) return
                servers.entries.removeIf { it.value === server }
            }
            server.server.stop(0)
            (server.server.executor as? java.util.concurrent.ExecutorService)?.shutdown()
        }
    }
}
