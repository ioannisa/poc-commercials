/*
 * Legacy-migration module - everything the migration PROCESS is:
 *
 * - DumpReplayer:      streams a legacy mysqldump into a scratch schema
 * - LegacyTransformer: scratch -> normalized target (+ synthetic fakes)
 * - MigrationService:  the state machine behind the in-app Migration screen
 * - MigrationCli:      the interactive/scripted command-line front
 * - StationsYaml:      append/remove station entries in stations.yaml
 *
 * - MigrationRoutes:   the module's own Ktor endpoints (/api/admin/migration)
 *
 * JVM-only by nature (JDBC + server filesystem). Stands directly on
 * :persistence for the station schema DDL (single-sourced in StationDb) and
 * the live StationRegistry. The routes ship here too, but SECURITY does not:
 * the server passes its requireAdmin guard in - who the super admin is stays
 * the host's concern.
 */
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

group = "eu.anifantakis.commercials"
version = "1.0.0"

dependencies {
    api(project(":persistence"))

    // The module contributes its own routes; the host installs them
    implementation(libs.ktor.server.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
}
