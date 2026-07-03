package eu.anifantakis.commercials

import androidx.compose.runtime.Composable

@Composable
actual fun WithTextPrefetch(
    mode: TextPrefetchMode,
    content: @Composable () -> Unit
) {
    content()
}