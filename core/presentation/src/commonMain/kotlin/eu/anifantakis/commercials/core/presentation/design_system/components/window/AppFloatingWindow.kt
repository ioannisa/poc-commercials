package eu.anifantakis.commercials.core.presentation.design_system.components.window

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.zIndex
import eu.anifantakis.commercials.core.presentation.design_system.AppDrawableRepo
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIconButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppIconSize
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings

/**
 * The chrome of one in-canvas window: title-bar drag to move, eight handles
 * to resize, minimize/close actions, focus-on-press. Rendered by
 * [AppWindowHost] inside its LTR-pinned positioning layer; [appDirection]
 * restores the app's real layout direction for the title bar and content, so
 * RTL mirrors the window's INSIDE while canvas geometry stays absolute.
 *
 * The default placement (centered, cascaded) is applied lazily: geometry
 * stays Unspecified - re-centering on every container resize - until the
 * first drag writes it into the state and the user owns it from then on.
 */
@Composable
internal fun AppFloatingWindow(
    window: AppWindowState,
    container: DpSize,
    focused: Boolean,
    appDirection: LayoutDirection,
    onFocus: () -> Unit,
    onMinimize: () -> Unit,
    onToggleDock: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val t = AppTheme.visualTokens
    val interaction = AppTheme.interaction

    val defaultSize = remember(container, t, window.minSize) {
        DpSize(
            width = (container.width * 0.6f)
                .coerceIn(t.dialogMinWidth, t.dialogMaxWidth)
                .coerceAtLeast(window.minSize.width)
                .coerceAtMost(container.width),
            height = (container.height * 0.7f)
                .coerceAtLeast(window.minSize.height)
                .coerceAtMost(container.height),
        )
    }
    val size = if (window.size.isSpecified) window.size else defaultSize
    val defaultPosition = remember(container, size, window.cascade) {
        val step = WINDOW_CASCADE_STEP * window.cascade
        DpOffset(
            x = ((container.width - size.width) / 2 + step)
                .clampSafe(0.dp, container.width - size.width),
            y = ((container.height - size.height) / 2 + step)
                .clampSafe(0.dp, container.height - size.height),
        )
    }

    // Gesture lambdas outlive the composition that created them (a drag spans
    // many frames) - route every value they read through rememberUpdatedState
    // or the window's own snapshot state, never through plain captures.
    val currentContainer by rememberUpdatedState(container)
    val currentDefaultSize by rememberUpdatedState(defaultSize)
    val currentDefaultPosition by rememberUpdatedState(defaultPosition)
    val currentOnFocus by rememberUpdatedState(onFocus)
    val currentOnClose by rememberUpdatedState(onClose)

    fun materializeAndFocus() {
        if (!window.size.isSpecified) window.size = currentDefaultSize
        if (!window.position.isSpecified) window.position = currentDefaultPosition
        currentOnFocus()
    }

    Surface(
        modifier = modifier
            // Deferred position read: dragging invalidates only layout, the
            // window's content never recomposes for a move.
            .offset {
                val p = window.position
                val effective = if (p.isSpecified) p else currentDefaultPosition
                val s = if (window.size.isSpecified) window.size else currentDefaultSize
                IntOffset(
                    effective.x
                        .clampSafe(WINDOW_MIN_VISIBLE - s.width, currentContainer.width - WINDOW_MIN_VISIBLE)
                        .roundToPx(),
                    effective.y
                        .clampSafe(0.dp, currentContainer.height - WINDOW_MIN_VISIBLE)
                        .roundToPx(),
                )
            }
            .size(size)
            .zIndex(window.zOrder.toFloat())
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && window.closable) {
                    currentOnClose()
                    true
                } else {
                    false
                }
            }
            // Press ANYWHERE in the window brings it to the front - observed
            // on the Initial pass so child-consumed clicks still refocus.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) currentOnFocus()
                    }
                }
            },
        shape = RoundedCornerShape(t.cornerLarge),
        tonalElevation = t.dialogTonalElevation,
        // Overlay depth follows the AI companion panel precedent, not the
        // card tokens - a floating window needs real separation on every OS.
        shadowElevation = 12.dp,
        border = if (t.cardBorderWidth > 0.dp) {
            BorderStroke(t.cardBorderWidth, MaterialTheme.colorScheme.outlineVariant)
        } else {
            null
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalLayoutDirection provides appDirection) {
                Column(Modifier.fillMaxSize()) {
                    WindowTitleBar(
                        window = window,
                        focused = focused,
                        onDragStart = ::materializeAndFocus,
                        onDrag = { delta -> window.moveBy(delta, currentContainer) },
                        onMinimize = onMinimize,
                        onToggleDock = onToggleDock,
                        onClose = onClose,
                    )
                    HorizontalDivider()
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                        content()
                    }
                }
            }
            // Handles live OUTSIDE the direction override: their left/right
            // semantics are canvas-absolute, matching the geometry math.
            if (window.resizable) {
                ResizeHandles(
                    grip = if (interaction.supportsTouchGestures) 12.dp else 6.dp,
                    onDragStart = ::materializeAndFocus,
                    onResize = { edge, delta ->
                        window.resizeBy(
                            delta = delta,
                            left = edge.left,
                            top = edge.top,
                            right = edge.right,
                            bottom = edge.bottom,
                            container = currentContainer,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun WindowTitleBar(
    window: AppWindowState,
    focused: Boolean,
    onDragStart: () -> Unit,
    onDrag: (DpOffset) -> Unit,
    onMinimize: () -> Unit,
    onToggleDock: () -> Unit,
    onClose: () -> Unit,
) {
    val t = AppTheme.visualTokens
    val interaction = AppTheme.interaction
    val density = LocalDensity.current
    val titleBarHeight = maxOf(t.buttonHeightDense, interaction.minimumTargetSize)
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(titleBarHeight)
            .background(if (focused) colors.surfaceContainerHigh else colors.surfaceContainer)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                ) { change, dragAmount ->
                    change.consume()
                    val delta = with(density) { DpOffset(dragAmount.x.toDp(), dragAmount.y.toDp()) }
                    onDrag(delta)
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(
            text = window.title.asString(),
            style = AppTextStyle.ITEM_TITLE,
            color = if (focused) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = UIConst.paddingCompact),
        )
        // Undock / dock: the docked window is a dialog (scrim, blocks the app);
        // the undocked one is a tool the operator works beside. Leftmost of the
        // chrome actions - it is a MODE switch, not a lifecycle one.
        if (window.undockable) {
            AppIconButton(
                label = Strings[
                    if (window.isModal) StringKey.WINDOW_UNDOCK else StringKey.WINDOW_DOCK
                ],
                icon = if (window.isModal) AppDrawableRepo.openInNew else AppDrawableRepo.closeFullscreen,
                onClick = onToggleDock,
                size = AppIconSize.SMALL,
            )
        }
        if (window.canMinimize) {
            AppIconButton(
                label = Strings[StringKey.WINDOW_MINIMIZE],
                icon = AppDrawableRepo.minimize,
                onClick = onMinimize,
                size = AppIconSize.SMALL,
            )
        }
        if (window.closable) {
            AppIconButton(
                label = Strings[StringKey.COMMON_CLOSE],
                icon = AppDrawableRepo.close,
                onClick = onClose,
                size = AppIconSize.SMALL,
            )
        }
    }
}

/** The eight resize regions; booleans name which window edges the drag moves. */
private enum class ResizeEdge(
    val left: Boolean = false,
    val top: Boolean = false,
    val right: Boolean = false,
    val bottom: Boolean = false,
) {
    N(top = true),
    S(bottom = true),
    E(right = true),
    W(left = true),
    NE(top = true, right = true),
    NW(top = true, left = true),
    SE(bottom = true, right = true),
    SW(bottom = true, left = true),
}

/**
 * Thin strips inside each border plus larger corner pads, drawn ON TOP of the
 * chrome so the outermost [grip] of the window always resizes. Crosshair
 * hover icon on every handle (the grids' ColumnResizeHandle precedent -
 * per-direction arrows would need AWT, desktop-only).
 */
@Composable
private fun ResizeHandles(
    grip: Dp,
    onDragStart: () -> Unit,
    onResize: (ResizeEdge, DpOffset) -> Unit,
) {
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        @Composable
        fun handle(edge: ResizeEdge, alignment: Alignment, sizing: Modifier) {
            Box(
                sizing
                    .align(alignment)
                    .pointerHoverIcon(PointerIcon.Crosshair)
                    .pointerInput(edge) {
                        detectDragGestures(
                            onDragStart = { onDragStart() },
                        ) { change, dragAmount ->
                            change.consume()
                            val delta = with(density) {
                                DpOffset(dragAmount.x.toDp(), dragAmount.y.toDp())
                            }
                            onResize(edge, delta)
                        }
                    },
            )
        }

        val corner = grip * 2
        handle(ResizeEdge.N, Alignment.TopCenter, Modifier.fillMaxWidth().height(grip))
        handle(ResizeEdge.S, Alignment.BottomCenter, Modifier.fillMaxWidth().height(grip))
        handle(ResizeEdge.W, Alignment.CenterStart, Modifier.fillMaxHeight().width(grip))
        handle(ResizeEdge.E, Alignment.CenterEnd, Modifier.fillMaxHeight().width(grip))
        handle(ResizeEdge.NW, Alignment.TopStart, Modifier.size(corner))
        handle(ResizeEdge.NE, Alignment.TopEnd, Modifier.size(corner))
        handle(ResizeEdge.SW, Alignment.BottomStart, Modifier.size(corner))
        handle(ResizeEdge.SE, Alignment.BottomEnd, Modifier.size(corner))
    }
}
