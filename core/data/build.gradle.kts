/*
 * :core:data - shared data services: the common HTTP client factory
 * (bearer + station scoping), AppConfig, the auth session/token store.
 * Depends only on :core:domain (kmp-developer RULE 2).
 */
plugins {
    id("commercials.kmp.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.domain)

            // AuthSession's revision is Compose-observable state
            implementation(libs.compose.runtime)
            // Encrypted session/preferences store; KSafe appears in
            // constructor signatures (createKSafe factory)
            api(libs.ksafe)
            implementation(libs.koin.annotations)

            // authenticatedJsonClient returns HttpClient - api surface
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.kotlinx.serialization.json)
            api(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        // js + wasmJs share sources (AppConfig fetches /config over HTTP)
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
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
        iosArm64Main.get().dependsOn(iosMain)
        iosSimulatorArm64Main.get().dependsOn(iosMain)
    }
}
