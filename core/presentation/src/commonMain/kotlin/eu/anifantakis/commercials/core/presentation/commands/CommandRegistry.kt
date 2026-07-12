package eu.anifantakis.commercials.core.presentation.commands

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a menu item renders and invokes for one command. */
data class AppCommandState(
    val enabled: Boolean,
    val execute: () -> Unit,
)

/** Disposal handle - registration ALWAYS lives inside a DisposableEffect. */
interface CommandRegistration {
    fun dispose()
}

/**
 * The stateful, lifecycle-aware command router between app chrome (desktop
 * MenuBar, keyboard shortcuts) and whatever screen currently owns an action.
 *
 * - OBSERVABLE: the MenuBar renders `enabled` from [commandStates]; a bare
 *   `() -> Boolean` would keep the value fresh but give Compose nothing to
 *   invalidate on ("canExport flipped but the item stayed grey").
 *   Registrants re-publish on state change via [refresh] (the
 *   [RegisterAppCommand] helper wires that automatically).
 * - LIFO ownership: the most recently registered LIVE owner wins (a dialog
 *   temporarily overrides its parent); on dispose the previous registration
 *   becomes active again. Deterministic, not accidental.
 */
class CommandRegistry {

    private class Entry(
        val owner: Any,
        val enabled: () -> Boolean,
        val handler: () -> Unit,
    )

    private val stacks = LinkedHashMap<AppCommand, ArrayDeque<Entry>>()
    private val lock = Any()

    private val _commandStates = MutableStateFlow<Map<AppCommand, AppCommandState>>(emptyMap())
    val commandStates: StateFlow<Map<AppCommand, AppCommandState>> = _commandStates.asStateFlow()

    fun register(
        owner: Any,
        command: AppCommand,
        enabled: () -> Boolean,
        handler: () -> Unit,
    ): CommandRegistration {
        val entry = Entry(owner, enabled, handler)
        stacks.getOrPut(command) { ArrayDeque() }.addLast(entry)
        publish()
        return object : CommandRegistration {
            override fun dispose() {
                stacks[command]?.remove(entry)
                publish()
            }
        }
    }

    /**
     * Re-evaluates every active `enabled()` and publishes. Call when a
     * source of an enabled-flag changes; RegisterAppCommand calls it for you.
     */
    fun refresh() = publish()

    fun execute(command: AppCommand) {
        val state = _commandStates.value[command] ?: return
        if (state.enabled) state.execute()
    }

    private fun publish() {
        _commandStates.value = stacks.mapNotNull { (command, stack) ->
            val top = stack.lastOrNull() ?: return@mapNotNull null
            command to AppCommandState(enabled = top.enabled(), execute = top.handler)
        }.toMap()
    }
}

/**
 * The one sanctioned way for a screen to own a command: registration keyed
 * on the OWNER only (state changes update in place via rememberUpdatedState +
 * refresh, no re-registration churn), disposal on leave (navigation evicts
 * stale handlers automatically).
 */
@Composable
fun RegisterAppCommand(
    registry: CommandRegistry,
    owner: Any,
    command: AppCommand,
    enabled: Boolean = true,
    handler: () -> Unit,
) {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentHandler by rememberUpdatedState(handler)
    DisposableEffect(registry, owner, command) {
        val registration = registry.register(
            owner = owner,
            command = command,
            enabled = { currentEnabled },
            handler = { currentHandler() },
        )
        onDispose { registration.dispose() }
    }
    // Republish whenever the enabled flag actually changes so the MenuBar's
    // StateFlow observers see it (the registration itself is stable).
    DisposableEffect(enabled) {
        registry.refresh()
        onDispose { }
    }
}
