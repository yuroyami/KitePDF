package io.github.yuroyami.kitepdf.epub.script

/**
 * A URL record of the WHATWG URL Standard and its basic URL parser, for the `URL` and
 * `URLSearchParams` that a book's scripts see (#520). [parse] resolves a URL against a base as a
 * browser does: special schemes and their default ports, `..` that stops at the root, IPv4 and
 * IPv6 hosts, host names through UTS #46 ([Idna]), and percent-encoding of each component.
 * The setters of the `URL` class run the parser with a state override, as the standard asks.
 *
 * The web-platform-tests data of the standard checks it: urltestdata.json, setters_tests.json
 * and toascii.json.
 */
internal class WhatwgUrl private constructor() {
    var scheme: String = ""
    var username: String = ""
    var password: String = ""

    /** The serialized host: a domain, an IPv4 address, an IPv6 address in brackets, an opaque host, or empty. Null when there is none. */
    var host: String? = null
    var port: Int? = null

    /** The path segments, unless the URL has an opaque path. */
    val path: MutableList<String> = ArrayList()

    /** The opaque path of a URL such as `mailto:a@b`, or null. */
    var opaquePath: String? = null
    var query: String? = null
    var fragment: String? = null

    val isSpecial: Boolean get() = scheme in SPECIAL
    val hasOpaquePath: Boolean get() = opaquePath != null
    private val includesCredentials: Boolean get() = username.isNotEmpty() || password.isNotEmpty()
    val cannotHaveUsernamePasswordPort: Boolean get() = host.isNullOrEmpty() || scheme == "file"

    private fun copy(): WhatwgUrl = WhatwgUrl().also {
        it.scheme = scheme; it.username = username; it.password = password; it.host = host; it.port = port
        it.path += path; it.opaquePath = opaquePath; it.query = query; it.fragment = fragment
    }

    /** The URL serializer. */
    fun href(excludeFragment: Boolean = false): String {
        val out = StringBuilder(scheme).append(':')
        val host = host
        if (host != null) {
            out.append("//")
            if (includesCredentials) {
                out.append(username)
                if (password.isNotEmpty()) out.append(':').append(password)
                out.append('@')
            }
            out.append(host)
            port?.let { out.append(':').append(it) }
        } else if (opaquePath == null && path.size > 1 && path[0].isEmpty()) {
            out.append("/.")
        }
        out.append(pathname)
        query?.let { out.append('?').append(it) }
        if (!excludeFragment) fragment?.let { out.append('#').append(it) }
        return out.toString()
    }

    /** The URL path serializer. */
    val pathname: String get() = opaquePath ?: path.joinToString("") { "/$it" }

    /**
     * The serialized origin: the scheme, host and port of a special URL but `file`, the origin of
     * the URL a `blob:` URL wraps, and `null` for an opaque origin. A URL of [tupleScheme] has a
     * tuple origin too, as a reading system's own scheme does in a browser that registers it.
     */
    fun origin(tupleScheme: String? = null): String = when (scheme) {
        "ftp", "http", "https", "ws", "wss" -> tuple()
        "blob" -> parse(pathname)?.takeIf { it.scheme == "http" || it.scheme == "https" }?.origin() ?: "null"
        else -> if (scheme == tupleScheme && !host.isNullOrEmpty()) tuple() else "null"
    }

    private fun tuple(): String = "$scheme://$host" + (port?.let { ":$it" } ?: "")

    // The getters and setters of the URL class.

    val protocol: String get() = "$scheme:"
    val hostWithPort: String get() = host?.let { h -> port?.let { "$h:$it" } ?: h }.orEmpty()
    val hostname: String get() = host.orEmpty()
    val portString: String get() = port?.toString().orEmpty()
    val search: String get() = query.let { if (it.isNullOrEmpty()) "" else "?$it" }
    val hash: String get() = fragment.let { if (it.isNullOrEmpty()) "" else "#$it" }

