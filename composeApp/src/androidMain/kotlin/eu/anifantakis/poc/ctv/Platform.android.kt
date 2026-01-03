package eu.anifantakis.poc.ctv

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.text.LocalBackgroundTextMeasurementExecutor
import java.util.concurrent.Executors

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

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
    enabled: Boolean,
    content: @Composable () -> Unit
) {
    if (!enabled) {
        content()
        return
    }
    CompositionLocalProvider(
        LocalBackgroundTextMeasurementExecutor provides TextPrefetchExecutor.instance
    ) {
        content()
    }
}