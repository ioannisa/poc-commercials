import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Report contract - the single home of the Program Flow (ΡΟΗ ΠΡΟΓΡΑΜΜΑΤΟΣ)
 * report's JRXML parameter/field names, formatters (MM:SS duration, Greek date),
 * the ΡΟΗ notes rule, and the JSON param/row builders. Shared so the client
 * (`reports-client`, which builds a ReportPayload from grid data) and the
 * backend (`:mcp`, which builds a ReportRequest from station-DB rows) cannot
 * drift apart on the template contract.
 *
 * Pure multiplatform Kotlin (kotlinx-serialization + kotlinx-datetime) - NO
 * reportcore, NO Compose - so it compiles on every target `reports-client` has
 * (android/ios/jvm/js/wasm) while staying consumable by the JVM `:mcp`.
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android {
        namespace = "eu.anifantakis.commercials.reports.model"
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
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
