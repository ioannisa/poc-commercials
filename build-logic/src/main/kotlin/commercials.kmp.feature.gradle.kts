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
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(catalog.findLibrary("compose-runtime").get())
            implementation(catalog.findLibrary("compose-foundation").get())
            implementation(catalog.findLibrary("compose-material3").get())
            implementation(catalog.findLibrary("compose-ui").get())
            // The @Preview annotation, in COMMON code: every presentation module
            // gets it here rather than repeating the dependency seven times.
            //
            // This artifact publishes `androidx.compose.ui.tooling.preview` into
            // commonMain. Do NOT reach for components-ui-tooling-preview and its
            // `org.jetbrains.compose.ui.tooling.preview.Preview` - same annotation,
            // same rendering, but deprecated in favour of this one, and 46 previews
            // is a lot of places to have to un-deprecate later.
            //
            // `api`, not `implementation` - a preview in a downstream module is
            // annotated with a type this module hands it.
            api(catalog.findLibrary("compose-uiToolingPreview").get())

            // The renderer engine for the IDE's Compose Preview.
            // ClassNotFoundException: androidx.compose.ui.tooling.ComposeViewAdapter
            // is thrown when this is missing.
            implementation(catalog.findLibrary("compose-uiTooling").get())

            implementation(catalog.findLibrary("material-icons-extended").get())
            implementation(catalog.findLibrary("androidx-lifecycle-viewmodelCompose").get())
            implementation(catalog.findLibrary("androidx-lifecycle-runtimeCompose").get())
            implementation(catalog.findLibrary("kotlinx-collections-immutable").get())
            implementation(catalog.findLibrary("kotlinx-datetime").get())
            implementation(catalog.findLibrary("kotlinx-serialization-json").get())
            api(catalog.findLibrary("koin-core").get())
            api(catalog.findLibrary("jetbrains-navigation3-ui").get())
            api(catalog.findLibrary("jetbrains-lifecycle-viewmodel-navigation3").get())
            implementation(catalog.findLibrary("koin-compose").get())
            implementation(catalog.findLibrary("koin-compose-viewmodel").get())
        }
    }
}

// Compose stability config, applied to EVERY feature presentation module
// (core:presentation + each :feature:*:presentation) so kotlinx-datetime
// value types are skippable in composables (e.g. date: LocalDate params).
composeCompiler {
    stabilityConfigurationFiles.add(
        rootProject.layout.projectDirectory.file("compose_compiler_config.conf")
    )
}
