plugins {
    id("commercials.kmp.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.migrationConsole.domain)
            api(projects.core.presentation)
            implementation(projects.appearance)
        }
    }
}
