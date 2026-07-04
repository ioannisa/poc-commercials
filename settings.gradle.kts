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
include(":core:presentation")
include(":core:presentation:grids")
include(":reports-client")
include(":feature:auth:domain")
include(":feature:auth:data")
include(":feature:auth:presentation")
include(":feature:timetable:domain")
include(":feature:timetable:data")
include(":feature:timetable:presentation")
include(":feature:schedule-email:domain")
include(":feature:schedule-email:data")
include(":feature:schedule-email:presentation")
include(":feature:preferences:domain")
include(":feature:preferences:data")
include(":feature:preferences:presentation")
include(":feature:databases:domain")
include(":feature:databases:data")
include(":feature:databases:presentation")
include(":feature:user-management:domain")
include(":feature:user-management:data")
include(":feature:user-management:presentation")
include(":feature:migration-console:domain")
include(":feature:migration-console:data")
include(":feature:migration-console:presentation")

include(":shared")
include(":androidApp")
include(":desktopApp")
include(":webApp")
include(":server")
include(":reportcore")
include(":migration")
include(":persistence")
include(":mailer")
