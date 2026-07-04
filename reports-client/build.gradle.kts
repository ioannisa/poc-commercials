import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Client-side reporting - everything between the grid and a printed/previewed
 * PDF:
 *
 * - commonMain:  report payload model, the payload factory (grid data ->
 *                report rows), the ReportService expect + the ReportToolbar UI
 * - reportsMain: (jvm + js + wasmJs) payload -> :reportcore wire-DTO adapter,
 *                so desktop and browser paths cannot drift apart
 * - jvmMain:     in-process JasperReports engine (preview, print, save)
 * - webMain:     ReportApiClient - browsers ask the server to render
 * - android/ios: "unsupported" stubs (they never produce reports)
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


    // default hierarchy template + the module's custom sharing: reportsMain
    // holds the real report engine (jvm + web); android/ios keep stubs.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("reports") {
                withJvm()
                group("web") {
                    withJs()
                    withWasmJs()
                }
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

            // Grid models are the factory's input; session/config drive the
            // web client - both appear in public signatures.
            api(projects.core.presentation.grids)
            api(projects.core.data)

            implementation(libs.kotlinx.serialization.json)
            implementation(libs.koin.compose)
        }
        val reportsMain by getting {
            dependencies {
                api(project(":reportcore"))
            }
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }

        val webMain by getting {
            dependencies {
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.js)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
            }
        }
        // iosMain comes from the default hierarchy template
    }
}
