package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.zip.ZipReader

import io.github.yuroyami.kitepdf.core.KiteLock
import io.github.yuroyami.kitepdf.core.withLock
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
    val tree: HtmlNode.Element,
    val rules: List<StyleRule>,
    val docDir: String,
    /** The `<meta name=viewport>` size, or null when the document declares none. */
    val viewport: Pair<Double, Double>?,
    /** Zip path of this spine document, the key for href -> page navigation. */
    val path: String,
    /** Faces from this document's own inline `<style>` blocks. Almost always empty. */
    val localFaces: List<EmbeddedFace>,
)

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
    val fixedLayout: Boolean,
) {

    val spineCount: Int get() = spinePaths.size

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
        val tree = HtmlParser.parse(zip.readText(path) ?: "")
        val rules = ArrayList<StyleRule>()
        val faces = ArrayList<EmbeddedFace>()
        walkStyleSources(
            tree, docDir,
            onLink = { sheet -> rules.addAll(sheetRules(sheet)) },
            onInline = { text ->
                val css = CssParser.parseAll(inlineImports(zip, text, docDir, 0, HashSet()), Origin.AUTHOR)
                rules.addAll(css.rules)
                for (rule in css.fontFaces) loadFace(rule, docDir)?.let(faces::add)
            },
        )
        return ParsedSpine(tree, rules, docDir, parseViewport(tree), path, faces)
    }

    /** One stylesheet's rules, parsed once however many chapters link it. */
    private fun sheetRules(path: String): List<StyleRule> {
        sheetLock.withLock { sheetCache[path] }?.let { return it }
        val text = zip.readText(path) ?: ""
        val rules = CssParser.parse(inlineImports(zip, text, dirOf(path), 0, hashSetOf(path)), Origin.AUTHOR)
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
        val path = fontPath(dir, url)
        val raw = zip.read(path) ?: return null
        val bytes = obfuscation[path]?.let { Deobfuscate.deobfuscate(raw, it, opf.uniqueId ?: "") } ?: raw
        return FontRegistry.face(rule.family, rule.bold, rule.italic, bytes)
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
            val contentPaths = opf.spineIdrefs.mapNotNull { opf.itemsById[it]?.href }
                .map { EpubDocument.resolvePath(opf.baseDir, it) }
            if (contentPaths.isEmpty()) throw EpubFormatException("spine is empty in $opfPath")

            val present = contentPaths.filter { it in zip.names }
            if (present.isEmpty()) throw EpubFormatException("spine has no readable documents")

            return ParsedEpub(
                zip = zip,
                opf = opf,
                spinePaths = present,
                metadata = buildMetadata(opf),
                toc = TocParser.parse(zip, opf, contentPaths) { base, href -> EpubDocument.resolvePath(base, href) },
                baseDir = if (opf.direction?.lowercase() == "rtl") Direction.RTL else Direction.LTR,
                fixedLayout = opf.renditionLayout == "pre-paginated" ||
                    contentPaths.indices.all { opf.fixedLayoutAt(it) },
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
                // declares no direction of its own (ledger Part 13).
                rightToLeft = opf.direction?.lowercase() == "rtl" ||
                    (opf.direction == null && opf.primaryWritingMode?.lowercase() == "vertical-rl"),
            )
        }

        /** META-INF/encryption.xml -> obfuscated zip path -> algorithm URI. */
        private fun parseEncryption(zip: ZipReader): Map<String, String> {
            val xml = zip.readText("META-INF/encryption.xml") ?: return emptyMap()
            val map = HashMap<String, String>()
            var algo: String? = null
            for (t in MiniXml.tokenize(xml)) if (t is XmlToken.Open) when (t.name) {
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
            for (t in MiniXml.tokenize(xml)) {
                if (t is XmlToken.Open && t.name == "rootfile") t.attrs["full-path"]?.let { return it }
            }
            return null
        }

        /** Visit a document's author CSS in document order: linked sheets, then `<style>` blocks. */
        private fun walkStyleSources(
            tree: HtmlNode.Element,
            docDir: String,
            onLink: (String) -> Unit,
            onInline: (String) -> Unit,
        ) {
            fun walk(el: HtmlNode.Element) {
                when (el.tag) {
                    "link" -> {
                        val rel = el.attrs["rel"]?.lowercase() ?: ""
                        val href = el.attrs["href"]
                        if ("stylesheet" in rel && href != null) onLink(EpubDocument.resolvePath(docDir, href))
                    }
                    "style" -> onInline(buildString { for (c in el.children) if (c is HtmlNode.Text) append(c.text) })
                    else -> for (c in el.children) if (c is HtmlNode.Element) walk(c)
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
                else zip.readText(path)?.let { inlineImports(zip, it, dirOf(path), depth + 1, visited) } ?: ""
            }
        }

        private fun dirOf(path: String): String = path.substringBeforeLast('/', "")

        private val IMPORT_RE = Regex(
            """@import\s+(?:url\(\s*)?["']?([^"')\s;]+)["']?\s*\)?[^;{]*;""",
            RegexOption.IGNORE_CASE,
        )

        /** Fixed-layout page size: the `<meta name=viewport>` width/height, else a root `<svg>`'s. */
        private fun parseViewport(tree: HtmlNode.Element): Pair<Double, Double>? {
            var result: Pair<Double, Double>? = null
            var svgSize: Pair<Double, Double>? = null
            fun px(s: String?) = s?.trim()?.removeSuffix("px")?.toDoubleOrNull()
            fun walk(el: HtmlNode.Element) {
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
                for (c in el.children) if (c is HtmlNode.Element) walk(c)
            }
            walk(tree)
            return result ?: svgSize
        }
    }
}
