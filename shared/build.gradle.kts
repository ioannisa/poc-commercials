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
            implementation(libs.ktor.client.okhttp)
        }
        commonMain.dependencies {
            implementation(projects.core.domain)
            api(projects.core.data)
            implementation(projects.core.presentation)
            implementation(projects.feature.aiChat.domain)
            implementation(projects.feature.aiChat.data)
            implementation(projects.feature.aiChat.presentation)
            implementation(projects.feature.auth.domain)
            implementation(projects.feature.auth.data)
            implementation(projects.feature.auth.presentation)
            implementation(projects.feature.timetable.domain)
            implementation(projects.feature.timetable.data)
            implementation(projects.feature.timetable.presentation)
            implementation(projects.feature.scheduleEmail.domain)
            implementation(projects.feature.scheduleEmail.data)
            implementation(projects.feature.scheduleEmail.presentation)
            implementation(projects.feature.preferences.domain)
            implementation(projects.feature.preferences.data)
            implementation(projects.feature.preferences.presentation)
            implementation(projects.feature.databases.domain)
            implementation(projects.feature.databases.data)
            implementation(projects.feature.databases.presentation)
            implementation(projects.feature.userManagement.domain)
            implementation(projects.feature.userManagement.data)
            implementation(projects.feature.userManagement.presentation)
            implementation(projects.feature.migrationConsole.domain)
            implementation(projects.feature.migrationConsole.data)
            implementation(projects.feature.migrationConsole.presentation)

            // Cross-cutting UI modules used internally by :shared's own
            // composables (grid widgets, the report toolbar) - not re-exported,
            // since no entry app references them directly.
            implementation(projects.core.presentation.grids)
            implementation(projects.reportsClient)

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
            implementation(libs.ktor.client.okhttp)

        }

        // Browser-based platforms: Ktor client for server-side report generation
        webMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.js)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.kotlinx.serialization.json)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

dependencies {
    "androidRuntimeClasspath"(libs.compose.uiTooling)
}
