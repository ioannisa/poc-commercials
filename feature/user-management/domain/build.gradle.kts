plugins {
    id("commercials.kmp.domain")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.domain)
            // Compose-runtime annotations only (@Immutable on ManagedUser /
            // AdminApiToken); no @Composable / compose-compiler here.
            implementation(libs.compose.runtime)
        }
    }
}
