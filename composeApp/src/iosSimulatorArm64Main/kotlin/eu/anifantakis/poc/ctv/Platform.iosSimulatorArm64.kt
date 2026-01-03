package eu.anifantakis.poc.ctv

import androidx.compose.runtime.Composable


@Composable
actual fun WithTextPrefetch(
    enabled: Boolean,
    content: @Composable () -> Unit
) {
    content()
}

actual fun getPlatform(): Platform {
    TODO("Not yet implemented")
}