plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

/*
 * :kitepdf-difftest is INTERNAL test infrastructure, never published: the
 * hardened MuPDF oracle and image differ shared by the native-renderer and
 * skia-renderer differential harnesses (one copy, not three).
 */
kotlin {
    jvmToolchain(21)
    jvm()
}
