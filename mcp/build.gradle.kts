/*
 * MCP tool core - the transport-agnostic Model Context Protocol server for the
 * Commercials Manager backend. Exposes the station data (queries), report
 * generation and (guarded) mutations as MCP tools, reusing the existing
 * persistence + reportcore + mailer modules. Its hosts bind it to a transport:
 * the Ktor `server` (HTTP/SSE, bearer-auth) and `:mcp-stdio` (Claude Desktop).
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
version = "1.0.0"

dependencies {
    // The official MCP Kotlin SDK (server) - its Server/Tool types are part of
    // this module's public API; it also brings ktor-server-sse transitively.
    api(libs.mcp.kotlin.sdk.server)

    // Database layer + auth: StationRegistry/StationDb/AuthDb/AuthUser appear in
    // the tool-services + caller-identity public API.
    api(projects.persistence)

    // Report engine + wire DTOs (headless PDF generation), and the shared
    // Program Flow contract (names/formatters) also used by reports-client.
    implementation(projects.reportcore)
    implementation(projects.reportsModel)
    implementation(libs.kotlinx.datetime)

    // Customer schedule emails: shared assembler + the mailer render/send (send_schedule_email tool).
    implementation(projects.scheduleEmail)
    implementation(projects.mailer)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}
