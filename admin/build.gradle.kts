import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Super-admin vertical slice - the screens and API clients behind the
 * account-menu admin entries:
 *
 * - UserManagementScreen + AdminApi: users, grants, password resets
 * - DatabasesScreen: hosted stations, safe/hard delete
 * - MigrationScreen + MigrationApi: the legacy-dump migration front
 *
 * Pure common code (no platform actuals): stands on :client-core for
 * session/auth/http and :appearance for the native file picker seam.
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
        namespace = "eu.anifantakis.commercials.admin"
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
            implementation(libs.material.icons.extended)

            api(project(":client-core"))
            implementation(project(":appearance"))

            implementation(libs.kotlinx.serialization.json)
            implementation(libs.koin.compose)
        }
    }
}
