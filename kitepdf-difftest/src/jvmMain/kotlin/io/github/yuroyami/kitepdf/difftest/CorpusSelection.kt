package io.github.yuroyami.kitepdf.difftest

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Shared selection for the test harnesses, never a published library API. */
object CorpusSelection {
    data class Document(val name: String, val file: File)

    /** A bounded, evenly spaced sample including both ends, or every page when requested. */
    data class Pages(val maxPages: Int = 6, val allPages: Boolean = false) {
        init {
            require(maxPages >= 1) { "maxPages must be positive (was $maxPages)" }
        }

        fun indices(pageCount: Int): List<Int> {
            require(pageCount >= 0) { "pageCount must not be negative (was $pageCount)" }
            if (allPages || pageCount <= maxPages) return (0 until pageCount).toList()
            if (maxPages == 1) return listOf(0)
            return List(maxPages) { (it.toLong() * (pageCount - 1) / (maxPages - 1)).toInt() }
        }

        fun describe(): String = if (allPages) "all pages" else "up to $maxPages evenly spaced pages per document"
    }

    fun configuredPages(): Pages = Pages(
        parseMaxPages(System.getProperty("kitepdf.diff.maxpages")),
        parseAllPages(System.getProperty("kitepdf.diff.allpages")),
    )

    fun parseMaxPages(raw: String?): Int {
        if (raw == null) return 6
        val value = raw.toIntOrNull()
        require(value != null && value >= 1) {
            "kitepdf.diff.maxpages must be a positive integer (was '$raw')"
        }
        return value
    }

    fun parseAllPages(raw: String?): Boolean = when (raw) {
        null, "false" -> false
        "true" -> true
        else -> throw IllegalArgumentException("kitepdf.diff.allpages must be true or false (was '$raw')")
    }

    fun repoCorpus(sub: String): File? {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").exists()) return File(directory, "corpus/$sub")
            directory = directory.parentFile
        }
        return null
    }

    fun resolveDirectory(propertyName: String, configuredPath: String?, fallback: File?): File? {
        if (configuredPath == null) return fallback
        val directory = File(configuredPath)
        require(directory.isDirectory) {
            "$propertyName points to a missing or non-directory corpus: ${directory.absolutePath}"
        }
        return directory
    }

    fun configuredDocuments(extension: String, reservedNames: Set<String> = emptySet()): List<Document> {
        val property = if (extension == "pdf") "kitepdf.corpus" else "kitepdf.$extension.corpus"
        return discover(resolveDirectory(property, System.getProperty(property), repoCorpus(extension)), extension, reservedNames)
    }

    /** All matching files, recursively, with stable names that cannot overwrite report artifacts. */
    fun discover(directory: File?, extension: String, reservedNames: Set<String> = emptySet()): List<Document> {
        if (directory == null || !directory.isDirectory) return emptyList()
        val files = directory.walkTopDown().onFail { _, error -> throw error }
            .filter { it.isFile && it.extension.equals(extension, ignoreCase = true) }
            .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }
            .toList()
        val stems = files.groupingBy { it.nameWithoutExtension.lowercase(Locale.ROOT) }.eachCount()
        val reserved = reservedNames.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
        // Reserve every plain stem before assigning generated names, so a later source file
        // cannot collide with the hashed name of an earlier nested file.
        val plainNames = files.filter {
            it.parentFile == directory && safeName.matches(it.nameWithoutExtension) &&
                it.nameWithoutExtension !in setOf(".", "..") &&
                stems.getValue(it.nameWithoutExtension.lowercase(Locale.ROOT)) == 1 &&
                it.nameWithoutExtension.lowercase(Locale.ROOT) !in reserved
        }.associateWith { it.nameWithoutExtension }
        val used = reserved.apply { addAll(plainNames.values.map { it.lowercase(Locale.ROOT) }) }
        return files.map { file ->
            val plainName = plainNames[file]
            if (plainName != null) return@map Document(plainName, file)
            val relative = file.relativeTo(directory).invariantSeparatorsPath
            val prefix = relative.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "-")
                .trim('.', '-').take(80).ifEmpty { "document" }
            val digest = MessageDigest.getInstance("SHA-256").digest(relative.toByteArray(Charsets.UTF_8))
                .take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
            val base = "$prefix-$digest"
            var name = base
            var suffix = 2
            while (!used.add(name.lowercase(Locale.ROOT))) name = "$base-${suffix++}"
            Document(name, file)
        }
    }

    private val safeName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")
}
