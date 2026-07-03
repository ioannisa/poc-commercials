package eu.anifantakis.commercials

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.text.LocalBackgroundTextMeasurementExecutor
import java.util.concurrent.Executors

private object TextPrefetchExecutor {
    val instance by lazy {
        Executors.newFixedThreadPool(3) { runnable ->
            Thread(runnable, "TextPrefetch").apply {
                priority = Thread.MIN_PRIORITY
            }
        }
    }
}

@Composable
actual fun WithTextPrefetch(
    mode: TextPrefetchMode,
    content: @Composable () -> Unit
) {
    if (mode == TextPrefetchMode.DISABLED || (mode == TextPrefetchMode.HARDWARE_BASED && Runtime.getRuntime().availableProcessors() < 4)) {
        content()
        return
    }
    CompositionLocalProvider(
        LocalBackgroundTextMeasurementExecutor provides TextPrefetchExecutor.instance
    ) {
        content()
    }
}