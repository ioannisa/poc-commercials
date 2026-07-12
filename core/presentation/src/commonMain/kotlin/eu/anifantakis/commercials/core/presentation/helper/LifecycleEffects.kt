package eu.anifantakis.commercials.core.presentation.helper

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Declarative lifecycle hooks for a screen — the parameter-object pattern
 * (AndroidSkeletonApp heritage): the screen states WHAT should happen on each
 * lifecycle event instead of wiring observers.
 *
 * The [onCreate] / [onFirstCreate] distinction: [onCreate] re-runs when the
 * composition is recreated (Android configuration change), [onFirstCreate]
 * runs ONCE per screen instance, surviving recreation via rememberSaveable —
 * use it for one-shot work (analytics, initial fetch) that must not repeat on
 * rotation. Prefer driving DATA loads from the ViewModel's init; reach for
 * [onResume] when the screen must refresh every time it becomes active again.
 *
 * KMP fidelity: pure commonMain (multiplatform androidx.lifecycle). Android
 * delivers the full event set; iOS maps events to the hosting
 * ComposeUIViewController's appearance + app foreground/background (no config
 * recreation, so onCreate == onFirstCreate); desktop/web map to window/tab
 * state. Treat [onResume] as "the screen became active again" and do not
 * build logic on exact event ordering across platforms.
 */
data class LifecycleConfig(
    val onResume: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onDestroy: () -> Unit = {},
    val onCreate: () -> Unit = {},
    val onFirstCreate: () -> Unit = {},
    val onAny: () -> Unit = {},
)

@Composable
fun LifecycleEffects(
    config: LifecycleConfig,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
) {
    val onFirstCreateExecuted = rememberSaveable { mutableStateOf(false) }
    val onCreateExecuted = remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner.lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            config.onAny()
            when (event) {
                Lifecycle.Event.ON_RESUME -> config.onResume()
                Lifecycle.Event.ON_START -> config.onStart()
                Lifecycle.Event.ON_PAUSE -> config.onPause()
                Lifecycle.Event.ON_STOP -> config.onStop()
                Lifecycle.Event.ON_DESTROY -> config.onDestroy()
                Lifecycle.Event.ON_CREATE -> {
                    // classic onCreate: repeats when the composition is recreated
                    if (!onCreateExecuted.value) {
                        config.onCreate()
                        onCreateExecuted.value = true
                    }
                    // once per screen instance, survives recreation (rememberSaveable)
                    if (!onFirstCreateExecuted.value) {
                        config.onFirstCreate()
                        onFirstCreateExecuted.value = true
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
