package eu.anifantakis.commercials.core.presentation.design_system.components

import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/**
 * The app's blocking progress overlay (golden-standard component): full-screen
 * scrim that swallows every tap behind it while [isLoading]. [isCritical]
 * marks an uninterruptible operation — this module has no platform back
 * dispatcher, so the actual back-blocking for critical loads lives in the
 * navigation host (NavigationRoot guards its onBack on
 * `GlobalState.isCriticalLoading`), keeping this component pure-common.
 */
@Composable
fun AppLoadingIndicator(
    isLoading: Boolean,
    isCritical: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (isLoading) {
        val processing = Strings[StringKey.COMMON_PROCESSING]
        val waitText = Strings[
            if (isCritical) StringKey.COMMON_PLEASE_WAIT_NO_BACK else StringKey.COMMON_PLEASE_WAIT
        ]
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f))
                // Block unintended taps behind the full-screen scrim
                .pointerInput(Unit) { detectTapGestures { } }
                // Accessibility: convey what's happening on screen
                .semantics {
                    contentDescription = processing
                    stateDescription = waitText
                }
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
}
