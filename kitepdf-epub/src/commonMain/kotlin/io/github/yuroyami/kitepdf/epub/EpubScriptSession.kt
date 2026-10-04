package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteDataUrl
import io.github.yuroyami.kitepdf.core.KiteLock
import io.github.yuroyami.kitepdf.core.script.KiteScriptEngine
import io.github.yuroyami.kitepdf.core.script.KiteScriptException
import io.github.yuroyami.kitepdf.core.withLock
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import io.github.yuroyami.kitepdf.epub.css.CssPosition
import io.github.yuroyami.kitepdf.epub.script.DOM_PRELUDE
import io.github.yuroyami.kitepdf.epub.script.ScriptDom
import io.github.yuroyami.kitepdf.epub.script.WhatwgDecoder
import io.github.yuroyami.kitepdf.epub.script.WhatwgEncoding
import io.github.yuroyami.kitepdf.epub.script.WhatwgMimeType
import io.github.yuroyami.kitepdf.epub.script.WhatwgUrl
import io.github.yuroyami.kitepdf.epub.script.byteString
import io.github.yuroyami.kitepdf.epub.script.bytesOf
import kotlin.math.roundToLong

/**
 * Runs the scripts of a book's chapters over the library's own parse and layout (#41).
 *
 * Each chapter is a window of its own, with its own engine from [engineFor]. Its scripts see a
 * DOM over the chapter: the document and its elements, events, timers, `console` and an
 * in-memory `localStorage` for the book. They run the first time the chapter opens, in document
 * order, and a script that changes the chapter has it laid out again from the changed tree once
 * the script returns, with the pages that it gains or loses.
 *
 * Nothing a script does reaches outside the book: there is no `fetch` or `XMLHttpRequest`, a
 * script at an address outside the book does not run, and a change of `location` goes to
 * [onNavigate] listeners as a link would.
 *
 * ```kotlin
 * val session = EpubScriptSession(book, engineFor = { MyEngine() })
 * session.chapterOpened(0)
 * session.tap(book.page(KiteLocation(0, 0)), x = 52.5, y = 90.0)
 * ```
 *
 * The session calls its engines on the caller's thread, so call it from the one thread they
 * belong to. `EpubScriptRunner` of `kitepdf-javascript` opens KiteJS engines on a thread of its
 * own and does that for a viewer, which calls from many threads.
 *
 * The scripts' changes belong to the book: another document over it, at another font size,
 * lays the changed chapters out too. They last as long as the book is open; [close] closes the
 * engines but leaves the changed chapters as they are.
 *
 * At most [liveChapters] chapters keep their scripts running, as a reading system unloads the
 * chapters the reader left (#498). Opening one more closes the engine of the chapter used least
 * recently, whose pages keep what its scripts made of them. When that chapter opens again, its
 * scripts start over from its own markup, as a page does when it loads again, and so does a
 * chapter that an earlier session's scripts changed.
 *
 * @param engineFor opens the engine of one chapter's scripts, the first time it opens. The
 *   session closes it in [close], or when it makes room for another chapter. An engine that
 *   will not open is a failure of that chapter, whose scripts then do not run.
 * @param liveChapters how many chapters' engines may be open at once, at least one. One suits
 *   engines that cannot share a thread with another open engine.
 * @param onConsole gets what scripts print with `console`, and what `alert`, `confirm` and
 *   `prompt` would have shown, with the level or the dialog's name.
 * @param clock milliseconds on a clock that only goes forward, for the timers and
 *   `performance.now()`. Leave it unset for the system's own.
 */
