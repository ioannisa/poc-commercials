/*
 * :core:presentation - the presentation toolkit every feature presentation
 * module stands on: MVI helpers (toComposeState, ObserveEffects) and the
 * app-wide global state (GlobalStateContainer + BaseGlobalViewModel).
 * Depends only on :core:domain (kmp-developer RULE 2).
 */
plugins {
    id("commercials.kmp.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.domain)
            // Res.font.* for the design system's bundled Roboto family
            // (and the Noto faces that cover what Roboto cannot - FontFallback.kt).
            // api, not implementation: AppImage/AppDrawableRepo expose
            // DrawableResource on their public surface, so consumer modules
            // (feature presentations) need the type transitively.
            api(libs.compose.components.resources)
            // AppAsyncImage: Coil 3 remote images + SVG; the ktor3 fetcher uses
            // the platform client engines the app already ships (OkHttp/Js/Darwin)
            implementation(libs.coil.compose)
            implementation(libs.coil.svg)
            implementation(libs.coil.network.ktor3)
            // api, not implementation: AppAsyncImage's optional authenticated
            // loading takes an HttpClient on its public signature, so feature
            // presentations need the type to pass their client through.
            api(libs.ktor.client.core)
            // AppText rich-text: LRU cache for parsed HTML (KMP artifact)
            implementation(libs.androidx.collection)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

kotlin {
    sourceSets {
        jvmMain.dependencies {
            // The native FileDialog is shown on the AWT event thread
            implementation(libs.kotlinx.coroutinesSwing)
        }
        // Headless Compose UI tests (runComposeUiTest): the floating-window
        // scoping contract (keyed ViewModel survival across minimize, clearKey
        // on close) is BEHAVIOR - a compile pass cannot vouch for it.
        jvmTest.dependencies {
            implementation(libs.compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
    }
}
