plugins {
    id("commercials.kmp.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.userManagement.domain)
            api(projects.core.presentation)
        }
    }
}
