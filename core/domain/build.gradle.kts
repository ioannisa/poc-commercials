/*
 * :core:domain - the app-wide domain vocabulary: DataResult, Error,
 * DataError, AppRole, the session contract (UserSession/StationAccess). Pure
 * Kotlin (commercials.kmp.domain enforces RULE 1: no Android plugin, no
 * platform deps). kotlinx-serialization is pure-Kotlin multiplatform (not a
 * platform dependency), so @Serializable domain models like StationAccess -
 * which the data layer persists - stay compliant. Every other module may
 * depend on this; this depends on nothing.
 */
plugins {
    id("commercials.kmp.domain")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