    /**
     * Sets the part [name] of the URL to [value] as the setter of that name of the URL class does,
     * or, for `query`, sets the query as a URLSearchParams update does. False when the `href` setter
     * fails to parse, the one setter that throws.
     */
    fun set(name: String, value: String): Boolean {
        when (name) {
            "href" -> {
                val parsed = parse(value) ?: return false
                assign(parsed)
            }
            "protocol" -> basicParse("$value:", null, this, State.SCHEME_START)
            "username" -> if (!cannotHaveUsernamePasswordPort) username = percentEncode(value, ::inUserinfoSet)
            "password" -> if (!cannotHaveUsernamePasswordPort) password = percentEncode(value, ::inUserinfoSet)
            "host" -> if (!hasOpaquePath) basicParse(value, null, this, State.HOST)
            "hostname" -> if (!hasOpaquePath) basicParse(value, null, this, State.HOSTNAME)
            "port" -> if (!cannotHaveUsernamePasswordPort) {
                if (value.isEmpty()) port = null else basicParse(value, null, this, State.PORT)
            }
            "pathname" -> if (!hasOpaquePath) {
                path.clear()
                basicParse(value, null, this, State.PATH_START)
            }
            "search" -> if (value.isEmpty()) {
                query = null
            } else {
                query = ""
                basicParse(value.removePrefix("?"), null, this, State.QUERY)
            }
            "hash" -> if (value.isEmpty()) {
                fragment = null
            } else {
                fragment = ""
                basicParse(value.removePrefix("#"), null, this, State.FRAGMENT)
            }
            "query" -> query = value.ifEmpty { null }
            else -> throw IllegalArgumentException("no URL part $name")
        }
        return true
    }

    private fun assign(other: WhatwgUrl) {
        scheme = other.scheme; username = other.username; password = other.password; host = other.host; port = other.port
        path.clear(); path += other.path; opaquePath = other.opaquePath; query = other.query; fragment = other.fragment
    }

    private fun shortenPath() {
        if (scheme == "file" && path.size == 1 && isNormalizedWindowsDriveLetter(path[0])) return
        if (path.isNotEmpty()) path.removeAt(path.size - 1)
    }

    private enum class State {
        SCHEME_START, SCHEME, NO_SCHEME, SPECIAL_RELATIVE_OR_AUTHORITY, PATH_OR_AUTHORITY, RELATIVE, RELATIVE_SLASH,
        SPECIAL_AUTHORITY_SLASHES, SPECIAL_AUTHORITY_IGNORE_SLASHES, AUTHORITY, HOST, HOSTNAME, PORT, FILE, FILE_SLASH,
        FILE_HOST, PATH_START, PATH, OPAQUE_PATH, QUERY, FRAGMENT,
    }

