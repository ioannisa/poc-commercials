plugins {
    id("commercials.kmp.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.scheduleEmail.domain)
            api(projects.core.presentation)
            // EnhancedDataGrid: the composer's activity / spot / history lists
            // are TABLES, so they use the same grid the timetable consoles do
            // (resizable + sortable columns, sticky header, scrollbars).
            implementation(projects.core.presentation.grids)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            // JavaFX WebView backs the email-preview webview (Swing's HTML
            // pane cannot handle the report's size). OpenJFX ships natives
            // as Maven classifiers, so resolve the build machine's OS/arch;
            // per-OS distributables get theirs by being built on that OS.
            val fxOs = System.getProperty("os.name").lowercase()
            val fxArch = System.getProperty("os.arch").lowercase()
            val fxClassifier = when {
                fxOs.contains("mac") && fxArch == "aarch64" -> "mac-aarch64"
                fxOs.contains("mac") -> "mac"
                fxOs.contains("win") -> "win"
                fxArch == "aarch64" -> "linux-aarch64"
                else -> "linux"
            }
            listOf("base", "graphics", "controls", "media", "web", "swing").forEach { module ->
                implementation("org.openjfx:javafx-$module:${libs.versions.javafx.get()}:$fxClassifier")
            }
        }
    }
}
