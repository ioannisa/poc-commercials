/*
 * :core:domain - the app-wide domain vocabulary: DataResult, Error,
 * DataError. Pure Kotlin (commercials.kmp.domain enforces RULE 1: no
 * Android plugin, no platform deps). Every other module may depend on this;
 * this depends on nothing.
 */
plugins {
    id("commercials.kmp.domain")
}