    companion object {
        private val SPECIAL = setOf("ftp", "file", "http", "https", "ws", "wss")
        private const val EOF = -1

        private fun defaultPort(scheme: String): Int? = when (scheme) {
            "ftp" -> 21
            "http", "ws" -> 80
            "https", "wss" -> 443
            else -> null
        }

        /** The API URL parser: [input] against [base], each a string, or null when either fails to parse. */
        fun parse(input: String, base: String?): WhatwgUrl? {
            val parsedBase = if (base == null) null else (parse(base) ?: return null)
            return parse(input, parsedBase)
        }

        /** The basic URL parser without a state override: [input] against [base], or null on failure. */
        fun parse(input: String, base: WhatwgUrl? = null): WhatwgUrl? {
            val url = WhatwgUrl()
            return if (basicParse(input, base, url, null)) url else null
        }

        /**
         * The basic URL parser of the URL Standard, 4.4. With [stateOverride] it changes [url] in
         * place, as a setter does, and true means it did not fail. The input is a scalar value
         * string: a lone surrogate reads as U+FFFD.
         */
        private fun basicParse(text: String, base: WhatwgUrl?, url: WhatwgUrl, stateOverride: State?): Boolean {
            var cps = codePoints(text).let { a -> IntArray(a.size) { if (a[it] in 0xD800..0xDFFF) 0xFFFD else a[it] } }
            if (stateOverride == null) {
                var start = 0
                var end = cps.size
                while (start < end && cps[start] <= 0x20) start++
                while (end > start && cps[end - 1] <= 0x20) end--
                cps = cps.copyOfRange(start, end)
            }
            if (cps.any { it == 0x09 || it == 0x0A || it == 0x0D }) cps = cps.filter { it != 0x09 && it != 0x0A && it != 0x0D }.toIntArray()
            val n = cps.size
            var state = stateOverride ?: State.SCHEME_START
            val buffer = StringBuilder()
            var atSignSeen = false
            var insideBrackets = false
            var passwordTokenSeen = false
            var pointer = 0
            fun at(i: Int): Int = if (i in 0 until n) cps[i] else EOF
            fun remainingStartsWith(cp: Int): Boolean = at(pointer + 1) == cp
            fun bufferIsSpecial(): Boolean = buffer.toString() in SPECIAL

            while (true) {
                val c = at(pointer)
                when (state) {
                    State.SCHEME_START -> when {
                        isAsciiAlpha(c) -> { buffer.append(c.toChar().lowercaseChar()); state = State.SCHEME }
                        stateOverride == null -> { state = State.NO_SCHEME; pointer-- }
                        else -> return false
                    }
                    State.SCHEME -> when {
                        isAsciiAlphanumeric(c) || c == '+'.code || c == '-'.code || c == '.'.code -> buffer.append(c.toChar().lowercaseChar())
                        c == ':'.code -> {
                            val candidate = buffer.toString()
                            if (stateOverride != null) {
                                if (url.isSpecial != (candidate in SPECIAL)) return true
                                if ((url.includesCredentials || url.port != null) && candidate == "file") return true
                                if (url.scheme == "file" && url.host == "") return true
                            }
                            url.scheme = candidate
                            if (stateOverride != null) {
                                if (url.port == defaultPort(url.scheme)) url.port = null
                                return true
                            }
                            buffer.clear()
                            when {
                                url.scheme == "file" -> state = State.FILE
                                url.isSpecial && base != null && base.scheme == url.scheme -> state = State.SPECIAL_RELATIVE_OR_AUTHORITY
                                url.isSpecial -> state = State.SPECIAL_AUTHORITY_SLASHES
                                remainingStartsWith('/'.code) -> { state = State.PATH_OR_AUTHORITY; pointer++ }
                                else -> { url.opaquePath = ""; state = State.OPAQUE_PATH }
                            }
                        }
                        stateOverride == null -> { buffer.clear(); state = State.NO_SCHEME; pointer = -1 }
                        else -> return false
                    }
                    State.NO_SCHEME -> when {
                        base == null || (base.hasOpaquePath && c != '#'.code) -> return false
                        base.hasOpaquePath && c == '#'.code -> {
                            url.scheme = base.scheme
                            url.opaquePath = base.opaquePath
                            url.query = base.query
                            url.fragment = ""
                            state = State.FRAGMENT
                        }
                        base.scheme != "file" -> { state = State.RELATIVE; pointer-- }
                        else -> { state = State.FILE; pointer-- }
                    }
                    State.SPECIAL_RELATIVE_OR_AUTHORITY -> if (c == '/'.code && remainingStartsWith('/'.code)) {
                        state = State.SPECIAL_AUTHORITY_IGNORE_SLASHES
                        pointer++
                    } else {
                        state = State.RELATIVE
                        pointer--
                    }
                    State.PATH_OR_AUTHORITY -> if (c == '/'.code) state = State.AUTHORITY else { state = State.PATH; pointer-- }
                    State.RELATIVE -> {
                        base!!
                        url.scheme = base.scheme
                        when {
                            c == '/'.code -> state = State.RELATIVE_SLASH
                            url.isSpecial && c == '\\'.code -> state = State.RELATIVE_SLASH
                            else -> {
                                url.username = base.username; url.password = base.password
                                url.host = base.host; url.port = base.port
                                url.path.clear(); url.path += base.path
                                url.query = base.query
                                when {
                                    c == '?'.code -> { url.query = ""; state = State.QUERY }
                                    c == '#'.code -> { url.fragment = ""; state = State.FRAGMENT }
                                    c != EOF -> {
                                        url.query = null
                                        url.shortenPath()
                                        state = State.PATH
                                        pointer--
                                    }
                                }
                            }
                        }
                    }
                    State.RELATIVE_SLASH -> when {
                        url.isSpecial && (c == '/'.code || c == '\\'.code) -> state = State.SPECIAL_AUTHORITY_IGNORE_SLASHES
                        c == '/'.code -> state = State.AUTHORITY
                        else -> {
                            base!!
                            url.username = base.username; url.password = base.password
                            url.host = base.host; url.port = base.port
                            state = State.PATH
                            pointer--
                        }
                    }
                    State.SPECIAL_AUTHORITY_SLASHES -> if (c == '/'.code && remainingStartsWith('/'.code)) {
                        state = State.SPECIAL_AUTHORITY_IGNORE_SLASHES
                        pointer++
                    } else {
                        state = State.SPECIAL_AUTHORITY_IGNORE_SLASHES
                        pointer--
                    }
                    State.SPECIAL_AUTHORITY_IGNORE_SLASHES -> if (c != '/'.code && c != '\\'.code) {
                        state = State.AUTHORITY
                        pointer--
                    }
                    State.AUTHORITY -> when {
                        c == '@'.code -> {
                            if (atSignSeen) buffer.insert(0, "%40")
                            atSignSeen = true
                            for (cp in codePoints(buffer.toString())) {
                                if (cp == ':'.code && !passwordTokenSeen) { passwordTokenSeen = true; continue }
                                val encoded = percentEncode(cp, ::inUserinfoSet)
                                if (passwordTokenSeen) url.password += encoded else url.username += encoded
                            }
                            buffer.clear()
                        }
                        c == EOF || c == '/'.code || c == '?'.code || c == '#'.code || (url.isSpecial && c == '\\'.code) -> {
                            if (atSignSeen && buffer.isEmpty()) return false
                            pointer -= codePoints(buffer.toString()).size + 1
                            buffer.clear()
                            state = State.HOST
                        }
                        else -> appendCodePoint(buffer, c)
                    }
                    State.HOST, State.HOSTNAME -> when {
                        stateOverride != null && url.scheme == "file" -> { pointer--; state = State.FILE_HOST }
                        c == ':'.code && !insideBrackets -> {
                            if (buffer.isEmpty()) return false
                            if (stateOverride == State.HOSTNAME) return false
                            val host = parseHost(buffer.toString(), !url.isSpecial) ?: return false
                            url.host = host
                            buffer.clear()
                            state = State.PORT
                        }
                        c == EOF || c == '/'.code || c == '?'.code || c == '#'.code || (url.isSpecial && c == '\\'.code) -> {
                            pointer--
                            if (url.isSpecial && buffer.isEmpty()) return false
                            if (stateOverride != null && buffer.isEmpty() && (url.includesCredentials || url.port != null)) return false
                            val host = parseHost(buffer.toString(), !url.isSpecial) ?: return false
                            url.host = host
                            buffer.clear()
                            state = State.PATH_START
                            if (stateOverride != null) return true
                        }
                        else -> {
                            if (c == '['.code) insideBrackets = true
                            if (c == ']'.code) insideBrackets = false
                            appendCodePoint(buffer, c)
                        }
                    }
                    State.PORT -> when {
                        isAsciiDigit(c) -> buffer.append(c.toChar())
                        c == EOF || c == '/'.code || c == '?'.code || c == '#'.code || (url.isSpecial && c == '\\'.code) || stateOverride != null -> {
                            if (buffer.isNotEmpty()) {
                                // Leading zeros are allowed, and any number beyond 65535 fails, so stop counting there.
                                var port = 0
                                for (d in buffer) {
                                    port = port * 10 + (d - '0')
                                    if (port > 65535) return false
                                }
                                url.port = if (port == defaultPort(url.scheme)) null else port
                                buffer.clear()
                                if (stateOverride != null) return true
                            }
                            if (stateOverride != null) return false
                            state = State.PATH_START
                            pointer--
                        }
                        else -> return false
                    }
                    State.FILE -> {
                        url.scheme = "file"
                        url.host = ""
                        if (c == '/'.code || c == '\\'.code) {
                            state = State.FILE_SLASH
                        } else if (base != null && base.scheme == "file") {
                            url.host = base.host
                            url.path.clear(); url.path += base.path
                            url.query = base.query
                            when {
                                c == '?'.code -> { url.query = ""; state = State.QUERY }
                                c == '#'.code -> { url.fragment = ""; state = State.FRAGMENT }
                                c != EOF -> {
                                    url.query = null
                                    if (!startsWithWindowsDriveLetter(cps, pointer)) url.shortenPath() else url.path.clear()
                                    state = State.PATH
                                    pointer--
                                }
                            }
                        } else {
                            state = State.PATH
                            pointer--
                        }
                    }
                    State.FILE_SLASH -> if (c == '/'.code || c == '\\'.code) {
                        state = State.FILE_HOST
                    } else {
                        if (base != null && base.scheme == "file") {
                            url.host = base.host
                            if (!startsWithWindowsDriveLetter(cps, pointer) && base.path.isNotEmpty() && isNormalizedWindowsDriveLetter(base.path[0])) {
                                url.path += base.path[0]
                            }
                        }
                        state = State.PATH
                        pointer--
                    }
                    State.FILE_HOST -> if (c == EOF || c == '/'.code || c == '\\'.code || c == '?'.code || c == '#'.code) {
                        pointer--
                        if (stateOverride == null && isWindowsDriveLetter(buffer.toString())) {
                            // The buffer stays, and the path state reads it.
                            state = State.PATH
                        } else if (buffer.isEmpty()) {
                            url.host = ""
                            if (stateOverride != null) return true
                            state = State.PATH_START
                        } else {
                            var host = parseHost(buffer.toString(), !url.isSpecial) ?: return false
                            if (host == "localhost") host = ""
                            url.host = host
                            if (stateOverride != null) return true
                            buffer.clear()
                            state = State.PATH_START
                        }
                    } else {
                        appendCodePoint(buffer, c)
                    }
                    State.PATH_START -> when {
                        url.isSpecial -> {
                            state = State.PATH
                            if (c != '/'.code && c != '\\'.code) pointer--
                        }
                        stateOverride == null && c == '?'.code -> { url.query = ""; state = State.QUERY }
                        stateOverride == null && c == '#'.code -> { url.fragment = ""; state = State.FRAGMENT }
                        c != EOF -> {
                            state = State.PATH
                            if (c != '/'.code) pointer--
                        }
                        stateOverride != null && url.host == null -> url.path += ""
                    }
                    State.PATH -> if (c == EOF || c == '/'.code || (url.isSpecial && c == '\\'.code) ||
                        (stateOverride == null && (c == '?'.code || c == '#'.code))
                    ) {
                        val segment = buffer.toString()
                        val slash = c == '/'.code || (url.isSpecial && c == '\\'.code)
                        if (isDoubleDot(segment)) {
                            url.shortenPath()
                            if (!slash) url.path += ""
                        } else if (isSingleDot(segment) && !slash) {
                            url.path += ""
                        } else if (!isSingleDot(segment)) {
                            if (url.scheme == "file" && url.path.isEmpty() && isWindowsDriveLetter(segment)) {
                                url.path += segment.substring(0, 1) + ":"
                            } else {
                                url.path += segment
                            }
                        }
                        buffer.clear()
                        if (c == '?'.code) { url.query = ""; state = State.QUERY }
                        if (c == '#'.code) { url.fragment = ""; state = State.FRAGMENT }
                    } else {
                        buffer.append(percentEncode(c, ::inPathSet))
                    }
                    State.OPAQUE_PATH -> when {
                        c == '?'.code -> { url.query = ""; state = State.QUERY }
                        c == '#'.code -> { url.fragment = ""; state = State.FRAGMENT }
                        c == ' '.code -> {
                            val next = at(pointer + 1)
                            url.opaquePath += if (next == '?'.code || next == '#'.code) "%20" else " "
                        }
                        c != EOF -> url.opaquePath += percentEncode(c, ::inC0ControlSet)
                    }
                    State.QUERY -> if ((stateOverride == null && c == '#'.code) || c == EOF) {
                        val set: (Int) -> Boolean = if (url.isSpecial) ::inSpecialQuerySet else ::inQuerySet
                        url.query = url.query.orEmpty() + percentEncode(buffer.toString(), set)
                        buffer.clear()
                        if (c == '#'.code) { url.fragment = ""; state = State.FRAGMENT }
                    } else if (c != EOF) {
                        appendCodePoint(buffer, c)
                    }
                    State.FRAGMENT -> if (c != EOF) url.fragment = url.fragment.orEmpty() + percentEncode(c, ::inFragmentSet)
                }
                if (pointer >= n) break
                pointer++
            }
            return true
        }

        private fun isAsciiAlpha(c: Int) = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code
        private fun isAsciiDigit(c: Int) = c in '0'.code..'9'.code
        private fun isAsciiAlphanumeric(c: Int) = isAsciiAlpha(c) || isAsciiDigit(c)
        private fun isAsciiHexDigit(c: Int) = isAsciiDigit(c) || c in 'a'.code..'f'.code || c in 'A'.code..'F'.code

        private fun isWindowsDriveLetter(s: String) = s.length == 2 && isAsciiAlpha(s[0].code) && (s[1] == ':' || s[1] == '|')
        private fun isNormalizedWindowsDriveLetter(s: String) = s.length == 2 && isAsciiAlpha(s[0].code) && s[1] == ':'

        /** Whether the code points of [cps] from [from] on start with a Windows drive letter. */
        private fun startsWithWindowsDriveLetter(cps: IntArray, from: Int): Boolean {
            val left = cps.size - from
            if (left < 2 || !isAsciiAlpha(cps[from]) || (cps[from + 1] != ':'.code && cps[from + 1] != '|'.code)) return false
            return left == 2 || cps[from + 2].let { it == '/'.code || it == '\\'.code || it == '?'.code || it == '#'.code }
        }

        private fun isSingleDot(s: String) = s == "." || s.equals("%2e", ignoreCase = true)
        private fun isDoubleDot(s: String) = when (s.lowercase()) {
            "..", ".%2e", "%2e.", "%2e%2e" -> true
            else -> false
        }

        // The percent-encode sets of the URL Standard, 1.3.

        fun inC0ControlSet(c: Int) = c < 0x20 || c > 0x7E
        fun inFragmentSet(c: Int) = inC0ControlSet(c) || c == ' '.code || c == '"'.code || c == '<'.code || c == '>'.code || c == '`'.code
        fun inQuerySet(c: Int) = inC0ControlSet(c) || c == ' '.code || c == '"'.code || c == '#'.code || c == '<'.code || c == '>'.code
        fun inSpecialQuerySet(c: Int) = inQuerySet(c) || c == '\''.code
        fun inPathSet(c: Int) = inQuerySet(c) || c == '?'.code || c == '^'.code || c == '`'.code || c == '{'.code || c == '}'.code
        fun inUserinfoSet(c: Int) = inPathSet(c) || c == '/'.code || c == ':'.code || c == ';'.code || c == '='.code || c == '@'.code ||
            c in '['.code..']'.code || c == '|'.code
        fun inComponentSet(c: Int) = inUserinfoSet(c) || c in '$'.code..'&'.code || c == '+'.code || c == ','.code
        fun inFormSet(c: Int) = inComponentSet(c) || c == '!'.code || c in '\''.code..')'.code || c == '~'.code

        private const val HEX = "0123456789ABCDEF"

        /** UTF-8 percent-encode of one code point. */
        fun percentEncode(cp: Int, set: (Int) -> Boolean): String {
            if (!set(cp)) return if (cp < 0x10000) cp.toChar().toString() else fromCodePoints(intArrayOf(cp))
            val out = StringBuilder()
            for (b in utf8(cp)) out.append('%').append(HEX[b shr 4]).append(HEX[b and 15])
            return out.toString()
        }

        /** UTF-8 percent-encode of a string, a space as `+` when [spaceAsPlus]. */
        fun percentEncode(text: String, set: (Int) -> Boolean, spaceAsPlus: Boolean = false): String {
            val out = StringBuilder(text.length)
            for (cp in codePoints(text)) {
                val scalar = if (cp in 0xD800..0xDFFF) 0xFFFD else cp
                if (spaceAsPlus && scalar == ' '.code) out.append('+') else out.append(percentEncode(scalar, set))
            }
            return out.toString()
        }

        private fun utf8(cp: Int): IntArray = when {
            cp < 0x80 -> intArrayOf(cp)
            cp < 0x800 -> intArrayOf(0xC0 or (cp shr 6), 0x80 or (cp and 0x3F))
            cp < 0x10000 -> intArrayOf(0xE0 or (cp shr 12), 0x80 or (cp shr 6 and 0x3F), 0x80 or (cp and 0x3F))
            else -> intArrayOf(0xF0 or (cp shr 18), 0x80 or (cp shr 12 and 0x3F), 0x80 or (cp shr 6 and 0x3F), 0x80 or (cp and 0x3F))
        }

        /**
         * The bytes of [text] in UTF-8 with each `%XX` decoded: the percent-decoding of the URL
         * Standard. A lone surrogate encodes as U+FFFD, the same on each platform.
         */
        fun percentDecode(text: String): ByteArray {
            val bytes = utf8(text)
            val out = ByteArray(bytes.size)
            var n = 0
            var i = 0
            while (i < bytes.size) {
                val b = bytes[i].toInt()
                if (b == '%'.code && i + 2 < bytes.size && isAsciiHexDigit(bytes[i + 1].toInt()) && isAsciiHexDigit(bytes[i + 2].toInt())) {
                    out[n++] = ((hexValue(bytes[i + 1].toInt()) shl 4) or hexValue(bytes[i + 2].toInt())).toByte()
                    i += 3
                } else {
                    out[n++] = bytes[i++]
                }
            }
            return out.copyOf(n)
        }

        /** [text] in UTF-8, a lone surrogate as U+FFFD. */
        private fun utf8(text: String): ByteArray {
            if (text.all { it.code < 0x80 }) return ByteArray(text.length) { text[it].code.toByte() }
            val out = ArrayList<Byte>(text.length * 2)
            for (cp in codePoints(text)) for (b in utf8(if (cp in 0xD800..0xDFFF) 0xFFFD else cp)) out += b.toByte()
            return out.toByteArray()
        }

        private fun hexValue(c: Int): Int = when (c) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            else -> c - 'A'.code + 10
        }

