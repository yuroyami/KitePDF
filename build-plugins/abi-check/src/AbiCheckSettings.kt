/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.buildplugins.abi

import org.jetbrains.amper.plugins.Configurable

@Configurable
interface AbiCheckSettings {
    /** Directory of the committed JVM ABI dump, relative to the module directory. The file is `<module>.api`. */
    val dumpDir: String get() = "api/jvm"

    /**
     * Platforms of the merged klib ABI dump, such as `iosArm64` and `js`. Empty means no klib check.
     * The klib check reads the klibs that `./kotlin build` wrote, so it needs a build of each platform first.
     */
    val klibPlatforms: List<String> get() = emptyList()

    /**
     * Also checks the Android classes against `<androidDumpDir>/<module>.api`. Use it when the Android code
     * has API that the JVM code does not have. The check reads the classes that `./kotlin build -p android` wrote.
     */
    val android: Boolean get() = false

    /** Directory of the committed Android ABI dump, relative to the module directory. */
    val androidDumpDir: String get() = "api/android"

    /** Directory of the committed klib ABI dump, relative to the module directory. The file is `<module>.klib.api`. */
    val klibDumpDir: String get() = "api"
}
