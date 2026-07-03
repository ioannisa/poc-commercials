package eu.anifantakis.commercials.reports

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

// Report generation is not available on iOS - the Koin platform module
// binds UnsupportedReportService (see di/PlatformModule.ios.kt).

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