        /**
         * UTF-8 decode without BOM of the Encoding Standard: a BOM stays, and each maximal ill-formed
         * subpart reads as one U+FFFD, the same on every Kotlin target.
         */
        fun utf8Decode(bytes: ByteArray): String {
            val out = StringBuilder(bytes.size)
            var i = 0
            while (i < bytes.size) {
                val b0 = bytes[i].toInt() and 0xFF
                if (b0 < 0x80) { out.append(b0.toChar()); i++; continue }
                val (need, min, lowerSecond, upperSecond) = when (b0) {
                    in 0xC2..0xDF -> Quad(1, 0x80, 0x80, 0xBF)
                    0xE0 -> Quad(2, 0x800, 0xA0, 0xBF)
                    in 0xE1..0xEC, 0xEE, 0xEF -> Quad(2, 0x800, 0x80, 0xBF)
                    0xED -> Quad(2, 0x800, 0x80, 0x9F)
                    0xF0 -> Quad(3, 0x10000, 0x90, 0xBF)
                    in 0xF1..0xF3 -> Quad(3, 0x10000, 0x80, 0xBF)
                    0xF4 -> Quad(3, 0x10000, 0x80, 0x8F)
                    else -> { out.append('�'); i++; continue }
                }
                var cp = b0 and (0x3F shr need)
                var j = 1
                var ok = true
                while (j <= need) {
                    val b = if (i + j < bytes.size) bytes[i + j].toInt() and 0xFF else -1
                    val lo = if (j == 1) lowerSecond else 0x80
                    val hi = if (j == 1) upperSecond else 0xBF
                    if (b !in lo..hi) { ok = false; break }
                    cp = (cp shl 6) or (b and 0x3F)
                    j++
                }
                if (ok && cp >= min) {
                    appendCodePoint(out, cp)
                    i += need + 1
                } else {
                    out.append('�')
                    i += j
                }
            }
            return out.toString()
        }

