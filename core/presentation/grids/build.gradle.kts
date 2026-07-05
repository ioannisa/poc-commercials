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
}

kotlin {
    android {
        namespace = "eu.anifantakis.commercials.core.presentation.grids"
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

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.material.icons.extended)

            // Grid models carry dates and expose immutable collections in
            // their API - both are part of this module's public surface.
            api(libs.kotlinx.datetime)
            api(libs.kotlinx.collections.immutable)
        }
        jvmMain.dependencies {
            // Desktop scrollbar APIs (VerticalScrollbar, rememberScrollbarAdapter)
            implementation(compose.desktop.currentOs)
        }
        // iosMain comes from the default hierarchy template
    }
}
