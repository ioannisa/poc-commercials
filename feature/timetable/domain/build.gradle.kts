plugins {
    id("commercials.kmp.domain")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.domain)
            api(libs.kotlinx.datetime)
        }
    }
}
