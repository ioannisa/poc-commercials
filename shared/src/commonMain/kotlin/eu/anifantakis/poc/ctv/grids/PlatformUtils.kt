package eu.anifantakis.poc.ctv.grids

/**
 * Multiplatform utility functions
 */

/**
 * Format an integer with leading zeros (multiplatform compatible)
 * e.g., padZero(5, 2) returns "05"
 */
fun padZero(value: Int, length: Int = 2): String {
    val str = value.toString()
    return if (str.length < length) {
        "0".repeat(length - str.length) + str
    } else {
        str
    }
}

/**
 * Format time as HH:MM
 */
fun formatTime(hour: Int, minute: Int): String = "${padZero(hour)}:${padZero(minute)}"

/**
 * Format duration in seconds as MM:SS
 */
fun formatDuration(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${padZero(minutes)}:${padZero(seconds)}"
}
