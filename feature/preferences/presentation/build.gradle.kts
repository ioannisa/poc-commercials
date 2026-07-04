plugins {
    id("commercials.kmp.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.preferences.domain)
            api(projects.core.presentation)
        }
    }
}
