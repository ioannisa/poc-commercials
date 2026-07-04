plugins {
    id("commercials.kmp.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.databases.domain)
            api(projects.core.presentation)
        }
    }
}
