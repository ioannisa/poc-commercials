import org.jetbrains.compose.desktop.application.dsl.TargetFormat

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)
    // Direct dep (shared only `implementation`s it): the PlatformShowcase
    // dev entry renders the design-system lab without the app shell.
    implementation(projects.core.presentation)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    // FileKit.init (native save dialogs live in reports-client jvmMain)
    implementation(libs.filekit.dialogs)
    // Window-geometry persistence (Plain mode - x/y/w/h are not secrets)
    implementation(libs.ksafe)

    // SingleInstanceTest forks real JVMs - a single-instance guard cannot be
    // proven inside one process (see the test's KDoc).
    testImplementation(kotlin("test"))

}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

// Run the desktop app from the repo root so ./config.properties resolves to
// the shared dev config file. Compose Desktop registers the `run` task lazily,
// so configure it via matching to avoid "Task not found" at config time.
tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    workingDir = rootProject.projectDir
    // macOS names the app from the main CLASS when running unbundled ("MainKt").
    // -Xdock:name is set before the JVM starts, so it wins even if something
    // touches AWT early; main() also sets apple.awt.application.name as backup.
    jvmArgs("-Xdock:name=Commercials Manager")
}

// Dev-only entry: the design-system laboratory (six platform profiles x
// input/density/a11y/RTL/font/window simulation on one machine).
// ./gradlew :desktopApp:runShowcase
tasks.register<JavaExec>("runShowcase") {
    group = "application"
    description = "Runs the PlatformShowcase design-system lab"
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("eu.anifantakis.commercials.PlatformShowcaseMainKt")
    workingDir = rootProject.projectDir
}

// Dev-only: render the legacy toolbar mock offscreen to PNGs (no window, no
// login), for reviewing the design. ./gradlew :desktopApp:renderToolbar
tasks.register<JavaExec>("renderToolbar") {
    group = "application"
    description = "Renders the legacy toolbar mock to PNGs (offscreen)"
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("eu.anifantakis.commercials.ToolbarRenderMainKt")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

compose.desktop {
    application {
        mainClass = "eu.anifantakis.commercials.MainKt"

        buildTypes.release.proguard {
            // JasperReports and commons-beanutils resolve report fields and
            // renderers reflectively; ProGuard shrinking (enabled by default
            // for packageRelease* tasks) breaks report generation in packaged
            // release builds, so keep release distributions unminified.
            isEnabled.set(false)
        }

        javaHome = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(21)
            vendor = JvmVendorSpec.MICROSOFT
        }.get().metadata.installationPath.asFile.absolutePath

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Commercials Manager 2"
            packageVersion = "1.0.0"
            includeAllModules = true

            windows {
                menu = true
                shortcut = true
                menuGroup = "Cybernate"
                console = true // for debug to see console errors on windows installations
                dirChooser = true
                upgradeUuid = "f4a8e9b2-7c3d-41a5-8f6e-2b1c0d9a4e3f"
            }

            macOS {
                bundleID = "eu.anifantakis.commercials"
                dockName = "Commercials Manager"
            }

            linux {
                shortcut = true
                appCategory = "Utility"
                debMaintainer = "ioannisanif@gmail.com"
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}
