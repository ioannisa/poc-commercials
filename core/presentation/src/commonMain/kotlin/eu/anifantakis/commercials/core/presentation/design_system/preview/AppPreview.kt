package eu.anifantakis.commercials.core.presentation.design_system.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.anifantakis.commercials.core.presentation.design_system.CommercialsTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.string_resources.LocalizationProvider

/**
 * The one wrapper every `@Preview` in this codebase renders inside.
 *
 * A preview that draws a component BARE is not previewing the component - it is
 * previewing a component in a composition it will never actually run in. Two
 * things have to be ambient or the picture lies:
 *
 *  - [CommercialsTheme], which is where the colours, the type scale, the platform
 *    visual tokens and the interaction metrics come from. Without it Material's
 *    defaults quietly stand in, and the preview shows a widget nobody ships.
 *  - [LocalizationProvider], because every operator-facing string resolves through
 *    `Strings[…]` / `UiText`. Without it the language CompositionLocal is absent
 *    and the preview is asserting against a fallback the app never uses.
 *
 * [dark] renders the same content in the dark palette. It is a parameter rather
 * than a second wrapper so a component's light and dark previews cannot drift.
 *
 * Note this is a PREVIEW-ONLY wrapper: the app root does this wiring itself, and
 * nothing in production should call it.
 */
@Composable
fun AppPreview(
    dark: Boolean = false,
    padded: Boolean = true,
    content: @Composable () -> Unit,
) {
    CommercialsTheme(darkTheme = dark) {
        LocalizationProvider {
            // Surface, not a bare Box: components inherit their content colour from
            // it, and previewing them on a transparent background is how you ship
            // white-on-white text that only shows up in the dark theme.
            Surface {
                Box(if (padded) Modifier.padding(UIConst.paddingRegular) else Modifier) {
                    content()
                }
            }
        }
    }
}
