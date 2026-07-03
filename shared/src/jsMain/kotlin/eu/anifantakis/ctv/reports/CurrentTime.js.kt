package eu.anifantakis.ctv.reports

import kotlin.js.Date

actual fun currentTimeMillis(): Long = Date.now().toLong()
