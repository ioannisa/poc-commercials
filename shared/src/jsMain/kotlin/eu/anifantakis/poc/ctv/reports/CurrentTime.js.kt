package eu.anifantakis.poc.ctv.reports

import kotlin.js.Date

actual fun currentTimeMillis(): Long = Date.now().toLong()
