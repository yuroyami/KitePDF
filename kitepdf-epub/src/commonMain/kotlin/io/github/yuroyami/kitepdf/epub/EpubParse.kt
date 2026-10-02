package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.xml.KiteXml
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import io.github.yuroyami.kitepdf.core.xml.KiteXmlToken

import io.github.yuroyami.kitepdf.core.zip.ZipReader

import io.github.yuroyami.kitepdf.core.KiteLock
import io.github.yuroyami.kitepdf.core.withLock
import io.github.yuroyami.kitepdf.core.render.KiteImageIdentity
import io.github.yuroyami.kitepdf.epub.css.CssParser
import io.github.yuroyami.kitepdf.epub.css.Direction
import io.github.yuroyami.kitepdf.epub.css.FontFaceRule
import io.github.yuroyami.kitepdf.epub.css.Origin
import io.github.yuroyami.kitepdf.epub.css.StyleRule

/**
 * One spine document's font-size-independent parse: its DOM, author CSS rules,
 * base dir and declared viewport. Built on demand by [ParsedEpub.spine].
 */
internal class ParsedSpine(
    val tree: KiteXmlNode.Element,
    val rules: List<StyleRule>,
    val docDir: String,
    /** The `<meta name=viewport>` size in points, or null when the document declares none. */
    val viewport: Pair<Double, Double>?,
    /** Zip path of this spine document, the key for href -> page navigation. */
    val path: String,
    /** Faces from this document's own inline `<style>` blocks. Almost always empty. */
    val localFaces: List<EmbeddedFace>,
) {
    /** What each note, glossary and bibliography link in this document is for, by href (#227). */
    val linkKinds: Map<String, EpubLinkKind> by lazy {
        linkKindsIn(tree) { href -> resolveLinkHref(href, path) { EpubDocument.resolvePath(docDir, it) } }
    }
}

/**
 * The reusable, font-size-independent parse of a book. One [ParsedEpub] backs any
 * number of [EpubDocument]s at different [EpubSettings], so re-flowing on a
 * settings change is a re-layout, never a re-parse.
 *
 * Opening reads only the container, the OPF and the TOC. Spine documents parse
 * one at a time, when a chapter is first laid out, and stylesheets parse once per
 * file instead of once per chapter that links them.
 */
