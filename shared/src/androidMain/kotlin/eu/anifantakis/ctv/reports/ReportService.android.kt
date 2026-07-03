package eu.anifantakis.ctv.reports

// Report generation is not available on Android - the Koin platform module
// binds UnsupportedReportService (see di/PlatformModule.android.kt).

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
