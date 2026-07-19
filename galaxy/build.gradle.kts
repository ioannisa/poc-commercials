/*
 * Galaxy (SingularLogic) ERP import module - the ONGOING sync from the
 * client's new ERP into the group databases. Deliberately a SEPARATE
 * subsystem from :migration (which is the one-off legacy dumps + SEN
 * migration): different source format, different lifecycle.
 *
 * - GalaxyExports:  quote-aware parsers for the Galaxy flat export + dictionaries
 * - GalaxyImporter: idempotent reconcile/upsert engine (dry-run by default)
 * - GalaxyImportCli: the command-line front (:server:galaxyImportCli)
 * - GalaxyImportService: the state machine behind the Galaxy Bridge screen
 * - GalaxyRoutes:   the module's own Ktor endpoints (/api/admin/galaxy)
 *
 * JVM-only by nature (JDBC + server filesystem). Stands on :persistence for
 * the group schema DDL (GroupDb) and, transitively, the MySQL driver.
 * Analysis + format contract: GALAXY-MATCHER.md at the repo root. Like
 * :migration, the routes ship here but SECURITY does not: the server passes
 * its requireAdmin guard in.
 */
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    // OpenAPI metadata generation for THIS module's routes (/api/admin/galaxy):
    // the compiler plugin only runs where it is applied.
    alias(libs.plugins.ktor)
}

group = "eu.anifantakis.commercials"
version = libs.versions.server.get()

dependencies {
    api(projects.persistence)

    // The module contributes its own routes; the host installs them
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.routing.openapi)
    implementation(libs.kotlinx.serialization.json)

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
