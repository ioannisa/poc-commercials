plugins {
    id("commercials.kmp.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.auth.domain)
            api(projects.core.presentation)
        }
    }
}