        private data class Quad(val a: Int, val b: Int, val c: Int, val d: Int)

        // Host parsing, the URL Standard, 3.5.

        private fun isForbiddenHost(c: Int) = when (c) {
            0x00, 0x09, 0x0A, 0x0D, ' '.code, '#'.code, '/'.code, ':'.code, '<'.code, '>'.code, '?'.code, '@'.code,
            '['.code, '\\'.code, ']'.code, '^'.code, '|'.code -> true
            else -> false
        }

        private fun isForbiddenDomain(c: Int) = isForbiddenHost(c) || c <= 0x1F || c == '%'.code || c == 0x7F

        /** The host parser: the serialized host of [input], or null on failure. */
        fun parseHost(input: String, isOpaque: Boolean): String? {
            if (input.startsWith("[")) {
                if (!input.endsWith("]")) return null
                val address = parseIpv6(codePoints(input.substring(1, input.length - 1))) ?: return null
                return "[" + serializeIpv6(address) + "]"
            }
            if (isOpaque) {
                if (codePoints(input).any(::isForbiddenHost)) return null
                return percentEncode(input, ::inC0ControlSet)
            }
            val domain = utf8Decode(percentDecode(input))
            val ascii = domainToAscii(domain) ?: return null
            if (endsInANumber(ascii)) return parseIpv4(ascii)?.let(::serializeIpv4)
            return ascii
        }

