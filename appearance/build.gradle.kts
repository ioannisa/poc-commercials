import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Appearance module - how the app looks and its platform-integration UI:
 *
 * - ui/theme: the Material 3 AppTheme (brand palette, shapes, OS-following
 *   light/dark - currently locked to light at the call site until the grids
 *   are dark-ready)
 * - ui/files: the file-picker seam - a REAL native OS dialog on desktop
 *   (java.awt.FileDialog -> NSOpenPanel/Windows chooser), "unavailable" on
 *   web/mobile where callers fall back to server-side browsing.
 *
 * Pure Compose + coroutines; nothing app-specific.
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    android {
        namespace = "eu.anifantakis.commercials.appearance"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
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
        }
        jvmMain.dependencies {
            // The native FileDialog is shown on the AWT event thread
            implementation(libs.kotlinx.coroutinesSwing)
        }
        // js + wasmJs share one "no native picker" actual
        val webMain by creating {
            dependsOn(commonMain.get())
        }
        jsMain.get().dependsOn(webMain)
        wasmJsMain.get().dependsOn(webMain)

        val iosMain by creating {
            dependsOn(commonMain.get())
        }
        iosArm64Main.get().dependsOn(iosMain)
        iosSimulatorArm64Main.get().dependsOn(iosMain)
    }
}
