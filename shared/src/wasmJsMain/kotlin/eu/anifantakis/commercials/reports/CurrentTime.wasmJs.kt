package eu.anifantakis.commercials.reports

// External JavaScript function to get current time
@OptIn(ExperimentalWasmJsInterop::class)
private fun dateNow(): Double = js("Date.now()")

actual fun currentTimeMillis(): Long = dateNow().toLong()
