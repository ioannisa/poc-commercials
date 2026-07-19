/*
 * MCP tool core - the transport-agnostic Model Context Protocol server for the
 * Commercials Manager backend. Exposes the station data (queries), report
 * generation and (guarded) mutations as MCP tools, reusing the existing
 * persistence + reportcore + mailer modules. The Ktor `server` binds it to its
 * transport: HTTP/SSE at `/mcp`, bearer-auth (a personal access token).
 *
 * JVM-only (persistence is JVM-only). The MCP Kotlin SDK types (Server, Tool)
 * and the persistence types (StationRegistry, AuthUser) appear in this module's
 * public API, so both are exposed with `api`.
 */
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

group = "eu.anifantakis.commercials"
// The MCP server is part of the SERVER deployment - it advertises the server's
// version in its protocol handshake (CommercialsMcpServer.SERVER_VERSION reads
// the generated resource below). Same source as /version: libs `server`.
val serverVersion = libs.versions.server.get()
version = serverVersion

val generateMcpVersionResource by tasks.registering {
    val v = serverVersion
    val outDir = layout.buildDirectory.dir("generated/versionRes")
    inputs.property("serverVersion", v)
    outputs.dir(outDir)
    doLast {
        outDir.get().file("commercials-mcp-version.properties").asFile.apply {
            parentFile.mkdirs()
            writeText("version=$v\n")
        }
    }
}
// srcDir(TaskProvider) carries the task dependency - no explicit dependsOn.
sourceSets["main"].resources.srcDir(generateMcpVersionResource)

dependencies {
    // The official MCP Kotlin SDK (server) - its Server/Tool types are part of
    // this module's public API; it also brings ktor-server-sse transitively.
    api(libs.mcp.kotlin.sdk.server)

    // Database layer + auth: StationRegistry/StationDb/AuthDb/AuthUser appear in
    // the tool-services + caller-identity public API.
    api(projects.persistence)

    // `api`: ReportRequest appears in the ReportRenderer port's signature.
    api(projects.reportcore)
    implementation(projects.reportsModel)
    implementation(libs.kotlinx.datetime)

    // `api`: StationDataSource EXTENDS ScheduleEmailSource, so the supertype is
    // part of this module's public API.
    api(projects.scheduleEmail)
    implementation(projects.mailer)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    // This module owns its own Koin module (di/McpModule.kt): the Ktor server
    // loads it when it mounts /mcp, keeping the tool bindings in one place.
    implementation(libs.koin.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}
