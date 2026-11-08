package io.github.yuroyami.kitepdf.writer

/** What [PdfEditor.redactRegions] does to an image XObject that a region meets (#926). */
public enum class RedactImages {
    /** Take the image off the page: the safe default, which for a scanned page removes the page. */
    Remove,

    /** Blank the pixels under the regions in a copy of the image, and keep the rest of it. */
    Pixels,
}
