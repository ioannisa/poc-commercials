/*
 * Galaxy (SingularLogic) ERP import module - the ONGOING sync from the
 * client's new ERP into the group databases. Deliberately a SEPARATE
 * subsystem from :migration (which is the one-off legacy dumps + SEN
 * migration): different source format, different lifecycle, and it will
 * eventually get its own admin screen.
 *
 * - GalaxyExports:  quote-aware parsers for the Galaxy flat export + dictionaries
 * - GalaxyImporter: idempotent reconcile/upsert engine (dry-run by default)
 * - GalaxyImportCli: the command-line front (:server:galaxyImportCli)
 *
 * JVM-only by nature (JDBC + server filesystem). Stands on :persistence for
 * the group schema DDL (GroupDb) and, transitively, the MySQL driver.
 * Analysis + format contract: GALAXY-MATCHER.md at the repo root.
 */
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
}

group = "eu.anifantakis.commercials"
version = libs.versions.server.get()

dependencies {
    api(projects.persistence)

    testImplementation(libs.kotlin.test)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}
