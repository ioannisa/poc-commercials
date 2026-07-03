plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
    application
}

group = "eu.anifantakis.poc.ctv"
version = "1.0.0"

application {
    mainClass.set("eu.anifantakis.poc.ctv.server.ApplicationKt")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

dependencies {
    // Ktor Server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.json)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // Report engine + wire DTOs (brings JasperReports transitively)
    implementation(project(":reportcore"))

    // Logging
    implementation(libs.logback.classic)

    // MySQL JDBC driver (POC: ad-hoc DB queries)
    implementation(libs.mysql.connector.j)
    implementation(libs.hikaricp)

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