internal class ParsedEpub(
    val zip: ZipReader,
    private val opf: OpfPackage,
    /** Zip paths of the spine documents, in reading order. Reading this parses nothing. */
    val spinePaths: List<String>,
    val metadata: EpubMetadata,
    val toc: TableOfContents,
    val baseDir: Direction,
    /** How each spine document asks to be shown, parallel to [spinePaths] (#37). */
    val renditions: List<EpubRendition>,
    /** Whether the manifest marks each spine document `scripted`, parallel to [spinePaths] (#40). */
    private val manifestScripted: List<Boolean> = List(spinePaths.size) { false },
    /** The zip path of each spine document's media overlay, and its duration, parallel to [spinePaths] (#36). */
    private val overlays: List<Pair<String, Double?>?> = List(spinePaths.size) { null },
) {

    val spineCount: Int get() = spinePaths.size

    /** The same immutable image resources survive chapter eviction and settings changes (#371). */
    val imageIdentities = KiteImageIdentity()

    /** Whether [chapter] keeps the fixed pages its author set, rather than reflowing. */
    fun isFixed(chapter: Int): Boolean = renditions.getOrNull(chapter)?.layout == EpubLayout.PRE_PAGINATED

    /** Whether every chapter keeps fixed pages. */
    val allFixed: Boolean = renditions.isNotEmpty() && renditions.all { it.layout == EpubLayout.PRE_PAGINATED }

    private val overlayLock = KiteLock()
    private val overlayCache = arrayOfNulls<EpubMediaOverlay>(spinePaths.size)

    /** [chapter]'s media overlay, parsed on first use and kept, or null when it has none (#36). */
    fun mediaOverlay(chapter: Int): EpubMediaOverlay? {
        val (path, duration) = overlays[chapter] ?: return null
        overlayLock.withLock { overlayCache[chapter] }?.let { return it }
        val overlay = EpubMediaOverlay(SmilParser.clips(zip.readText(path).orEmpty(), path), duration)
        return overlayLock.withLock { overlayCache[chapter] ?: overlay.also { overlayCache[chapter] = it } }
    }

    private val scriptLock = KiteLock()
    private val scriptFound = arrayOfNulls<Boolean>(spinePaths.size)

    /**
     * Whether [chapter] is scripted: the manifest marks it, or its document has a `script`
     * element. Reads the chapter's markup once, without building its tree (#40).
     */
    fun isScripted(chapter: Int): Boolean {
        if (manifestScripted[chapter]) return true
        scriptLock.withLock { scriptFound[chapter] }?.let { return it }
        val text = zip.readText(spinePaths[chapter]).orEmpty()
        val found = KiteXml.tokenize(text).any { token ->
            token is KiteXmlToken.Open && token.name.substringAfterLast(':').equals("script", ignoreCase = true)
        }
        scriptLock.withLock { scriptFound[chapter] = found }
        return found
    }

    val spineIndices: IntRange get() = spinePaths.indices

    private val spineLock = KiteLock()
    private val spineCache = arrayOfNulls<ParsedSpine>(spinePaths.size)

    private val sheetLock = KiteLock()
    private val sheetCache = HashMap<String, List<StyleRule>>()
    private var sheetCount = 0

    /**
     * [chapter]'s DOM and CSS, parsed on first use and kept. Parsing runs outside
     * the lock; if two threads race, the first to publish wins and both get that
     * instance, so the book never holds two trees for one chapter.
     */
    fun spine(chapter: Int): ParsedSpine {
        spineLock.withLock { spineCache[chapter] }?.let { return it }
        val built = buildSpine(chapter)
        return spineLock.withLock { spineCache[chapter] ?: built.also { spineCache[chapter] = it } }
    }

    /** Whether [chapter]'s document has been parsed yet. For tests and diagnostics. */
    fun isSpineParsed(chapter: Int): Boolean =
        chapter in spinePaths.indices && spineLock.withLock { spineCache[chapter] } != null

    /** How many stylesheet files have been parsed. One per file, never one per chapter. */
    val sheetsParsed: Int get() = sheetLock.withLock { sheetCount }

    private val programLock = KiteLock()

    /**
     * Parsed font programs by zip path, null for a file that would not parse.
     * One per file, however many `@font-face` rules name it: a converter that
     * repeats the book's font in every chapter's own `<style>` otherwise gave
     * the book one parsed font per chapter, each with its own bytes and glyph
     * caches, and no budget counted them (#224).
     */
    private val programCache = HashMap<String, FontProgram?>()
    private var programCount = 0

    /** How many font files have been read and parsed. One per file, never one per chapter. */
    val fontFilesParsed: Int get() = programLock.withLock { programCount }

    /**
     * Every `@font-face` declared by a stylesheet in the OPF manifest, loaded from
     * the zip. Built once, on the first chapter layout, not at open time.
     *
     * `url()` resolves against the stylesheet's own directory, which is what CSS
     * says and what a book with its CSS and its documents in different folders
     * needs. Faces declared inside a document's inline `<style>` are not here;
     * they belong to that one document (see [ParsedSpine.localFaces]).
     */
    val fonts: FontRegistry by lazy {
        val found = ArrayList<Pair<FontFaceRule, String>>()
        for (item in opf.items) {
            if (item.mediaType != "text/css" && !item.href.endsWith(".css", ignoreCase = true)) continue
            val path = EpubDocument.resolvePath(opf.baseDir, item.href)
            val text = zip.readText(path) ?: continue
            if ("@font-face" !in text) continue // cheap reject: most books have none
            for (rule in CssParser.parseAll(text, Origin.AUTHOR).fontFaces) found.add(rule to dirOf(path))
        }
        if (found.isEmpty()) FontRegistry.EMPTY
        else FontRegistry(found.mapNotNull { (rule, dir) -> loadFace(rule, dir) })
    }

    /** Obfuscated zip path -> algorithm URI, for the mangled fonts some retailers ship. */
    private val obfuscation: Map<String, String> by lazy { parseEncryption(zip) }

    private fun buildSpine(chapter: Int): ParsedSpine {
        val path = spinePaths[chapter]
        val docDir = path.substringBeforeLast('/', "")
        // An entry that will not inflate becomes an empty document: the chapter
        // yields no pages, which is what skipping it used to do.
        val tree = HtmlParser.parse(zip.readText(path) ?: "").also(::resolveSwitches)
        val rules = ArrayList<StyleRule>()
        val faces = ArrayList<EmbeddedFace>()
        walkStyleSources(
            tree, docDir,
            onLink = { sheet -> rules.addAll(sheetRules(sheet)) },
            onInline = { text ->
                val css = CssParser.parseAll(absoluteUrls(inlineImports(zip, text, docDir, 0, HashSet()), docDir), Origin.AUTHOR)
                rules.addAll(css.rules)
                for (rule in css.fontFaces) loadFace(rule, docDir)?.let(faces::add)
            },
        )
        return ParsedSpine(tree, rules, docDir, parseViewport(tree), path, faces)
    }

    /** Manifest items by their zip path, for the fallback of a resource that a document names by path. */
    private val itemsByPath: Map<String, OpfItem> by lazy {
        opf.items.associateBy { EpubDocument.resolvePath(opf.baseDir, it.href) }
    }

    /** The media type that the manifest gives the file at [path], or null. */
    fun mediaTypeOf(path: String): String? = itemsByPath[path]?.mediaType

    /**
     * The zip paths of the items that the fallback chain of the item at [path] names after it,
     * in order. Empty for a path that is not in the manifest or has no fallback (#27).
     */
    fun fallbackPaths(path: String): List<String> {
        val item = itemsByPath[path] ?: return emptyList()
        return opf.fallbackChain(item.id).drop(1).map { EpubDocument.resolvePath(opf.baseDir, it.href) }
    }

    /** One stylesheet's rules, parsed once however many chapters link it. */
    private fun sheetRules(path: String): List<StyleRule> {
        sheetLock.withLock { sheetCache[path] }?.let { return it }
        val text = zip.readText(path) ?: ""
        val rules = CssParser.parse(absoluteUrls(inlineImports(zip, text, dirOf(path), 0, hashSetOf(path)), dirOf(path)), Origin.AUTHOR)
        return sheetLock.withLock { sheetCache.getOrPut(path) { sheetCount++; rules } }
    }

    private fun loadFace(rule: FontFaceRule, dir: String): EmbeddedFace? {
        // Prefer the cheapest format to unpack: raw SFNT (.ttf/.otf), then WOFF 1.0
        // (zlib tables), then WOFF2 (brotli + glyf transform). Otherwise take the
        // first src and let signature sniffing in FontRegistry.face sort it out.
        val url = rule.srcUrls.firstOrNull { it.endsWith(".ttf", true) || it.endsWith(".otf", true) }
            ?: rule.srcUrls.firstOrNull { it.endsWith(".woff", true) }
            ?: rule.srcUrls.firstOrNull { it.endsWith(".woff2", true) }
            ?: rule.srcUrls.firstOrNull()
            ?: return null
        val program = programAt(fontPath(dir, url)) ?: return null
        return EmbeddedFace(rule.family, rule.bold, rule.italic, program)
    }

    /** The program of the font file at [path], parsed on first use and shared by every face after. */
    private fun programAt(path: String): FontProgram? {
        programLock.withLock { if (programCache.containsKey(path)) return programCache[path] }
        val raw = zip.read(path) ?: return null
        val bytes = obfuscation[path]?.let { Deobfuscate.deobfuscate(raw, it, opf.uniqueId ?: "") } ?: raw
        val parsed = FontProgram.parse(bytes)
        // Parsing ran outside the lock; the first to publish wins, as for chapters.
        return programLock.withLock {
            if (programCache.containsKey(path)) programCache[path]
            else { programCount++; programCache[path] = parsed; parsed }
        }
    }

    /** The sheet's own folder, falling back to the OPF's for books with wrong urls. */
    private fun fontPath(dir: String, url: String): String {
        val own = EpubDocument.resolvePath(dir, url)
        if (own in zip.names) return own
        val fromOpf = EpubDocument.resolvePath(opf.baseDir, url)
        return if (fromOpf in zip.names) fromOpf else own
    }

    companion object {

        /**
         * Read [bytes] far enough to know what the book is: container, OPF, TOC.
         * Spine documents and fonts stay unparsed until something asks for them.
         *
         * @throws EpubFormatException when the bytes are not a readable EPUB.
         */
        fun parse(bytes: ByteArray): ParsedEpub {
            val zip = ZipReader(bytes)
            val opfPath = containerOpfPath(zip)
                ?: throw EpubFormatException("META-INF/container.xml missing or unreadable")
            val opf = Opf.parse(zip, opfPath)
                ?: throw EpubFormatException("OPF not found at $opfPath")
            // A spine item of a type this engine does not render shows its fallback document (#27).
            // Each document keeps the index of its spine entry, whose properties it takes (#37).
            val spine = opf.spineIdrefs.indices.mapNotNull { index ->
                val href = opf.contentDocument(opf.spineIdrefs[index])?.href ?: return@mapNotNull null
                EpubDocument.resolvePath(opf.baseDir, href) to index
            }
            val contentPaths = spine.map { it.first }
            if (contentPaths.isEmpty()) throw EpubFormatException("spine is empty in $opfPath")

            val present = spine.filter { it.first in zip.names }
            if (present.isEmpty()) throw EpubFormatException("spine has no readable documents")

            return ParsedEpub(
                zip = zip,
                opf = opf,
                spinePaths = present.map { it.first },
                metadata = buildMetadata(opf),
                toc = TocParser.parse(zip, opf, contentPaths) { base, href -> EpubDocument.resolvePath(base, href) },
                baseDir = if (opf.direction?.lowercase() == "rtl") Direction.RTL else Direction.LTR,
                renditions = present.map { opf.renditionAt(it.second) },
                manifestScripted = present.map { opf.contentDocument(opf.spineIdrefs[it.second])?.hasProperty("scripted") == true },
                overlays = present.map { (_, index) ->
                    val overlayId = opf.contentDocument(opf.spineIdrefs[index])?.mediaOverlay ?: return@map null
                    val item = opf.itemsById[overlayId] ?: return@map null
                    EpubDocument.resolvePath(opf.baseDir, item.href) to opf.overlayDurations[overlayId]
                },
            )
        }

        private fun buildMetadata(opf: OpfPackage): EpubMetadata {
            val coverHref = opf.items.firstOrNull { it.hasProperty("cover-image") }?.href
                ?: opf.metaCoverId?.let { opf.itemsById[it]?.href }
            return EpubMetadata(
                title = opf.title,
                creators = opf.creators,
                language = opf.language,
                identifier = opf.uniqueId,
                coverImagePath = coverHref?.let { EpubDocument.resolvePath(opf.baseDir, it) },
                // A vertical-rl book implies rtl progression when the spine
                // declares no direction of its own.
                rightToLeft = opf.direction?.lowercase() == "rtl" ||
                    (opf.direction == null && opf.primaryWritingMode?.lowercase() == "vertical-rl"),
                rendition = opf.rendition,
                pronunciationLexicons = opf.items.filter { it.mediaType?.lowercase() == "application/pls+xml" }
                    .map { EpubDocument.resolvePath(opf.baseDir, it.href) },
                narration = opf.narration,
            )
        }

        /** META-INF/encryption.xml -> obfuscated zip path -> algorithm URI. */
        private fun parseEncryption(zip: ZipReader): Map<String, String> {
            val xml = zip.readText("META-INF/encryption.xml") ?: return emptyMap()
            val map = HashMap<String, String>()
            var algo: String? = null
            for (t in KiteXml.tokenize(xml)) if (t is KiteXmlToken.Open) when (t.name) {
                "encrypteddata" -> algo = null
                "encryptionmethod" -> algo = t.attrs["algorithm"]
                "cipherreference" -> {
                    val uri = t.attrs["uri"]; val a = algo
                    if (uri != null && a != null) map[EpubDocument.resolvePath("", uri)] = a
                }
            }
            return map
        }

        /** META-INF/container.xml -> the OPF package path. */
        private fun containerOpfPath(zip: ZipReader): String? {
            val xml = zip.readText("META-INF/container.xml") ?: return null
            for (t in KiteXml.tokenize(xml)) {
                if (t is KiteXmlToken.Open && t.name == "rootfile") t.attrs["full-path"]?.let { return it }
            }
            return null
        }

        /** Visit a document's author CSS in document order: linked sheets, then `<style>` blocks. */
        private fun walkStyleSources(
            tree: KiteXmlNode.Element,
            docDir: String,
            onLink: (String) -> Unit,
            onInline: (String) -> Unit,
        ) {
            fun walk(el: KiteXmlNode.Element) {
                when (el.tag) {
                    "link" -> {
                        val rel = el.attrs["rel"]?.lowercase() ?: ""
                        val href = el.attrs["href"]
                        if ("stylesheet" in rel && href != null) onLink(EpubDocument.resolvePath(docDir, href))
                    }
                    "style" -> onInline(buildString { for (c in el.children) if (c is KiteXmlNode.Text) append(c.text) })
                    else -> for (c in el.children) if (c is KiteXmlNode.Element) walk(c)
                }
            }
            walk(tree)
        }

        /**
         * Replace `@import url(...)` / `@import "..."` with the imported sheet's
         * content, resolved zip-relative, recursively (depth cap 8, visited-set
         * cycle guard). Media conditions after the target are ignored, matching
         * the parser's always-on `@media` flattening.
         */
        private fun inlineImports(
            zip: ZipReader,
            css: String,
            baseDir: String,
            depth: Int,
            visited: MutableSet<String>,
        ): String {
            if (depth >= 8 || "@import" !in css) return css
            return IMPORT_RE.replace(css) { m ->
                val path = EpubDocument.resolvePath(baseDir, m.groupValues[1])
                if (!visited.add(path)) ""
                else zip.readText(path)?.let { absoluteUrls(inlineImports(zip, it, dirOf(path), depth + 1, visited), dirOf(path)) } ?: ""
            }
        }

        private fun dirOf(path: String): String = path.substringBeforeLast('/', "")

        /**
         * [css] with every relative `url()` made absolute against [baseDir], with a leading slash,
         * so a rule keeps the folder of its own sheet in whichever document it applies (#28).
         */
        internal fun absoluteUrls(css: String, baseDir: String): String {
            if (!css.contains("url(", ignoreCase = true)) return css
            return URL_RE.replace(css) { m ->
                val raw = m.groupValues[2].trim()
                if (raw.isEmpty() || raw.startsWith('/') || raw.startsWith('#') || URL_SCHEME.containsMatchIn(raw)) m.value
                else "url(\"/" + EpubDocument.resolvePath(baseDir, raw) + "\")"
            }
        }

        private val URL_RE = Regex("""url\(\s*(["']?)([^"')]*)\1\s*\)""", RegexOption.IGNORE_CASE)

        private val URL_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

        private val VIEWBOX_SEPARATOR = Regex("[\\s,]+")

        private val IMPORT_RE = Regex(
            """@import\s+(?:url\(\s*)?["']?([^"')\s;]+)["']?\s*\)?[^;{]*;""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Fixed-layout page size. An SVG document gives it in its root `viewBox` (EPUB 3.3,
         * 8.2.2.6, #26). An XHTML document gives it in `<meta name=viewport>`, else in the
         * width and height of its first `<svg>`.
         */
        private fun parseViewport(tree: KiteXmlNode.Element): Pair<Double, Double>? {
            val root = tree.children.firstOrNull { it is KiteXmlNode.Element } as KiteXmlNode.Element?
            if (root != null && root.tag.equals("svg", true)) {
                // The parser lower-cases attribute names, as SvgImage.fromElement notes.
                val box = (root.attrs["viewBox"] ?: root.attrs["viewbox"])?.trim()?.split(VIEWBOX_SEPARATOR)?.mapNotNull { it.toDoubleOrNull() }
                if (box != null && box.size == 4 && box[2] > 0 && box[3] > 0) return box[2] * 0.75 to box[3] * 0.75
            }
            var result: Pair<Double, Double>? = null
            var svgSize: Pair<Double, Double>? = null
            // EPUB 3.3 gives the viewport in CSS pixels, 0.75pt each, the unit the layout uses (#111).
            fun px(s: String?) = s?.trim()?.removeSuffix("px")?.toDoubleOrNull()?.times(0.75)
            fun walk(el: KiteXmlNode.Element) {
                if (el.tag == "meta" && el.attrs["name"]?.lowercase() == "viewport") {
                    var w: Double? = null; var h: Double? = null
                    for (part in (el.attrs["content"] ?: "").split(',', ';')) {
                        val kv = part.split('=')
                        if (kv.size == 2) when (kv[0].trim().lowercase()) {
                            "width" -> w = px(kv[1]); "height" -> h = px(kv[1])
                        }
                    }
                    if (w != null && h != null && w > 0 && h > 0) result = w to h
                }
                if (svgSize == null && el.tag.equals("svg", true)) {
                    val w = px(el.attrs["width"]); val h = px(el.attrs["height"])
                    if (w != null && h != null && w > 0 && h > 0) svgSize = w to h
                }
                for (c in el.children) if (c is KiteXmlNode.Element) walk(c)
            }
            walk(tree)
            return result ?: svgSize
        }
    }
}

/**
 * Replaces each `epub:switch` in [el] with the children of its first `case` whose required
 * namespace this engine renders, else with those of its `default` (EPUB 3.0.1 Content Documents,
 * 5.1). The switch is deprecated in EPUB 3.3, but books still carry it, and painting every branch
 * showed the content twice (#27).
 */
internal fun resolveSwitches(el: KiteXmlNode.Element) {
    var i = 0
    while (i < el.children.size) {
        val child = el.children[i]
        if (child !is KiteXmlNode.Element) {
            i++
            continue
        }
        if (child.tag != "switch") {
            resolveSwitches(child)
            i++
            continue
        }
        val branches = child.children.filterIsInstance<KiteXmlNode.Element>()
        val chosen = branches.firstOrNull { it.tag == "case" && it.attrs["required-namespace"]?.trim() in SWITCH_NAMESPACES }
            ?: branches.firstOrNull { it.tag == "default" }
        val replacement = chosen?.children.orEmpty()
        el.children.removeAt(i)
        el.children.addAll(i, replacement)
        for (node in replacement) if (node is KiteXmlNode.Element) node.parent = el
        // The branch may hold a switch of its own, so the loop reads the new children too.
    }
}

/** The namespaces a `case` may require for this engine to render it: XHTML, SVG and MathML (#32). */
private val SWITCH_NAMESPACES = setOf("http://www.w3.org/1999/xhtml", "http://www.w3.org/2000/svg", "http://www.w3.org/1998/Math/MathML")
