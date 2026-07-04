import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlinSerialization)
}

composeCompiler {
    stabilityConfigurationFiles.add(
        layout.projectDirectory.file("compose_compiler_config.conf")
    )
}

kotlin {
    android {
        namespace = "eu.anifantakis.commercials.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }

        androidResources { enable = true }

        // commonTest (KoinGraphTest) also runs as android host unit tests
        withHostTest {}
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    jvm()

    js {
        browser()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }


    // default hierarchy template + a webMain group (js + wasmJs)
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
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.ui.text.google.fonts)

            // Ktor client engine for Android
            implementation(libs.ktor.client.cio)
        }
        commonMain.dependencies {
            // kmp-developer multi-module layout: core + features; :shared is
            // the ":app" wiring module and may depend on everything.
            api(projects.core.domain)
            api(projects.core.data)
            api(projects.core.presentation)
            api(projects.feature.auth.domain)
            api(projects.feature.auth.data)
            api(projects.feature.auth.presentation)
            api(projects.feature.timetable.domain)
            api(projects.feature.timetable.data)
            api(projects.feature.timetable.presentation)
            api(projects.feature.scheduleEmail.domain)
            api(projects.feature.scheduleEmail.data)
            api(projects.feature.scheduleEmail.presentation)
            api(projects.feature.preferences.domain)
            api(projects.feature.preferences.data)
            api(projects.feature.preferences.presentation)
            api(projects.feature.databases.domain)
            api(projects.feature.databases.data)
            api(projects.feature.databases.presentation)
            api(projects.feature.userManagement.domain)
            api(projects.feature.userManagement.data)
            api(projects.feature.userManagement.presentation)
            api(projects.feature.migrationConsole.domain)
            api(projects.feature.migrationConsole.data)
            api(projects.feature.migrationConsole.presentation)

            // Extracted feature modules. `api`: their types (BreakSlot,
            // SchedulerCellData, AppTheme, ...) appear in shared's own
            // composable signatures.
            api(projects.core.presentation.grids)
            api(projects.reportsClient)

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            // Extra Icons
            implementation(libs.material.icons.extended)

            // DateTime
            implementation(libs.kotlinx.datetime)

            // Immutable Collections
            implementation(libs.kotlinx.collections.immutable)

            // Navigation 3 for Compose Multiplatform (JetBrains KMP version)
            implementation(libs.jetbrains.navigation3.ui)
            implementation(libs.jetbrains.lifecycle.viewmodel.navigation3)
            implementation(libs.kotlinx.serialization.json)

            // Ktor client + serialization (shared DB API client across all platforms)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.kotlinx.serialization.json)

            // Encrypted key/value persistence (auth token storage)
            implementation(libs.ksafe)

            // Koin DI. koin-core is `api`: initKoin's signature and the app
            // modules' entry points (KoinPlatform.getKoin()) use Koin types.
            api(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.koin.annotations)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)

            // Ktor client engine for JVM (desktop)
            implementation(libs.ktor.client.cio)

        }

        // Browser-based platforms: Ktor client for server-side report generation
        val webMain by getting {
            dependencies {
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.js)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

dependencies {
    "androidRuntimeClasspath"(libs.compose.uiTooling)
}