        /** The domain parser with beStrict false: an ASCII domain lower-cased, any other through UTS #46 ToASCII. */
        fun domainToAscii(domain: String): String? {
            val result = if (domain.all { it.code < 0x80 }) {
                domain.lowercase()
            } else {
                Idna.toAscii(domain, checkHyphens = false, checkBidi = true, checkJoiners = true, useStd3AsciiRules = false, verifyDnsLength = false)
                    ?: return null
            }
            if (result.isEmpty() || result.any { isForbiddenDomain(it.code) }) return null
            return result
        }

        private fun endsInANumber(input: String): Boolean {
            val parts = input.split('.').toMutableList()
            if (parts.last().isEmpty()) {
                if (parts.size == 1) return false
                parts.removeAt(parts.size - 1)
            }
            val last = parts.last()
            if (last.isNotEmpty() && last.all { isAsciiDigit(it.code) }) return true
            return parseIpv4Number(last) != null
        }

        /** An IPv4 number of the host parser, or null on failure. Beyond 2^32 it stops counting, since any such number fails. */
        private fun parseIpv4Number(text: String): Long? {
            if (text.isEmpty()) return null
            var input = text
            var radix = 10
            if (input.length >= 2 && (input.startsWith("0x") || input.startsWith("0X"))) {
                input = input.substring(2); radix = 16
            } else if (input.length >= 2 && input[0] == '0') {
                input = input.substring(1); radix = 8
            }
            if (input.isEmpty()) return 0
            var value = 0L
            for (ch in input) {
                val d = ch.digitToIntOrNull(radix) ?: return null
                if (value <= 0xFFFFFFFFL) value = value * radix + d
            }
            return value
        }

