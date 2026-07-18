package eu.anifantakis.commercials.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.domain.auth.AiChatProviderOption
import eu.anifantakis.commercials.core.presentation.design_system.AppDrawableRepo
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIcon
import eu.anifantakis.commercials.core.presentation.design_system.mediumSpec
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.feature.ai_chat.domain.AiChatPreferences
import eu.anifantakis.commercials.feature.ai_chat.presentation.screens.ai_chat.AiChatScreenRoot
import eu.anifantakis.commercials.feature.ai_chat.presentation.screens.ai_chat.AiChatViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.compose.koinInject

/**
 * HOW the AI companion is hosted is a platform decision:
 *
 * - **Web (and the compile-only mobile targets)**: an OVERLAY panel that
 *   slides in over the content from the end edge - it COVERS instead of
 *   squeezing, so the app never reflows; the uncovered part stays fully
 *   interactive, and a collapse chevron parks the panel into a slim
 *   re-expand tab without losing anything.
 * - **Desktop (jvm)**: the same overlay by default, PLUS a "detach" header
 *   action that moves the chat into a separate, resizable, always-on-top OS
 *   window (and "attach" brings it back). The choice persists.
 *
 * Rendered as the TOP layer of [NavigationRoot]'s content box; [windowWidth]
 * caps the drag-resize so some app is always visible beside the panel.
 */
@Composable
internal expect fun AiChatCompanionHost(
    visible: Boolean,
    windowWidth: Dp,
    providers: () -> List<AiChatProviderOption>,
    onClose: () -> Unit,
)

/** The overlay's drag-resize bounds. */
internal val AI_PANEL_MIN_WIDTH = 320.dp

/** How much of the app must stay visible (and clickable) beside the overlay. */
internal val AI_PANEL_MIN_UNCOVERED = 240.dp

/**
 * The in-app OVERLAY companion: slides over the content from the end edge
 * (start edge under RTL, for free via [Alignment.CenterEnd]), drag-resizable
 * from its inner edge, width silently persisted. Collapse parks it into a
 * slim mid-edge tab - one click re-expands to exactly where it was. The
 * content underneath neither moves nor reflows, and everything the panel
 * does not cover keeps taking clicks.
 */
@Composable
internal fun OverlayAiChatPanel(
    visible: Boolean,
    windowWidth: Dp,
    providers: () -> List<AiChatProviderOption>,
    onClose: () -> Unit,
    onDetach: (() -> Unit)? = null,
    viewModel: AiChatViewModel = koinViewModel(),
) {
    if (!visible) return
    val prefs = koinInject<AiChatPreferences>()
    var preferredWidth by remember { mutableStateOf(prefs.panelWidthDp.dp) }
    var collapsed by remember { mutableStateOf(false) }
    val ceiling = (windowWidth - AI_PANEL_MIN_UNCOVERED).coerceAtLeast(AI_PANEL_MIN_WIDTH)
    val panelWidth = preferredWidth.coerceIn(AI_PANEL_MIN_WIDTH, ceiling)
    val density = LocalDensity.current
    val motion = AppTheme.visualTokens.motion
    val a11y = AppTheme.a11y
    // Slide offsets are raw x pixels - mirror them by hand under RTL, where
    // the END edge (and therefore the panel) is on the LEFT.
    val edge = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    // Dragging the handle AWAY from the panel grows it.
    val grow = -edge

    Box(Modifier.fillMaxSize()) {
        // The parked state: a slim tab on the edge, one click to re-expand.
        AnimatedVisibility(
            visible = collapsed,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = fadeIn(motion.mediumSpec(a11y)),
            exit = fadeOut(motion.mediumSpec(a11y)),
        ) {
            Surface(
                shadowElevation = 8.dp,
                tonalElevation = 2.dp,
                shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
                modifier = Modifier
                    .width(28.dp)
                    .height(88.dp)
                    .clickable { collapsed = false },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AppIcon(
                        AppDrawableRepo.keyboardArrowLeft,
                        contentDescription = Strings[StringKey.AI_CHAT_EXPAND],
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = !collapsed,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            enter = slideInHorizontally(motion.mediumSpec(a11y)) { it * edge },
            exit = slideOutHorizontally(motion.mediumSpec(a11y)) { it * edge },
        ) {
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
                Surface(shadowElevation = 12.dp, tonalElevation = 2.dp) {
                    AiChatScreenRoot(
                        providers = providers,
                        onClose = onClose,
                        modifier = Modifier.width(panelWidth).fillMaxHeight(),
                        onCollapse = { collapsed = true },
                        onDetach = onDetach,
                        viewModel = viewModel,
                    )
                }
            }
        }
    }
}
