import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Client-side reporting - everything between the grid and a printed/previewed
 * PDF:
 *
 * - commonMain:  report payload model, the payload factory (grid data ->
 *                report rows), ReportService + ReportToolbar UI, the
 *                :reportcore wire-DTO adapter, ReportApiClient (server
 *                rendering) and the ServerReportService + PdfSink seam
 * - jvmMain:     in-process JasperReports engine (preview, print, save)
 * - webMain:     BrowserPdfSink (download / new tab / window.print)
 * - android/ios: FileKitPdfSink (SAF / Files.app save, system open, share)
 *
 * Mirrors the source-set graph :shared used before the extraction.
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android {
        namespace = "eu.anifantakis.commercials.reportsclient"
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


    // default hierarchy template + the shared web group (js + wasmJs)
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
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
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.material.icons.extended)

            // Shared Program Flow report contract (JRXML names, formatters, notes)
            // - the single home shared with the backend's :mcp assembler.
            implementation(projects.reportsModel)

            // Grid models are the factory's input; session/config drive the
            // web client - both appear in public signatures.
            api(projects.core.presentation.grids)
            // implementation, NOT api: reports-client uses core:data internally
            // (ReportApiClient's AuthSession, AppConfig) but must NOT re-export
            // it - otherwise presentation modules that depend on reports-client
            // inherit core:data on their classpath and can pierce the
            // presentation⊥data boundary transitively (SOLID/DIP hygiene).
            implementation(projects.core.data)

            implementation(libs.kotlinx.serialization.json)
            // Wire DTOs now compile on every client target (mobile posts them
            // to the report server exactly like the browsers).
            api(projects.reportcore)
            // No Koin: this module's only composable (ReportToolbar) is now
            // stateless, so nothing here resolves anything from a container.
        }
        androidMain.dependencies {
            // Native save (SAF) / open (ACTION_VIEW) / share sheet for PDFs
            implementation(libs.filekit.dialogs)
        }
        iosMain.dependencies {
            // UIDocumentPicker save / QuickLook open / share sheet for PDFs
            implementation(libs.filekit.dialogs)
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            // Native save dialog (IFileDialog/NSSavePanel/XDG portal) - kills
            // the Swing JFileChooser, the one visibly non-native dialog.
            implementation(libs.filekit.dialogs)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }

        webMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.js)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }
        // iosMain comes from the default hierarchy template
    }
}
