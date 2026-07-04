plugins {
    id("commercials.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.preferences.domain)

            // Compose-observable prefs holder backed by encrypted KSafe
            implementation(libs.compose.runtime)
            implementation(libs.ksafe)
            implementation(libs.koin.annotations)
            api(libs.koin.core)
        }
    }
}
