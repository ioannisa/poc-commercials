/*
 * MCP stdio entrypoint - a standalone JVM app that serves the Commercials
 * Manager MCP tools over stdio (stdin/stdout), for MCP clients that spawn a
 * helper process (Claude Desktop, CLI tooling). Boots the same persistence/auth
 * stack as the server and resolves its caller identity from COMMERCIALS_MCP_TOKEN.
 *
 * stdout is the MCP protocol channel - all logging MUST go to stderr.
 */
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

group = "eu.anifantakis.commercials"
version = "1.0.0"

application {
    mainClass.set("eu.anifantakis.commercials.mcp.stdio.MainKt")
    // Run from the repo root so ./config.properties and ./server.yaml resolve
    // to the shared dev config, matching the `server` module.
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

dependencies {
    // Tool core (brings persistence + the MCP SDK via `api`).
    implementation(projects.mcp)

    // Logging backend - directed to stderr so it never corrupts the stdout
    // MCP channel (see logback config).
    implementation(libs.logback.classic)

    testImplementation(libs.kotlin.test)
}

// Launch the stdio server from the repo root so ./config.properties / ./server.yaml
// resolve like they do for `:server:run`.
tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    workingDir = rootProject.projectDir
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}
