package eu.anifantakis.commercials.core.presentation.design_system.components

import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * The app's blocking progress overlay (golden-standard component): full-screen
 * scrim that swallows every tap behind it while [isLoading]. [isCritical]
 * marks an uninterruptible operation — this module has no platform back
 * dispatcher, so the actual back-blocking for critical loads lives in the
 * navigation host (NavigationRoot guards its onBack on
 * `GlobalState.isCriticalLoading`), keeping this component pure-common.
 *
 * [appearAfter] is the ANTI-FLASH grace period: a load that finishes inside it
 * shows nothing at all, instead of a scrim that blinks on and off. Zero by
 * default, so existing call sites keep their current behaviour; give it ~250ms
 * wherever the operation is USUALLY fast (a local query, a warm cache) and the
 * overlay would otherwise flicker on every interaction.
 */
@Composable
fun AppLoadingIndicator(
    isLoading: Boolean,
    isCritical: Boolean = false,
    modifier: Modifier = Modifier,
    appearAfter: Duration = Duration.ZERO,
) {
    // Held OUTSIDE the isLoading branch: the moment loading stops, the effect
    // restarts and re-arms the delay, so a burst of quick loads can never
    // accumulate into a scrim that appears after the work is done.
    var elapsed by remember { mutableStateOf(false) }
    LaunchedEffect(isLoading, appearAfter) {
        elapsed = false
        if (isLoading) {
            if (appearAfter > Duration.ZERO) delay(appearAfter)
            elapsed = true
        }
    }

    if (isLoading && elapsed) {
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

/** The screen the overlay sits ON - without it the scrim has nothing to dim. */
@Composable
private fun ScreenBehindTheScrim() {
    Column(
        modifier = Modifier.padding(UIConst.paddingRegular),
        verticalArrangement = Arrangement.spacedBy(UIConst.paddingExtraSmall),
    ) {
        AppText("Crete TV", AppTextStyle.ITEM_TITLE)
        AppText("Wednesday 15 July - 14 breaks", AppTextStyle.BODY)
        AppText("38 spots, 4 customers", AppTextStyle.NOTE)
    }
}

// isLoading = false: the component must draw NOTHING. The state that is easiest
// to break and hardest to notice.
@Preview
@Composable
private fun AppLoadingIndicatorIdlePreview() = AppPreview {
    Box(Modifier.size(260.dp, 140.dp)) {
        ScreenBehindTheScrim()
        AppLoadingIndicator(isLoading = false)
    }
}

@Preview
@Composable
private fun AppLoadingIndicatorPreview() = AppPreview {
    Box(Modifier.size(260.dp, 140.dp)) {
        ScreenBehindTheScrim()
        AppLoadingIndicator(isLoading = true)
    }
}

// Critical: same picture, different a11y state text ("do not go back") - the
// back-blocking itself lives in the navigation host, not here.
@Preview
@Composable
private fun AppLoadingIndicatorCriticalPreview() = AppPreview {
    Box(Modifier.size(260.dp, 140.dp)) {
        ScreenBehindTheScrim()
        AppLoadingIndicator(isLoading = true, isCritical = true)
    }
}

// The scrim is a fixed 25% BLACK, so on the dark palette it dims a surface that
// is already dark - the one place this component can go invisible.
@Preview
@Composable
private fun AppLoadingIndicatorDarkPreview() = AppPreview(dark = true) {
    Box(Modifier.size(260.dp, 140.dp)) {
        ScreenBehindTheScrim()
        AppLoadingIndicator(isLoading = true)
    }
}
