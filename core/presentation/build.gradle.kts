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
            implementation(libs.compose.components.resources)
        }
    }
}

kotlin {
    sourceSets {
        jvmMain.dependencies {
            // The native FileDialog is shown on the AWT event thread
            implementation(libs.kotlinx.coroutinesSwing)
        }
    }
}
