package eu.anifantakis.commercials.core.presentation.global_state

import androidx.compose.runtime.Immutable

/**
 * App-wide UI state (kmp-developer global-state MVI): the loading overlay
 * and content flags live here, never in per-screen State.
 */
@Immutable
data class GlobalState(
    val isLoading: Boolean = false,
    val hasContent: Boolean = false,
)

sealed interface GlobalIntent {
    data object ShowLoading : GlobalIntent
    data object HideLoading : GlobalIntent
    data class UpdateHasContent(val hasContent: Boolean) : GlobalIntent
    /** Routed to [GlobalEffect.SnackBarMessage]; state is unchanged. */
    data class ShowSnackbar(val message: String, val actionLabel: String? = null) : GlobalIntent
}

sealed interface GlobalEffect {
    /**
     * Plain String, not a StringKey: this POC is deliberately mono-lingual
     * (Greek), so the StringKey/LocalizationManager layer of the skill is a
     * recorded deviation - introduce it if a second language ever lands.
     */
    data class SnackBarMessage(val message: String, val actionLabel: String? = null) : GlobalEffect
}
