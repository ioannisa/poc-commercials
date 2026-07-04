rootProject.name = "commercials-manager"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // JasperReports repository for additional dependencies
        maven {
            url = uri("https://jaspersoft.jfrog.io/jaspersoft/third-party-ce-artifacts/")
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// kmp-developer multi-module layout: :core:* + :feature:<name>:<layer>.
// :shared plays the ":app" wiring role (name kept - the iOS framework is
// baseName "Shared"); :grids / :reports-client / :appearance serve as
// standalone core modules.
include(":core:domain")
include(":core:data")

include(":shared")
include(":androidApp")
include(":desktopApp")
include(":webApp")
include(":server")
include(":reportcore")
include(":migration")
include(":grids")
include(":client-core")
include(":reports-client")
include(":admin")
include(":persistence")
include(":mailer")
include(":appearance")
