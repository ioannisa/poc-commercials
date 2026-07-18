plugins {
    id("commercials.kmp.domain")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.domain)
            // Compose-runtime annotations only (@Immutable on the chat models).
            implementation(libs.compose.runtime)
        }
    }
}
