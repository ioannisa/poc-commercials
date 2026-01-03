package eu.anifantakis.poc.ctv

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

@androidx.compose.runtime.Composable
expect fun WithTextPrefetch(
    enabled: Boolean = true,
    content: @androidx.compose.runtime.Composable () -> Unit
)