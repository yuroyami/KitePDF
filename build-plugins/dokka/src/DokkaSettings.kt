/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.buildplugins.dokka

import org.jetbrains.amper.plugins.Configurable
import java.nio.file.Path

@Configurable
interface DokkaSettings {
    /** Name of this module in the API site. It must equal the `# Module` header in [includes]. */
    val moduleName: String

    /** Markdown files with module and package docs, such as `Module.md`. */
    val includes: List<Path> get() = emptyList()

    /** Extra CSS files for the HTML pages. */
    val customStyleSheets: List<Path> get() = emptyList()

    /** Directory with FreeMarker templates that replace Dokka's own, such as `includes/header.ftl`. */
    val templatesDir: Path?

    /** Text shown in the page footer. */
    val footerMessage: String?

    /**
     * Platform source folders to document next to the common code, such as `src@apple`. Each folder becomes
     * a platform tab that depends on the common code. Build that platform first, so the toolchain cache holds
     * its standard library: Kotlin/Native for native folders, the stdlib klib for `src@js` and `src@wasm*`.
     */
    val platformSourceDirs: List<String> get() = emptyList()

    /** Web address of the module's `src` directory. When set, each declaration links to its source line. */
    val sourceLinkUrl: String?

    /**
     * Title of the merged API site. Set it in one module only. That module's `dokkaSite` command merges
     * the pages of every module in [siteModules] into `build/dokka/html`.
     */
    val siteName: String?

    /** Names of the modules, in page order, that `dokkaSite` merges. */
    val siteModules: List<String> get() = emptyList()

    /**
     * The `Module.md` files of the other modules in [siteModules]. The start page shows the text under each
     * `# Module <name>` header next to that module.
     */
    val siteIncludes: List<Path> get() = emptyList()
}