        private fun parseIpv4(input: String): Long? {
            val parts = input.split('.').toMutableList()
            if (parts.last().isEmpty() && parts.size > 1) parts.removeAt(parts.size - 1)
            if (parts.size > 4) return null
            val numbers = parts.map { parseIpv4Number(it) ?: return null }
            if (numbers.dropLast(1).any { it > 255 }) return null
            var limit = 1L
            repeat(5 - numbers.size) { limit *= 256 }
            if (numbers.last() >= limit) return null
            var ipv4 = numbers.last()
            for ((i, n) in numbers.dropLast(1).withIndex()) {
                var factor = 1L
                repeat(3 - i) { factor *= 256 }
                ipv4 += n * factor
            }
            return ipv4
        }

        private fun serializeIpv4(address: Long): String =
            listOf(address shr 24 and 255, address shr 16 and 255, address shr 8 and 255, address and 255).joinToString(".")

        private fun parseIpv6(input: IntArray): IntArray? {
            val address = IntArray(8)
            var pieceIndex = 0
            var compress = -1
            var pointer = 0
            fun c(): Int = if (pointer < input.size) input[pointer] else EOF
            if (c() == ':'.code) {
                if (pointer + 1 >= input.size || input[pointer + 1] != ':'.code) return null
                pointer += 2
                pieceIndex++
                compress = pieceIndex
            }
            while (c() != EOF) {
                if (pieceIndex == 8) return null
                if (c() == ':'.code) {
                    if (compress != -1) return null
                    pointer++
                    pieceIndex++
                    compress = pieceIndex
                    continue
                }
                var value = 0
                var length = 0
                while (length < 4 && isAsciiHexDigit(c())) {
                    value = value * 16 + hexValue(c())
                    pointer++
                    length++
                }
                if (c() == '.'.code) {
                    if (length == 0) return null
                    pointer -= length
                    if (pieceIndex > 6) return null
                    var numbersSeen = 0
                    while (c() != EOF) {
                        var ipv4Piece = -1
                        if (numbersSeen > 0) {
                            if (c() == '.'.code && numbersSeen < 4) pointer++ else return null
                        }
                        if (!isAsciiDigit(c())) return null
                        while (isAsciiDigit(c())) {
                            val number = c() - '0'.code
                            ipv4Piece = when (ipv4Piece) {
                                -1 -> number
                                0 -> return null
                                else -> ipv4Piece * 10 + number
                            }
                            if (ipv4Piece > 255) return null
                            pointer++
                        }
                        address[pieceIndex] = address[pieceIndex] * 0x100 + ipv4Piece
                        numbersSeen++
                        if (numbersSeen == 2 || numbersSeen == 4) pieceIndex++
                    }
                    if (numbersSeen != 4) return null
                    break
                } else if (c() == ':'.code) {
                    pointer++
                    if (c() == EOF) return null
                } else if (c() != EOF) {
                    return null
                }
                address[pieceIndex] = value
                pieceIndex++
            }
            if (compress != -1) {
                var swaps = pieceIndex - compress
                pieceIndex = 7
                while (pieceIndex != 0 && swaps > 0) {
                    val t = address[pieceIndex]
                    address[pieceIndex] = address[compress + swaps - 1]
                    address[compress + swaps - 1] = t
                    pieceIndex--
                    swaps--
                }
            } else if (pieceIndex != 8) {
                return null
            }
            return address
        }

