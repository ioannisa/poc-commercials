import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

/*
 * Report core module - single source of truth for report generation.
 *
 * - commonMain: wire DTOs shared by every report producer/consumer
 *   (browser clients serialize them, the Ktor server deserializes them).
 * - jvmMain:    the JasperReports engine (template + compile cache + fill +
 *               PDF export), used by BOTH the desktop app and the server.
 *
 * Targets are limited to jvm/js/wasmJs on purpose: Android and iOS do not
 * produce reports (they show "unsupported"), so they never see this module.
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
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
