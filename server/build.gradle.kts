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
    implementation(libs.ktor.serialization.json)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // JasperReports
    implementation(libs.jasperreports)
    implementation(libs.jasperreports.pdf)
    implementation(libs.jasperreports.fonts)
    implementation(libs.jasperreports.jdt)

    // Logging
    implementation(libs.logback.classic)

    // Testing
    testImplementation(libs.kotlin.test)
}

ktor {
    fatJar {
        archiveFileName.set("server.jar")
    }
}
