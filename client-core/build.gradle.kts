import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Client core - the foundation every client feature stands on:
 *
 * - config: AppConfig (server base URL & friends), expect/actual per platform
 * - auth:   AuthSession (encrypted persisted session + Compose-observable
 *           revision), AuthApi (login/logout/password/recovery), AppRole,
 *           authenticatedJsonClient (bearer + 401-to-login mapping),
 *           KSafe factory actuals
 *
 * Feature API clients (AdminApi, MigrationApi, ...) do NOT belong here -
 * they live with their features. HTTP engines are also not bundled (except
 * web, where AppConfig itself must fetch its config): the apps provide them.
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
        namespace = "eu.anifantakis.commercials.clientcore"
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
            // Session/config/ksafe moved to :core:data (kmp-developer
            // multi-module layout); what remains here (AuthApi,
            // UserPreferences) re-exports it until features absorb them.
            api(projects.core.data)

            // AuthSession's revision is Compose-observable state
            implementation(libs.compose.runtime)

            // Session/auth API surface: HttpClient and Result types appear in
            // public signatures (authenticatedJsonClient returns HttpClient)
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.kotlinx.serialization.json)

            // Encrypted key/value persistence (session storage)
            implementation(libs.ksafe)

            // @Provided marker for the compile-time Koin checker (the
            // definitions themselves live in the app's DI module)
            implementation(libs.koin.annotations)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        // js + wasmJs: AppConfig fetches /config over HTTP at startup
        val webMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.ktor.client.js)
            }
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