public class EpubScriptSession(
    public val document: EpubDocument,
    private val engineFor: (chapter: Int) -> KiteScriptEngine,
    private val onConsole: (level: String, message: String) -> Unit = { _, _ -> },
    clock: (() -> Long)? = null,
    public val liveChapters: Int = Int.MAX_VALUE,
) : EpubScriptHandler, AutoCloseable {

    init {
        require(liveChapters >= 1) { "liveChapters must be at least 1: $liveChapters" }
    }

    private val started = kotlin.time.TimeSource.Monotonic.markNow()
    private val now: () -> Long = clock ?: { started.elapsedNow().inWholeMilliseconds }

    /** The chapters whose scripts run, the one used least recently first. */
    private val chapters = LinkedHashMap<Int, ChapterScripts>()

    private val lock = KiteLock()
    private val timerListeners = ArrayList<() -> Unit>()
    private val navigationListeners = ArrayList<(String) -> Unit>()

    /**
     * The book's origin, the same in each chapter and another for another book (#500): a host
     * made from the package's unique identifier, so the book has it each time it opens.
     */
    private val origin: String = "epub://" + originHost(document.epubMetadata.identifier)

    /** `localStorage` and `sessionStorage`, one each for the whole book. */
    private val stores = mapOf("local" to LinkedHashMap<String, String>(), "session" to LinkedHashMap<String, String>())

    private val failureList = ArrayList<KiteScriptException>()

    @kotlin.concurrent.Volatile
    private var failureCopy: List<KiteScriptException> = emptyList()

    @kotlin.concurrent.Volatile
    private var timersWaiting = false

    @kotlin.concurrent.Volatile
    private var closed = false

    /**
     * The scripts that failed since the session opened, newest last: a throw, a parse error or
     * the engine's limit. It keeps the last [MAX_FAILURES], since a timer that throws each time
     * it runs would otherwise grow it for as long as the book is open.
     */
    public val failures: List<KiteScriptException> get() = failureCopy

    override val hasTimers: Boolean get() = timersWaiting

    override fun chapterOpened(chapter: Int) {
        open(chapter)
    }

    override fun tap(page: EpubPage, x: Double, y: Double): Boolean {
        if (!page.document.sharesBookWith(document)) return false
        val scripts = open(page.chapter) ?: return false
        val target = page.elementAt(x, y)?.let { scripts.dom.fromLayout[it] }
            ?: scripts.dom.root.children.firstOrNull { it is KiteXmlNode.Element }
            ?: scripts.dom.root
        val prevented = scripts.steps("tap", "__kite_tap(${scripts.dom.idOf(target)}, ${x / PT_PER_PX}, ${y / PT_PER_PX})") == "true"
        scripts.commit()
        return prevented
    }

    override fun pumpTimers(nowMillis: Long): Long? {
        var next: Long? = null
        for (scripts in chapters.values.toList()) {
            if (scripts.timers == 0) continue
            scripts.steps("timers", "__kite_pump(${now()})")
            val wait = scripts.call("timers") { scripts.engine.evaluate("__kite_wait()", "timers")?.toDoubleOrNull() }
            scripts.commit()
            if (wait != null && wait >= 0) next = minOf(next ?: Long.MAX_VALUE, wait.roundToLong())
        }
        return next
    }

    override fun onTimersChanged(listener: () -> Unit): () -> Unit {
        lock.withLock { timerListeners.add(listener) }
        return { lock.withLock { timerListeners.remove(listener) } }
    }

    override fun onNavigate(listener: (href: String) -> Unit): () -> Unit {
        lock.withLock { navigationListeners.add(listener) }
        return { lock.withLock { navigationListeners.remove(listener) } }
    }

    /** Closes every chapter's engine. The chapters keep what the scripts made of them. */
    override fun close() {
        if (closed) return
        closed = true
        for (scripts in chapters.values) scripts.close()
        chapters.clear()
        timersWaiting = false
    }

    /**
     * [chapter]'s scripts, run the first time and after its engine was closed to make room;
     * null for a chapter the book does not have or that has no scripts, which takes no engine
     * and leaves the others open. The chapter becomes the one used last.
     */
    private fun open(chapter: Int): ChapterScripts? {
        check(!closed) { "the script session is closed" }
        if (chapter !in 0 until document.chapterCount || !document.isScripted(chapter)) return null
        chapters.remove(chapter)?.let { scripts ->
            chapters[chapter] = scripts
            return scripts
        }
        // Room first: an engine that cannot share its thread opens only once the other has closed.
        var dropped = false
        while (chapters.size >= liveChapters) {
            val eldest = chapters.keys.first()
            chapters.remove(eldest)?.let { if (it.timers > 0) dropped = true; it.close() }
        }
        if (dropped) timersChanged()
        val scripts = ChapterScripts(chapter)
        chapters[chapter] = scripts
        scripts.start()
        return scripts
    }

    private fun recordFailure(failure: KiteScriptException) {
        failureList.add(failure)
        if (failureList.size > MAX_FAILURES) failureList.removeAt(0)
        failureCopy = failureList.toList()
    }

    private fun timersChanged() {
        timersWaiting = chapters.values.any { it.timers > 0 }
        lock.withLock { timerListeners.toList() }.forEach { it() }
    }

    private fun navigate(href: String) {
        lock.withLock { navigationListeners.toList() }.forEach { it(href) }
    }

    /** One chapter's window: its engine, its live tree and what binds the two. */
    private inner class ChapterScripts(val chapter: Int) {
        /** Null when it would not open, so nothing runs here. */
        private val opened: KiteScriptEngine? = try {
            engineFor(chapter)
        } catch (failure: Exception) {
            recordFailure(KiteScriptException("chapter $chapter: the engine did not open: ${failure.message}", failure))
            null
        }
        val engine: KiteScriptEngine get() = checkNotNull(opened)
        /** The chapter's media type, which makes its document an HTML or an XML one (#541). */
        val contentType: String = document.resourceType(document.chapterPath(chapter))?.lowercase() ?: "application/xhtml+xml"
        val dom = ScriptDom(document.sourceChapterTree(chapter), html = contentType == "text/html")

        /** The timers and frames its scripts wait on. */
        var timers = 0

        /** Where the next `document.write` of each script goes: after what the script wrote last. */
        private val writeCursors = HashMap<Int, KiteXmlNode>()

        /** False once the engine or the DOM failed to start, so nothing more runs here. */
        private var usable = opened != null

        /** Runs [block] against the engine; a failure is recorded, and answers null. */
        fun <T> call(what: String, block: () -> T): T? {
            if (!usable) return null
            return try {
                block()
            } catch (failure: KiteScriptException) {
                recordFailure(KiteScriptException("chapter $chapter, $what: ${failure.message}", failure))
                null
            }
        }

        /**
         * Runs [entry], one of the prelude's starts, then each callback it has left in a call of
         * its own (#535). The engine runs the promise jobs of a call once it returns, so the jobs
         * of each callback run before the next one, as HTML runs a microtask checkpoint after
         * each callback it invokes. Answers what the steps returned, or null when a call failed.
         */
        fun steps(what: String, entry: String): String? {
            var answer = call(what) { engine.evaluate(entry, what) }
            while (answer == MORE) answer = call(what) { engine.evaluate("__kite_step()", what) }
            return answer
        }

        /**
         * Hands the layout the tree as the scripts left it, when they changed it, and settles the
         * book's blob URLs: what the tree names stays with it, and what the call revoked goes (#533).
         */
        fun commit() {
            val tree = if (dom.dirty) dom.snapshot() else null
            if (tree != null) document.replaceChapterTree(chapter, tree)
            document.blobUrls.settle(chapter, tree)
        }

        /** Closes the engine, which revokes the blob URLs its scripts made, as a browser does when a page unloads (#533). */
        fun close() {
            runCatching { opened?.close() }
            document.blobUrls.closeChapter(chapter)
        }

        fun start() {
            // The layout shows what scripts made of the chapter before, so it takes this run's
            // tree even when the scripts change nothing.
            if (document.chapterVersionOf(chapter) > 0) dom.dirty = true
            if (!usable) {
                commit()
                return
            }
            bind()
            try {
                engine.evaluate(DOM_PRELUDE, "kitepdf-dom.js")
            } catch (failure: KiteScriptException) {
                // Without its DOM no script of the chapter can run, so none is tried.
                recordFailure(KiteScriptException("chapter $chapter: the DOM did not start: ${failure.message}", failure))
                usable = false
                commit()
                return
            }
            // Scripts run here, so `noscript` content does not show, as in a browser that runs them.
            for (noscript in elementsIn(dom.root, "noscript")) dom.remove(null, noscript)
            // A parser makes an element when it reaches its start tag, and a handler attribute takes
            // its place in the listener list then (#539): the elements before a script are made
            // before it runs, those after it once it ran.
            val handlers = ArrayList<Int>()
            val scripts = ArrayList<Pair<KiteXmlNode.Element, Int>>()
            fun walk(e: KiteXmlNode.Element) {
                if (e.attrs.keys.any { it.length > 2 && it.startsWith("on") }) handlers.add(dom.idOf(e))
                if (e.tag == "script") scripts.add(e to handlers.size) else for (c in e.children) if (c is KiteXmlNode.Element) walk(c)
            }
            walk(dom.root)
            if (handlers.isNotEmpty()) call("markup") { engine.evaluate("__kite_markup([${handlers.joinToString(",")}])", "markup") }
            for ((script, reached) in scripts) runScript(script, reached)
            steps("load", "__kite_loaded()")
            commit()
        }

        /** The [tag] elements under [el], in tree order, not looking inside one. */
        private fun elementsIn(el: KiteXmlNode.Element, tag: String): List<KiteXmlNode.Element> {
            val out = ArrayList<KiteXmlNode.Element>()
            fun walk(e: KiteXmlNode.Element) {
                for (c in e.children) if (c is KiteXmlNode.Element) {
                    if (c.tag == tag) out.add(c) else walk(c)
                }
            }
            walk(el)
            return out
        }

        /**
         * Runs one classic script, inline or from the zip, once the parser reached the first
         * [reached] elements of the markup with handler attributes. A module and a data block do not run.
         */
        private fun runScript(script: KiteXmlNode.Element, reached: Int) {
            val type = script.attrs["type"]?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            if (type.isNotEmpty() && type !in SCRIPT_TYPES) return
            val src = script.attrs["src"]?.trim()
            val (name, source) = if (!src.isNullOrEmpty()) {
                val path = EpubDocument.resolvePath(document.chapterDir(chapter), src)
                if (isRemoteUrl(path)) {
                    recordFailure(KiteScriptException("chapter $chapter: $src is outside the book, so it does not run"))
                    return
                }
                val bytes = document.resource(path)
                if (bytes == null) {
                    recordFailure(KiteScriptException("chapter $chapter: $src is not in the book"))
                    return
                }
                path to bytes.decodeToString().removePrefix("﻿")
            } else {
                "${document.chapterPath(chapter)}#script" to dom.textOf(script)
            }
            val id = dom.idOf(script)
            call("current") { engine.evaluate("__kite_current($id, $reached)", "current") }
            call(name) { engine.evaluate(withoutCommentMarks(source), name) }
            call("current") { engine.evaluate("__kite_current(null)", "current") }
        }

        private fun bind() {
            fun def(name: String, function: (List<Any?>) -> Any?) = engine.defineFunction("__kite.$name", function)
            fun node(args: List<Any?>, i: Int): KiteXmlNode? = (args.getOrNull(i) as? Double)?.let { dom.node(it.toInt()) }
            fun element(args: List<Any?>, i: Int): KiteXmlNode.Element? = node(args, i) as? KiteXmlNode.Element
            fun string(args: List<Any?>, i: Int): String = args.getOrNull(i)?.toString().orEmpty()
            fun id(node: KiteXmlNode?): Int? = node?.let(dom::idOf)

            def("root") { dom.idOf(dom.root) }
            def("version") { dom.version.toDouble() }
            def("contentType") { contentType }
            def("kind") { args -> node(args, 0)?.let(dom::kind) }
            def("tag") { args -> element(args, 0)?.tag.orEmpty() }
            def("name") { args -> element(args, 0)?.let(dom::nameOf)?.let { listOf(it.namespace, it.prefix, it.localName) } }
            def("parent") { args -> node(args, 0)?.let(dom::parentOf)?.let(dom::idOf) }
            def("children") { args -> element(args, 0)?.children?.map(dom::idOf) }
            def("attr") { args -> element(args, 0)?.attrs?.get(string(args, 1)) }
            def("setAttr") { args -> element(args, 0)?.let { dom.setAttr(it, string(args, 1), string(args, 2)) }; null }
            def("removeAttr") { args -> element(args, 0)?.let { dom.removeAttr(it, string(args, 1)) }; null }
            def("attrNames") { args -> element(args, 0)?.attrs?.keys?.toList().orEmpty() }
            def("text") { args -> node(args, 0)?.let(dom::textOf).orEmpty() }
            def("setText") { args -> node(args, 0)?.let { dom.setText(it, string(args, 1)) }; null }
            def("create") { args -> dom.idOf(dom.createElement(string(args, 0), args.getOrNull(1) as? String, args.getOrNull(2) as? String)) }
            def("createDocument") { dom.idOf(dom.createDocument()) }
            def("createText") { args -> dom.idOf(KiteXmlNode.Text(string(args, 0))) }
            def("createFragment") { dom.idOf(dom.createFragment()) }
            def("insert") { args ->
                val parent = element(args, 0) ?: return@def "HierarchyRequestError"
                val child = node(args, 1) ?: return@def "NotFoundError"
                dom.insert(parent, child, node(args, 2))
            }
            def("remove") { args ->
                val child = node(args, 1) ?: return@def "NotFoundError"
                dom.remove(element(args, 0), child)
            }
            def("clone") { args -> node(args, 0)?.let { dom.idOf(dom.clone(it, args.getOrNull(1) == true)) } }
            def("byId") { args -> id(dom.byId(string(args, 0))) }
            def("query") { args ->
                val scope = element(args, 0) ?: return@def emptyList<Int>()
                dom.query(scope, string(args, 1), args.getOrNull(2) == true)?.map(dom::idOf)
            }
            def("matches") { args -> element(args, 0)?.let { dom.matches(it, string(args, 1)) } }
            def("html") { args -> node(args, 0)?.let { dom.html(it, args.getOrNull(1) == true) }.orEmpty() }
            def("setHtml") { args -> element(args, 0)?.let { dom.setHtml(it, string(args, 1)) }; null }
            def("insertHtml") { args -> element(args, 0)?.let { dom.insertHtml(it, string(args, 1), string(args, 2)) } }
            def("write") { args ->
                val script = node(args, 0) ?: return@def null
                val scriptId = dom.idOf(script)
                writeCursors[scriptId] = dom.writeAfter(writeCursors[scriptId] ?: script, string(args, 1)) ?: return@def null
                null
            }
            def("connected") { args -> node(args, 0)?.let(dom::isConnected) ?: false }
            def("rect") { args ->
                val live = element(args, 0) ?: return@def null
                // A browser lays the page out again when a script measures after a change (#499).
                commit()
                val laid = dom.toLayout[live] ?: return@def null
                val r = document.scriptBoundsOf(chapter, laid) ?: return@def null
                listOf(r.left / PT_PER_PX, r.bottom / PT_PER_PX, r.width / PT_PER_PX, r.height / PT_PER_PX)
            }
            def("computed") { args -> element(args, 0)?.let { computed(it, string(args, 1)) } }
            def("viewport") {
                val (w, h) = document.scriptViewportOf(chapter)
                listOf(w / PT_PER_PX, h / PT_PER_PX)
            }
            def("now") { now().toDouble() }
            def("console") { args -> onConsole(string(args, 0), string(args, 1)); null }
            def("error") { args -> recordFailure(KiteScriptException("chapter $chapter: ${string(args, 0)}")); null }
            def("storage") { args -> storage(string(args, 0), string(args, 1), args.getOrNull(2)?.toString(), args.getOrNull(3)?.toString()) }
            def("navigate") { args ->
                // An address under the book's own origin is a path in the book, from its root.
                val href = string(args, 0).let { if (it.startsWith("$origin/")) it.substring(origin.length) else it }
                // A data URL never opens as a page of its own (EPUB Reading Systems 3.3, 3.4), as a
                // browser will not navigate its top frame to one (#514).
                if (KiteDataUrl.isDataUrl(href)) {
                    recordFailure(KiteScriptException("chapter $chapter: a script may not open a data: URL as a page"))
                    return@def null
                }
                navigate(resolveLinkHref(href, document.chapterPath(chapter)) { EpubDocument.resolvePath(document.chapterDir(chapter), it) })
                null
            }
            def("location") { "$origin/" + document.chapterPath(chapter) }
            def("origin") { origin }
            // The URL Standard (#520): a parse against an optional base, a setter of the URL class
            // on a serialized URL, and the application/x-www-form-urlencoded parser and serializer
            // over flat name-value lists.
            def("url") { args -> WhatwgUrl.parse(string(args, 0), args.getOrNull(1)?.toString())?.let(::urlParts) }
            def("urlSet") { args -> WhatwgUrl.parse(string(args, 0))?.takeIf { it.set(string(args, 1), string(args, 2)) }?.let(::urlParts) }
            def("formParse") { args -> WhatwgUrl.parseForm(string(args, 0)).flatMap { listOf(it.first, it.second) } }
            def("formSerialize") { args ->
                val flat = (args.getOrNull(0) as? List<*>).orEmpty().map { it?.toString().orEmpty() }
                WhatwgUrl.serializeForm(flat.chunked(2).filter { it.size == 2 }.map { it[0] to it[1] })
            }
            // The Encoding Standard (#532): the encoding a label names, a decoder that goes on from
            // the state the call before it left, the UTF-8 encoder, and forgiving base64. Bytes
            // cross as strings whose code units are bytes.
            def("encoding") { args -> WhatwgEncoding.forLabel(string(args, 0)) }
            def("decode") { args ->
                val state = (args.getOrNull(3) as? List<*>)?.map { (it as? Double)?.toInt() ?: 0 }
                val decoder = WhatwgDecoder(string(args, 0), args.getOrNull(1) == true, args.getOrNull(2) == true, state)
                listOf(decoder.decode(bytesOf(string(args, 4)), flush = args.getOrNull(5) == true)) + decoder.state
            }
            def("encode") { args -> byteString(WhatwgEncoding.utf8Encode(string(args, 0))) }
            def("encodeInto") { args ->
                val capacity = ((args.getOrNull(1) as? Double) ?: 0.0).coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt()
                val (read, bytes) = WhatwgEncoding.utf8EncodeInto(string(args, 0), capacity)
                listOf(read, byteString(bytes))
            }
            def("atob") { args -> WhatwgEncoding.atob(string(args, 0)) }
            def("btoa") { args -> WhatwgEncoding.btoa(string(args, 0)) }
            // The File API (#533): the text a FileReader reads, in the encoding its label names,
            // else the charset of the blob's type, else UTF-8, a byte order mark first; and the
            // book's blob URL store.
            def("blobText") { args ->
                val encoding = args.getOrNull(1)?.toString()?.let(WhatwgEncoding::forLabel)
                    ?: WhatwgMimeType.parse(string(args, 2))?.parameters?.get("charset")?.let(WhatwgEncoding::forLabel)
                    ?: "UTF-8"
                WhatwgEncoding.decode(bytesOf(string(args, 0)), encoding)
            }
            def("blobUrl") { args -> document.blobUrls.create(origin, chapter, bytesOf(string(args, 0)), string(args, 1)) }
            def("revokeBlobUrl") { args -> document.blobUrls.revoke(string(args, 0)); null }
            def("timers") { args ->
                timers = (args.getOrNull(0) as? Double)?.toInt() ?: 0
                timersChanged()
                null
            }
        }

        /**
         * What the `URL` class of the prelude reads of [url]: its href, its origin, the parts its
         * getters answer and the raw query. The book's own scheme has a tuple origin, so a URL in
         * the book has the origin that `location.origin` gives.
         */
        private fun urlParts(url: WhatwgUrl): List<String?> = listOf(
            url.href(), originOf(url), url.protocol, url.username, url.password, url.hostWithPort,
            url.hostname, url.portString, url.pathname, url.search, url.hash, url.query,
        )

        /**
         * The origin of [url]. A blob URL that the parser resolves to an entry of the book's store
         * has the origin of the book that made it, as the URL Standard takes the entry's (#533).
         */
        private fun originOf(url: WhatwgUrl): String =
            if (url.scheme == "blob" && document.blobUrls.isLive(url.href())) origin else url.origin(tupleScheme = "epub")

        /** What `getComputedStyle` answers for [property] of [el]: the cascade's value, in CSS pixels. */
        private fun computed(el: KiteXmlNode.Element, property: String): String {
            val style = document.scriptStyleOf(chapter, el)
            fun px(pt: Double) = "${(pt / PT_PER_PX * 100).roundToLong() / 100.0}px".replace(".0px", "px")
            fun rgb(c: io.github.yuroyami.kitepdf.core.render.RgbColor, alpha: Double = 1.0): String {
                fun v(x: Double) = (x.coerceIn(0.0, 1.0) * 255).roundToLong()
                return if (alpha >= 1.0) "rgb(${v(c.r)}, ${v(c.g)}, ${v(c.b)})" else "rgba(${v(c.r)}, ${v(c.g)}, ${v(c.b)}, $alpha)"
            }
            return when (property) {
                "display" -> style.display.name.lowercase().replace('_', '-')
                "visibility" -> if (style.visible) "visible" else "hidden"
                "color" -> rgb(style.color)
                "background-color" -> style.backgroundColor?.let { rgb(it.color, it.alpha) } ?: "rgba(0, 0, 0, 0)"
                "font-size" -> px(style.fontSizePt)
                "font-weight" -> if (style.bold) "700" else "400"
                "font-style" -> if (style.italic) "italic" else "normal"
                "opacity" -> style.opacity.toString().removeSuffix(".0")
                "text-align" -> style.textAlign.name.lowercase()
                "position" -> style.position.name.lowercase()
                "float" -> style.cssFloat.name.lowercase()
                "width" -> style.widthPt?.let(::px) ?: "auto"
                "height" -> style.heightPt?.let(::px) ?: "auto"
                "z-index" -> style.zIndex?.toString() ?: "auto"
                "left" -> style.leftPt?.takeIf { style.position != CssPosition.STATIC }?.let(::px) ?: "auto"
                "top" -> style.topPt?.takeIf { style.position != CssPosition.STATIC }?.let(::px) ?: "auto"
                else -> ""
            }
        }
    }

    private fun storage(kind: String, op: String, key: String?, value: String?): Any? {
        val store = stores[kind] ?: return null
        return when (op) {
            "get" -> store[key]
            "set" -> { if (key != null && value != null) store[key] = value; null }
            "remove" -> { store.remove(key); null }
            "clear" -> { store.clear(); null }
            "key" -> key?.toIntOrNull()?.let { store.keys.elementAtOrNull(it) }
            "length" -> store.size
            else -> null
        }
    }

    public companion object {
        /** How many failures [failures] keeps. */
        public const val MAX_FAILURES: Int = 100

        /** CSS pixels are 0.75 points, the unit of the layout and of display space. */
        private const val PT_PER_PX = 0.75

        /** What a start of the prelude and `__kite_step()` answer while a callback is left to run. */
        private const val MORE = "more"

        /**
         * The host of a book's origin: 64 bits of an FNV-1a hash of [identifier], in hex. A book
         * without one gets a host of its own each time it opens.
         */
        private fun originHost(identifier: String?): String {
            val key = identifier?.trim()?.takeIf { it.isNotEmpty() }
                ?: return "book-" + kotlin.random.Random.nextLong().toULong().toString(16)
            var hash = 0xcbf29ce484222325UL
            for (b in key.encodeToByteArray()) {
                hash = (hash xor (b.toULong() and 0xFFUL)) * 0x100000001b3UL
            }
            return hash.toString(16).padStart(16, '0')
        }

        /** The types of a classic script (HTML, 4.12.1.1). */
        private val SCRIPT_TYPES = setOf(
            "text/javascript", "application/javascript", "application/ecmascript", "text/ecmascript",
            "application/x-javascript", "text/x-javascript", "text/jscript", "text/livescript",
        )

        /** [source] without the `<!--` and `-->` lines old books wrap their scripts in. */
        private fun withoutCommentMarks(source: String): String {
            val lines = source.lines().toMutableList()
            if (lines.firstOrNull { it.isNotBlank() }?.trim()?.startsWith("<!--") == true) {
                lines[lines.indexOfFirst { it.isNotBlank() }] = ""
            }
            val last = lines.indexOfLast { it.isNotBlank() }
            if (last >= 0 && lines[last].trim().endsWith("-->")) lines[last] = ""
            return lines.joinToString("\n")
        }
    }
}
