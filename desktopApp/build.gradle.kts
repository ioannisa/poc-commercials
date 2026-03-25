import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
}

compose.desktop {
    application {
        mainClass = "eu.anifantakis.poc.ctv.MainKt"

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
                console = false
                dirChooser = true
                upgradeUuid = "f4a8e9b2-7c3d-41a5-8f6e-2b1c0d9a4e3f"
            }

            macOS {
                bundleID = "eu.anifantakis.poc.ctv"
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
