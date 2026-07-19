/*
 * Legacy-migration module - everything the migration PROCESS is:
 *
 * - DumpReplayer:      streams a legacy mysqldump into a scratch schema
 * - LegacyTransformer: scratch -> normalized target (+ synthetic fakes)
 * - MigrationService:  the state machine behind the in-app Migration screen
 * - MigrationCli:      the interactive/scripted command-line front
 * - StationsYaml:      append/remove station entries in server.yaml
 *
 * - MigrationRoutes:   the module's own Ktor endpoints (/api/admin/migration)
 *
 * JVM-only by nature (JDBC + server filesystem). Stands directly on
 * :persistence for the station schema DDL (single-sourced in StationDb) and
 * the live StationRegistry. The routes ship here too, but SECURITY does not:
 * the server passes its requireAdmin guard in - who the super admin is stays
 * the host's concern.
 */
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    // OpenAPI metadata generation for THIS module's routes (/api/admin/migration):
    // the compiler plugin only runs where it is applied, so the host server can't
    // infer these routes' docs on its behalf.
    alias(libs.plugins.ktor)
}

group = "eu.anifantakis.commercials"
// Backend jar coordinate (not published) - sourced from the catalog so no
// bare version literal survives anywhere. See libs.versions.toml `server`.
version = libs.versions.server.get()

dependencies {
    api(projects.persistence)

    // The module contributes its own routes; the host installs them
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.routing.openapi)   // runtime support for the generated OpenAPI metadata
    implementation(libs.kotlinx.serialization.json)

    // server.yaml surgery re-parses its own output before writing it (StationsYaml)
    implementation(libs.kaml)

    testImplementation(libs.kotlin.test)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

ktor {
    openApi {
        enabled = true
        codeInferenceEnabled = true
    }
}
