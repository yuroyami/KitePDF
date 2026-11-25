package io.github.yuroyami.kitepdf.document

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.wcstr
import platform.posix._wfopen
import platform.posix._wremove
import platform.posix.fclose
import platform.posix.fwrite
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals

/** `KiteDoc.openFile` opens a Windows path with a name outside ASCII (#1027). */
@OptIn(ExperimentalForeignApi::class)
class OpenFileWindowsTest {

    @Test
    fun opens_a_path_with_a_name_outside_ascii() {
        val dir = getenv("TEMP")?.toKString() ?: "."
        val path = "$dir\\Zoë-книга-${kotlin.random.Random.nextInt(1_000_000)}.pdf"
        val bytes = samplePdf()
        val file = _wfopen(path.wcstr, "wb".wcstr) ?: error("cannot create $path")
        try {
            bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), file) }
        } finally {
            fclose(file)
        }
        try {
            assertEquals(2, KiteDoc.openFile(path).pageCount)
        } finally {
            _wremove(path.wcstr)
        }
    }
}
