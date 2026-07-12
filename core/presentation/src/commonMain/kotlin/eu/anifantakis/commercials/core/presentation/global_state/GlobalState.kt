package eu.anifantakis.commercials.core.presentation.global_state

import androidx.compose.runtime.Immutable
import eu.anifantakis.commercials.core.presentation.helper.UiText

/**
 * App-wide UI state (kmp-developer global-state MVI): the loading overlay
 * and content flags live here, never in per-screen State. [isCriticalLoading]
 * marks an operation that must not be interrupted (golden-standard
 * AppLoadingIndicator convention): the overlay blocks taps either way, and
 * the navigation host additionally swallows back presses while critical.
 */
@Immutable
data class GlobalState(
    val isLoading: Boolean = false,
    val isCriticalLoading: Boolean = false,
    val hasContent: Boolean = false,
)

sealed interface GlobalIntent {
    data class ShowLoading(val critical: Boolean = false) : GlobalIntent
    /** [critical] must MATCH the ShowLoading it balances (ref-counted per tier). */
    data class HideLoading(val critical: Boolean = false) : GlobalIntent
    data class UpdateHasContent(val hasContent: Boolean) : GlobalIntent
    /** Routed to [GlobalEffect.SnackBarMessage]; state is unchanged. */
    data class ShowSnackbar(val message: UiText, val actionLabel: String? = null) : GlobalIntent
}

sealed interface GlobalEffect {
    /**
     * Carries a [UiText] (golden-standard: resolve at the UI edge, in the
     * language active at DISPLAY time): `Res(key)` for localized app text,
     * `Dynamic(text)` for a verbatim server message.
     */
    data class SnackBarMessage(val message: UiText, val actionLabel: String? = null) : GlobalEffect
}
