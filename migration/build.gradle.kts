/*
 * Legacy-migration module - everything the migration PROCESS is:
 *
 * - DumpReplayer:      streams a legacy mysqldump into a scratch schema
 * - LegacyTransformer: scratch -> normalized target (+ synthetic fakes)
 * - MigrationService:  the state machine behind the in-app Migration screen
 * - MigrationCli:      the interactive/scripted command-line front
 * - StationsYaml:      append/remove station entries in stations.yaml
 *
 * JVM-only by nature (JDBC + server filesystem). Deliberately knows NOTHING
 * about the server: everything it needs from its host (create a station
 * schema, host it live) goes through the MigrationHost port, which :server
 * implements over its own StationDb/StationRegistry - keeping the DDL single
 * -sourced there. No MySQL-driver compile dependency either: plain java.sql
 * against whatever driver the host application ships.
 */
plugins {
    alias(libs.plugins.kotlinJvm)
}

group = "eu.anifantakis.commercials"
version = "1.0.0"

dependencies {
    testImplementation(libs.kotlin.test)
}
