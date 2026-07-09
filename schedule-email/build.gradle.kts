/*
 * Schedule-email core - assembles the customer monthly schedule email
 * (ScheduleEmailData, one section per spot) from the station DB. The single
 * home of that logic, shared by the Ktor server's REST route and the MCP
 * `send_schedule_email` tool, so the two can never diverge.
 *
 * JVM-only. Depends on `persistence` (StationDb, CommercialRow, SmtpConfig)
 * and `mailer` (ScheduleEmailData & friends) - both appear in its public API,
 * so both are exposed with `api`. It renders/sends nothing itself: callers use
 * `mailer.renderScheduleEmail` / `mailer.SmtpMailer`.
 */
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
}

group = "eu.anifantakis.commercials"
version = "1.0.0"

dependencies {
    api(projects.persistence)
    api(projects.mailer)

    testImplementation(libs.kotlin.test)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}
