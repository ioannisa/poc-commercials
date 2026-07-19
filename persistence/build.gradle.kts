/*
 * Persistence core - the server's database layer, shared by every server
 * feature (scheduling, auth, admin, migration's host adapter):
 *
 * - stations:  server.yaml model + loader (HostingConfig, StationConfig,
 *              superAdmin block), pool-size resolution, StationRegistry
 *              (lazy per-station HikariCP pools, live add/remove)
 * - scheduler: StationDb (normalized station schema DDL + derived read
 *              model + demo seeding) and CentralDb (users/tokens/grants pool)
 * - auth:      AuthDb (PBKDF2 passwords, SHA-256 hashed bearer tokens &
 *              recovery codes, per-station grants, user management)
 *
 * JVM-only. Ships no logging backend and only a RUNTIME MySQL driver
 * (JDBC-by-string) - the hosting application provides both.
 *
 * Package names keep their historical `server.*` form on purpose: renaming
 * would churn every consumer for zero behavioural gain.
 */
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

group = "eu.anifantakis.commercials"
// Backend jar coordinate (not published) - sourced from the catalog so no
// bare version literal survives anywhere. See libs.versions.toml `server`.
version = libs.versions.server.get()

dependencies {
    // server.yaml (kaml keeps us multiplatform-friendly and comment-tolerant)
    implementation(libs.kaml)
    implementation(libs.kotlinx.serialization.json)

    // Connection pooling; the MySQL driver is referenced by class NAME only
    implementation(libs.hikaricp)
    runtimeOnly(libs.mysql.connector.j)

    // @Provided marker for the compile-time Koin checker (definitions live
    // in the hosting application's DI module)
    implementation(libs.koin.annotations)

    implementation(libs.slf4j.api)

    testImplementation(libs.kotlin.test)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}
