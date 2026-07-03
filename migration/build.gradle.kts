/*
 * Legacy-migration module - everything the migration PROCESS is:
 *
 * - DumpReplayer:      streams a legacy mysqldump into a scratch schema
 * - LegacyTransformer: scratch -> normalized target (+ synthetic fakes)
 * - MigrationService:  the state machine behind the in-app Migration screen
 * - MigrationCli:      the interactive/scripted command-line front
 * - StationsYaml:      append/remove station entries in stations.yaml
 *
 * JVM-only by nature (JDBC + server filesystem). Stands directly on
 * :persistence for the station schema DDL (single-sourced in StationDb) and
 * the live StationRegistry - but stays free of any HTTP/Ktor concern; the
 * server contributes only thin route adapters and the CLI `main`.
 */
plugins {
    alias(libs.plugins.kotlinJvm)
}

group = "eu.anifantakis.commercials"
version = "1.0.0"

dependencies {
    api(project(":persistence"))

    testImplementation(libs.kotlin.test)
}
