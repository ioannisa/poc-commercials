import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
    // Compile-time DI safety, same as :shared
    alias(libs.plugins.koinCompiler)
    application
}

group = "eu.anifantakis.commercials"
// The server's own build stamp (libs.versions.toml `server`), also baked into
// a generated resource so the running process can report itself on the open
// /version route (routes/VersionRoutes.kt). Independent of the DESKTOP app's
// version: client releases don't force server redeploys or vice versa.
val serverVersion = libs.versions.server.get()
version = serverVersion

val generateVersionResource by tasks.registering {
    val v = serverVersion
    val outDir = layout.buildDirectory.dir("generated/versionRes")
    inputs.property("serverVersion", v)
    outputs.dir(outDir)
    doLast {
        outDir.get().file("commercials-server-version.properties").asFile.apply {
            parentFile.mkdirs()
            writeText("version=$v\n")
        }
    }
}
// srcDir(TaskProvider) carries the task dependency - no explicit dependsOn.
sourceSets["main"].resources.srcDir(generateVersionResource)

application {
    mainClass.set("eu.anifantakis.commercials.server.ApplicationKt")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

dependencies {
    // Ktor Server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.sse)   // SSE transport for the MCP endpoint
    // Internet hardening: per-IP throttling on auth/OAuth endpoints + real
    // client IPs when TLS terminates at a reverse proxy (gated in server.yaml)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.server.double.receive)

    // In-app AI assistant: Anthropic via official SDK; OpenAI/Gemini via ktor-client HTTP
    implementation(libs.anthropic.java)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    // OpenAPI/Swagger UI: compiler-generated spec (dev-only, gated in Routing.kt).
    // routing-openapi provides the generation + .describe {}; swagger serves the UI.
    implementation(libs.ktor.server.swagger)
    implementation(libs.ktor.server.routing.openapi)
    implementation(libs.ktor.serialization.json)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // Koin DI (annotations bring @Provided for the compile-time checker)
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)
    implementation(libs.koin.annotations)

    // Report engine + wire DTOs (brings JasperReports transitively)
    implementation(projects.reportcore)

    // Legacy-dump migration engine/service/CLI (server implements its MigrationHost port)
    implementation(projects.migration)

    // Galaxy (new ERP) import engine/CLI - ongoing sync, separate from :migration
    implementation(projects.galaxy)

    // Database layer (server.yaml, HikariCP pools, station/central schemas,
    // auth persistence) - brings the MySQL driver at runtime
    implementation(projects.persistence)

    // Customer schedule emails (shared assembler + HTML renderer + SMTP sender)
    implementation(projects.scheduleEmail)
    implementation(projects.mailer)

    // MCP tool core - the Model Context Protocol server mounted at /mcp
    implementation(projects.mcp)

    // Logging
    implementation(libs.logback.classic)

    // Testing
    testImplementation(libs.kotlin.test)
}

// Run the server from the repo root so ./config.properties resolves to the
// shared dev config file. The application plugin registers `run` lazily.
tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    workingDir = rootProject.projectDir
}

ktor {
    fatJar {
        archiveFileName.set("server.jar")
    }
    // Generate OpenAPI metadata from the routing code at compile time; the
    // Swagger UI (served dev-only in Routing.kt) renders it. codeInferenceEnabled
    // lets the plugin infer params/bodies/responses from handler code; annotate
    // explicitly with .describe {} where inference falls short.
    openApi {
        enabled = true
        codeInferenceEnabled = true
    }
}

// JasperReports registers its extensions (PDF exporter, DejaVu fonts, core
// handlers) through same-named `jasperreports_extension.properties` files at
// the root of three different jars. Shadow's default "first wins" merge keeps
// only one of them, silently dropping the font registration - Greek text then
// breaks in PDFs from the deployed fat jar (while `:server:run` works, since
// it uses the real classpath). Concatenating them preserves all registrations;
// the keys are distinct per extension so appending is safe.
tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>().configureEach {
    // The Ktor plugin sets duplicatesStrategy=EXCLUDE, which drops duplicate
    // entries before transformers ever see them - the append below would be a
    // no-op. INCLUDE is Shadow's own default, chosen so transformers work.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    append("jasperreports_extension.properties")
    mergeServiceFiles()
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

// Runs the legacy migration with the module's full runtime classpath - the
// documented `java -cp server.jar ...MigrationToolKt` needs a fat jar, this
// does not:  ./gradlew :server:migrateCli --args="--dump ... --schema ..."
tasks.register<JavaExec>("migrateCli") {
    group = "migration"
    description = "Runs the legacy-dump migration CLI"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("eu.anifantakis.commercials.server.migration.MigrationToolKt")
}

// Galaxy (new ERP) import - dry-run by default:
//   ./gradlew :server:galaxyImportCli --args="--galaxy-dir ... --old-export-dir ... --schema ..."
tasks.register<JavaExec>("galaxyImportCli") {
    group = "migration"
    description = "Runs the Galaxy ERP import CLI (dry-run unless --apply)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("eu.anifantakis.commercials.server.galaxy.GalaxyImportToolKt")
    standardInput = System.`in`
}
