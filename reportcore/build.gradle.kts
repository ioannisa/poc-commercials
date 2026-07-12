import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

/*
 * Report core module - single source of truth for report generation.
 *
 * - commonMain: wire DTOs shared by every report producer/consumer
 *   (browser clients serialize them, the Ktor server deserializes them).
 * - jvmMain:    the JasperReports engine (template + compile cache + fill +
 *               PDF export), used by BOTH the desktop app and the server.
 *
 * Every client target is here: the wire DTOs in commonMain are pure
 * kotlinx-serialization and compile anywhere - mobile posts them to the
 * report server exactly like the browsers do. Only the Jasper engine in
 * jvmMain stays JVM-bound.
 */
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android {
        namespace = "eu.anifantakis.commercials.reportcore"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    js {
        browser()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            // JasperReports 7.x (modular). The core engine is `api` because
            // consumers (desktop print/preview) work with JasperPrint and
            // JasperPrintManager directly; the rest are runtime plumbing.
            api(libs.jasperreports)
            implementation(libs.jasperreports.pdf)
            implementation(libs.jasperreports.fonts)
            implementation(libs.jasperreports.jdt)
        }
    }
}
