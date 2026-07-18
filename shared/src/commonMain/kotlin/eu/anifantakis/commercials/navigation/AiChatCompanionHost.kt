package eu.anifantakis.commercials.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.domain.auth.AiChatProviderOption
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.mediumSpec
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatPreferences
import eu.anifantakis.commercials.feature.ai_chat.presentation.screens.ai_chat.AiChatScreenRoot
import org.koin.compose.koinInject

/**
 * HOW the AI companion is hosted is a platform decision:
 *
 * - **Desktop (jvm)**: a separate, resizable, ALWAYS-ON-TOP OS window. The
 *   main window keeps its full width - on a small display the schedule is
 *   never squeezed, and the chat can sit on a second monitor.
 * - **Web (and the compile-only mobile targets)**: a docked, drag-resizable
 *   side panel - browsers have no OS windows to give us.
 *
 * Rendered from [NavigationRoot]'s content row; [windowWidth] is that row's
 * max width, which the docked variant uses to cap its own width so the main
 * content never drops below its working minimum.
 */
@Composable
internal expect fun AiChatCompanionHost(
    visible: Boolean,
    windowWidth: Dp,
    providers: () -> List<AiChatProviderOption>,
    onClose: () -> Unit,
)

/** The docked variant's drag-resize bounds. */
internal val AI_PANEL_MIN_WIDTH = 320.dp
internal val AI_PANEL_MAX_WIDTH = 720.dp

/**
 * The width the MAIN CONTENT keeps before the docked panel stops growing.
 * Sized for the timetable's legacy toolbar, whose fixed group boxes overflow
 * (and clip the account/preferences cluster) when squeezed much below this.
 */
internal val AI_PANEL_MIN_CONTENT = 1360.dp

/**
 * The docked side-panel variant: animated, drag-resizable from its inner
 * edge, width silently persisted and clamped against the CURRENT window on
 * every render - a stored-wide panel reopening in a smaller window still
 * leaves the content its minimum.
 */
@Composable
internal fun DockedAiChatPanel(
    visible: Boolean,
    windowWidth: Dp,
    providers: () -> List<AiChatProviderOption>,
    onClose: () -> Unit,
) {
    val motion = AppTheme.visualTokens.motion
    val a11y = AppTheme.a11y
    val ceiling = (windowWidth - AI_PANEL_MIN_CONTENT)
        .coerceAtMost(AI_PANEL_MAX_WIDTH)
        .coerceAtLeast(AI_PANEL_MIN_WIDTH)
    AnimatedVisibility(
        visible = visible,
        enter = expandHorizontally(motion.mediumSpec(a11y), expandFrom = Alignment.Start),
        exit = shrinkHorizontally(motion.mediumSpec(a11y), shrinkTowards = Alignment.Start),
    ) {
        val prefs = koinInject<AiChatPreferences>()
        var preferredWidth by remember { mutableStateOf(prefs.panelWidthDp.dp) }
        val panelWidth = preferredWidth.coerceIn(AI_PANEL_MIN_WIDTH, ceiling)
        val density = LocalDensity.current
        // Dragging the handle AWAY from the panel grows it: the handle is on
        // the panel's inner edge, which mirrors under RTL.
        val grow = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1 else -1
        Row(Modifier.fillMaxHeight()) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(8.dp)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            val d = with(density) { delta.toDp() }
                            preferredWidth = (panelWidth + d * grow).coerceIn(AI_PANEL_MIN_WIDTH, ceiling)
                        },
                        onDragStopped = { prefs.panelWidthDp = preferredWidth.value.toInt() },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                VerticalDivider()
            }
            AiChatScreenRoot(
                providers = providers,
                onClose = onClose,
                modifier = Modifier.width(panelWidth).fillMaxHeight(),
            )
        }
    }
}
