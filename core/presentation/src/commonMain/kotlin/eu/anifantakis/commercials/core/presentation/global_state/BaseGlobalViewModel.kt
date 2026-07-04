package eu.anifantakis.commercials.core.presentation.global_state

import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.anifantakis.commercials.core.presentation.helper.toComposeState
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

    fun showLoading() = globalStateContainer.dispatch(GlobalIntent.ShowLoading)
    fun hideLoading() = globalStateContainer.dispatch(GlobalIntent.HideLoading)
    fun updateHasContent(has: Boolean) = globalStateContainer.dispatch(GlobalIntent.UpdateHasContent(has))

    fun showSnackbar(message: String, actionLabel: String? = null) =
        globalStateContainer.dispatch(GlobalIntent.ShowSnackbar(message, actionLabel))

    /** Runs [block] under the global loading overlay; always hides it, rethrows cancellation. */
    suspend fun <T> withLoading(block: suspend () -> T): Result<T> {
        showLoading()
        return try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            hideLoading()
        }
    }
}