        private fun serializeIpv6(address: IntArray): String {
            // The first longest run of two or more zero pieces is compressed.
            var compress = -1
            var longest = 1
            var i = 0
            while (i < 8) {
                if (address[i] == 0) {
                    var j = i
                    while (j < 8 && address[j] == 0) j++
                    if (j - i > longest) { longest = j - i; compress = i }
                    i = j
                } else {
                    i++
                }
            }
            val out = StringBuilder()
            var ignore0 = false
            for (index in 0 until 8) {
                if (ignore0 && address[index] == 0) continue
                ignore0 = false
                if (compress == index) {
                    out.append(if (index == 0) "::" else ":")
                    ignore0 = true
                    continue
                }
                out.append(address[index].toString(16))
                if (index != 7) out.append(':')
            }
            return out.toString()
        }

        // application/x-www-form-urlencoded, the URL Standard, 5.

        /** The name-value pairs of [input], the application/x-www-form-urlencoded parser on its UTF-8. */
        fun parseForm(input: String): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            for (sequence in input.split('&')) {
                if (sequence.isEmpty()) continue
                val eq = sequence.indexOf('=')
                val name = if (eq < 0) sequence else sequence.substring(0, eq)
                val value = if (eq < 0) "" else sequence.substring(eq + 1)
                out += utf8Decode(percentDecode(name.replace('+', ' '))) to utf8Decode(percentDecode(value.replace('+', ' ')))
            }
            return out
        }

        /** The application/x-www-form-urlencoded serializer. */
        fun serializeForm(pairs: List<Pair<String, String>>): String =
            pairs.joinToString("&") { (name, value) -> percentEncode(name, ::inFormSet, spaceAsPlus = true) + "=" + percentEncode(value, ::inFormSet, spaceAsPlus = true) }
    }
}
