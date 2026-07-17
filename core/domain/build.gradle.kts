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
            // StateFlow appears in the UserSession contract - pure kotlinx, no
            // platform/UI framework, so RULE 1 still holds.
            api(libs.kotlinx.coroutines.core)
            // Compose-runtime ANNOTATIONS ONLY (@Immutable/@Stable on value
            // types the chrome renders, e.g. StationAccess). No @Composable, no
            // compose-compiler plugin here - the annotation makes the IDE AND
            // the compiler agree these are stable, which a stability-config
            // entry (invisible to the IDE) could not.
            implementation(libs.compose.runtime)
        }
    }
}
