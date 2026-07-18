plugins {
    id("commercials.kmp.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.aiChat.domain)
            api(projects.core.presentation)
            // Chat answers arrive as markdown (models emit it no matter what
            // the prompt says) - render it instead of fighting it.
            implementation(libs.markdown.renderer.m3)
            // PdfSink: opening the out-of-band chat reports per platform.
            implementation(projects.reportsClient)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
