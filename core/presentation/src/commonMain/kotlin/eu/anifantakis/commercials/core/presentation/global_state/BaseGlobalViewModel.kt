package eu.anifantakis.commercials.core.presentation.global_state

import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.presentation.helper.UiText
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import kotlinx.coroutines.CancellationException
import org.koin.mp.KoinPlatform

/**
 * Base for every feature ViewModel: exposes the global UI state and the
 * app-wide helpers (loading overlay, snackbar), resolved via Koin defaults
 * so subclasses keep clean constructors (kmp-developer convention).
 */
abstract class BaseGlobalViewModel(
    protected val globalStateContainer: GlobalStateContainer = KoinPlatform.getKoin().get(),
) : ViewModel() {

    val globalUiState: GlobalState by globalStateContainer.state.toComposeState(viewModelScope)

    /** [critical] = uninterruptible: the nav host also blocks back while it shows. */
    fun showLoading(critical: Boolean = false) =
        globalStateContainer.dispatch(GlobalIntent.ShowLoading(critical))
    /** [critical] must match the corresponding showLoading (ref-counted per tier). */
    fun hideLoading(critical: Boolean = false) =
        globalStateContainer.dispatch(GlobalIntent.HideLoading(critical))
    fun updateHasContent(has: Boolean) = globalStateContainer.dispatch(GlobalIntent.UpdateHasContent(has))

    /** Golden-standard carrier: [UiText] resolves at the UI edge, in the display-time language. */
    fun showSnackbar(message: UiText, actionLabel: String? = null) =
        globalStateContainer.dispatch(GlobalIntent.ShowSnackbar(message, actionLabel))

    /** Convenience for the common localized case. */
    fun showSnackbar(key: StringKey, actionLabel: String? = null) =
        showSnackbar(UiText.Res(key), actionLabel)

    /**
     * Runs [block] under the global loading overlay; always hides it, rethrows
     * cancellation. [critical] additionally blocks back navigation for
     * uninterruptible operations (golden-standard AppLoadingIndicator).
     */
    suspend fun <T> withLoading(critical: Boolean = false, block: suspend () -> T): Result<T> {
        showLoading(critical)
        return try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            hideLoading(critical)
        }
    }
}
