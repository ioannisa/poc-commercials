plugins {
    id("commercials.kmp.domain")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.domain)
            implementation(libs.kotlinx.serialization.json)
            // Compose-runtime annotations only (@Immutable on the chat models).
            implementation(libs.compose.runtime)
        }
    }
}
