package eu.anifantakis.commercials.core.presentation.design_system.components.window

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.ViewModelStoreProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
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
 * The floating-window LAYER: renders every window of [state] over whatever
 * sits underneath it in the parent Box, plus the modal scrim and the
 * minimized-windows taskbar strip. Purely slot-based - it knows nothing
 * about navigation; the app layer decides what goes INTO a window.
 *
 * Layering inside: windows stack by z-order (press brings to front); a modal
 * window puts a click-swallowing scrim (AppLoadingIndicator idiom) just
 * beneath itself, blocking both the app and any lower windows.
 *
 * Coordinates: the positioning layer is PINNED TO LTR so window geometry is
 * canvas-absolute under RTL too (the localization guide's "LTR pinning"
 * rule); each window restores the ambient direction for its own chrome and
 * content, so the INSIDE of a window mirrors normally.
 *
 * ViewModel scoping (the point of all this): each window's content runs
 * under a [rememberViewModelStoreOwner] KEYED by the window id on the host's
 * [ViewModelStoreProvider] - so `koinViewModel()` inside a window resolves
 * to that window's own scope. Minimize keeps the ViewModels; only
 * [AppWindowHostState.close] destroys them.
 */
@Composable
fun AppWindowHost(
    state: AppWindowHostState,
    modifier: Modifier = Modifier,
) {
    if (state.windows.isEmpty()) return

    val saveableHolder = rememberSaveableStateHolder()
    ForgetClosedWindows(state, saveableHolder)

    Box(modifier.fillMaxSize()) {
        val appDirection = LocalLayoutDirection.current
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val container = DpSize(maxWidth, maxHeight)
                val modalZ = state.windows
                    .filter { it.isModal && !it.isMinimized }
                    .maxOfOrNull { it.zOrder }
                if (modalZ != null) {
                    ModalScrim(Modifier.zIndex(modalZ - 0.5f))
                }
                for (window in state.windows) {
                    key(window.id) {
                        if (!window.isMinimized) {
                            AppFloatingWindow(
                                window = window,
                                container = container,
                                focused = window.zOrder == state.topZ,
                                appDirection = appDirection,
                                onFocus = { state.focus(window.id) },
                                onMinimize = { state.minimize(window.id) },
                                onToggleDock = { state.setModal(window.id, !window.isModal) },
                                onClose = { state.close(window.id) },
                            ) {
                                WindowScopedContent(window, state.viewModelStoreProvider, saveableHolder)
                            }
                        }
                    }
                }
            }
        }
        // Drawn AFTER (= above) the windows layer so the strip stays usable
        // even under a modal scrim - a restored window simply lands beneath
        // the modal, exactly like an OS taskbar.
        MinimizedTaskbar(state, Modifier.align(Alignment.BottomStart))
    }
}

/**
 * The scoping seam. The keyed owner is why minimize/reorder cannot kill a
 * window's ViewModels: the store lives in the provider under the window id,
 * NOT in this composition - only clearKey (from close) destroys it.
 * SaveableStateProvider gives `rememberSaveable` inside the window the same
 * survive-minimize semantics.
 */
@Composable
private fun WindowScopedContent(
    window: AppWindowState,
    provider: ViewModelStoreProvider,
    saveableHolder: SaveableStateHolder,
) {
    val owner = rememberViewModelStoreOwner(
        key = window.id,
        provider = provider,
        // This codebase has zero SavedStateHandle usage (args travel via
        // parametersOf) - explicit null keeps desktop/web deterministic
        // instead of depending on a platform LocalSavedStateRegistryOwner.
        savedStateRegistryOwner = null,
    )
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        saveableHolder.SaveableStateProvider(window.id) {
            window.content()
        }
    }
}

/**
 * Drops the SaveableStateHolder records of windows that were CLOSED (not
 * minimized), so a future window reusing the id starts clean - matching the
 * ViewModel clearKey in [AppWindowHostState.close].
 */
@Composable
private fun ForgetClosedWindows(state: AppWindowHostState, holder: SaveableStateHolder) {
    var knownIds by remember { mutableStateOf(emptySet<String>()) }
    val currentIds = state.windows.map { it.id }.toSet()
    val closed = knownIds - currentIds
    SideEffect {
        closed.forEach(holder::removeState)
        if (knownIds != currentIds) knownIds = currentIds
    }
}

/** AppLoadingIndicator's scrim idiom: dim + swallow every tap underneath. */
@Composable
private fun ModalScrim(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.25f))
            .pointerInput(Unit) { detectTapGestures { } },
    )
}

@Composable
private fun MinimizedTaskbar(state: AppWindowHostState, modifier: Modifier = Modifier) {
    val minimized = state.windows.filter { it.isMinimized }
    if (minimized.isEmpty()) return
    val t = AppTheme.visualTokens
    Row(
        modifier = modifier.padding(UIConst.paddingSmall),
        horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
    ) {
        for (window in minimized) {
            key(window.id) {
                Surface(
                    shape = RoundedCornerShape(t.cornerMedium),
                    tonalElevation = t.dialogTonalElevation,
                    shadowElevation = 8.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .clickable { state.restore(window.id) }
                            .padding(start = UIConst.paddingCompact, end = UIConst.paddingExtraSmall),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(UIConst.paddingExtraSmall),
                    ) {
                        AppText(
                            text = window.title.asString(),
                            style = AppTextStyle.NOTE,
                            maxLines = 1,
                        )
                        if (window.closable) {
                            AppIconButton(
                                label = Strings[StringKey.COMMON_CLOSE],
                                icon = AppDrawableRepo.close,
                                onClick = { state.close(window.id) },
                                size = AppIconSize.SMALL,
                            )
                        }
                    }
                }
            }
        }
    }
}
