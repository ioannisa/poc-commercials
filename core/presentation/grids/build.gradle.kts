import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Scheduler grid engine - the app's largest, fully self-contained UI feature:
 * the lazy scheduler grid, the enhanced data grid, grid keyboard navigation,
 * the shared scheduler models (BreakSlot, CommercialItem, SchedulerCellData,
 * SchedulerKey, ...) and per-platform scrollbars (expect/actual).
 *
 * Depends on Compose + kotlinx only - deliberately NOTHING from the app
 * (no auth, no networking, no navigation), which is what made it the first
 * client-side module to extract.
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

composeCompiler {
    stabilityConfigurationFiles.add(
        rootProject.layout.projectDirectory.file("compose_compiler_config.conf")
    )
    if (providers.gradleProperty("composeReports").isPresent) {
        val dir = layout.buildDirectory.dir("compose_reports")
        reportsDestination.set(dir)
        metricsDestination.set(dir)
    }
}

kotlin {
    android {
        namespace = "eu.anifantakis.commercials.grids"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    jvm()

    js {
        browser()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    // Same webMain group as the convention plugin: js + wasmJs share one
    // source set, so per-target duplicate actuals collapse.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("web") {
                withJs()
                withWasmJs()
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.core.presentation)   // AppVerticalScrollbar & the design system
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.material.icons.extended)

            // Grid models carry dates and expose immutable collections in
            // their API - both are part of this module's public surface.
            api(libs.kotlinx.datetime)
            api(libs.kotlinx.collections.immutable)

            // The @Preview ANNOTATION - multiplatform, so it may live in commonMain.
            api(libs.compose.uiToolingPreview)
        }
        jvmMain.dependencies {
            // Desktop scrollbar APIs (VerticalScrollbar, rememberScrollbarAdapter)
            implementation(compose.desktop.currentOs)

            // The preview RENDERER (ComposeViewAdapter). Never commonMain: ui-tooling
            // publishes android + desktop variants ONLY, so declaring it there fails
            // resolution for wasmJs/js/iOS and takes the web app down with it.
            implementation(libs.compose.uiTooling)
        }
        jvmTest.dependencies {
            // TEMPORARY: perf harness for the grid (remove with the harness).
            implementation(libs.kotlin.test)
            implementation(compose.desktop.currentOs)
        }
        // iosMain comes from the default hierarchy template
    }
}
