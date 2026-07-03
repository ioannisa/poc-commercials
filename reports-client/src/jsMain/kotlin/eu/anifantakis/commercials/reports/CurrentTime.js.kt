package eu.anifantakis.commercials.reports

import kotlin.js.Date

actual fun currentTimeMillis(): Long = Date.now().toLong()
