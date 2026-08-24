package io.github.yuroyami.kitepdf.epub

/** Moved to core so non-EPUB zip formats (CBZ) can read archives too. */
@Deprecated(
    "ZipReader moved to io.github.yuroyami.kitepdf.core.zip",
    ReplaceWith("ZipReader", "io.github.yuroyami.kitepdf.core.zip.ZipReader"),
)
public typealias ZipReader = io.github.yuroyami.kitepdf.core.zip.ZipReader
