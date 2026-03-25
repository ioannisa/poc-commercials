package eu.anifantakis.poc.ctv

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

enum class TextPrefetchMode {
    DISABLED,
    ENABLED,
    HARDWARE_BASED;
}

@androidx.compose.runtime.Composable
expect fun WithTextPrefetch(
    mode: TextPrefetchMode = TextPrefetchMode.HARDWARE_BASED,
    content: @androidx.compose.runtime.Composable () -> Unit
)