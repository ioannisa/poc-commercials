/*
 * Feature presentation module: base KMP library + Compose Multiplatform +
 * Koin (with the compiler plugin's compile-time graph validation) +
 * kotlinx-serialization (NavKey routes, DTOs). The dependency bundle
 * mirrors what every screen needs; feature-specific deps stay in the
 * feature's own build script.
 */
plugins {
    id("commercials.kmp.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.insert-koin.compiler.plugin")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(catalog.findLibrary("compose-runtime").get())
            implementation(catalog.findLibrary("compose-foundation").get())
            implementation(catalog.findLibrary("compose-material3").get())
            implementation(catalog.findLibrary("compose-ui").get())
            implementation(catalog.findLibrary("material-icons-extended").get())
            implementation(catalog.findLibrary("androidx-lifecycle-viewmodelCompose").get())
            implementation(catalog.findLibrary("androidx-lifecycle-runtimeCompose").get())
            implementation(catalog.findLibrary("kotlinx-collections-immutable").get())
            implementation(catalog.findLibrary("kotlinx-datetime").get())
            implementation(catalog.findLibrary("kotlinx-serialization-json").get())
            api(catalog.findLibrary("koin-core").get())
            implementation(catalog.findLibrary("koin-compose").get())
        }
    }
}
