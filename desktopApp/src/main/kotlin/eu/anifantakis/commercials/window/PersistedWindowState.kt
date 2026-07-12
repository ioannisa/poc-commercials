package eu.anifantakis.commercials.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeWriteMode
import eu.anifantakis.lib.ksafe.invoke
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import java.awt.GraphicsEnvironment

/**
 * Window geometry persisted across launches - PLAIN KSafe mode on purpose
 * (x/y/width/height are not secrets; paying Keychain/Keystore cost per write
 * would be pure overhead). Primitives, not a @Serializable blob, so the
 * entry app needs no serialization plugin.
 */
class WindowStateStore(private val ksafe: KSafe) {
    var x: Int by ksafe(-1, key = "win_x", mode = KSafeWriteMode.Plain)
    var y: Int by ksafe(-1, key = "win_y", mode = KSafeWriteMode.Plain)
    var width: Int by ksafe(1440, key = "win_w", mode = KSafeWriteMode.Plain)
    var height: Int by ksafe(900, key = "win_h", mode = KSafeWriteMode.Plain)
    var maximized: Boolean by ksafe(true, key = "win_max", mode = KSafeWriteMode.Plain)
}

/**
 * Restores the persisted geometry (clamped against the CURRENT screens - an
 * unplugged external monitor must not restore the window off-screen) and
 * saves it back debounced (a resize drag emits every frame; the store must
 * see the settled value, not the storm).
 */
@OptIn(FlowPreview::class)
@Composable
fun rememberPersistedWindowState(store: WindowStateStore): WindowState {
    val state = rememberWindowState(
        placement = if (store.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
        size = DpSize(store.width.dp, store.height.dp),
        position = restorePosition(store),
    )
    LaunchedEffect(state) {
        snapshotFlow {
            Triple(state.size, state.position, state.placement)
        }
            .debounce(500)
            .collect { (size, position, placement) ->
                store.maximized = placement == WindowPlacement.Maximized
                if (placement == WindowPlacement.Floating) {
                    store.width = size.width.value.toInt()
                    store.height = size.height.value.toInt()
                    if (position is WindowPosition.Absolute) {
                        store.x = position.x.value.toInt()
                        store.y = position.y.value.toInt()
                    }
                }
            }
    }
    return state
}

private fun restorePosition(store: WindowStateStore): WindowPosition {
    if (store.x < 0 && store.y < 0) return WindowPosition.PlatformDefault
    // Clamp: only restore a position that is still on SOME screen.
    val onAScreen = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { device ->
        val b = device.defaultConfiguration.bounds
        store.x in b.x until (b.x + b.width) && store.y in b.y until (b.y + b.height)
    }
    return if (onAScreen) WindowPosition.Absolute(store.x.dp, store.y.dp)
    else WindowPosition.PlatformDefault
}
