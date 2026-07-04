/*
 * Customer email module - the modern successor of the legacy app's core
 * workflow (1,282 archived sends, 2006-2026: the monthly "ΠΡΟΓΡΑΜΜΑΤΙΣΜΟΙ"
 * schedule email per customer):
 *
 * - ScheduleEmail:  renders the customer's month grid as email-safe HTML
 *                   (inline styles, table layout) with programme colours,
 *                   weekend highlights and the per-programme totals legend -
 *                   faithful to the legacy format, modernised.
 * - SmtpMailer:     Jakarta Mail (Angus) SMTP sender.
 *
 * Pure JVM library: data in, HTML/send out. Knows nothing about persistence
 * or HTTP - the server assembles the data and provides SMTP settings from
 * stations.yaml.
 */
plugins {
    alias(libs.plugins.kotlinJvm)
}

group = "eu.anifantakis.commercials"
version = "1.0.0"

dependencies {
    implementation(libs.angus.mail)

    testImplementation(libs.kotlin.test)
}
