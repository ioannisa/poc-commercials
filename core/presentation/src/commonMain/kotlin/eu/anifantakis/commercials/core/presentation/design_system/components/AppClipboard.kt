package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

/**
 * A copy-to-clipboard action for the current composition: `val copy = rememberClipboardCopy(); copy("…")`.
 *
 * compose-multiplatform 1.12 offers no cross-platform way to build a text
 * `ClipEntry` for the newer `LocalClipboard` (`ClipEntry.withPlainText` is
 * iOS-only), so this uses the still-functional `LocalClipboardManager`. The
 * deprecation is isolated to this one helper - swap the body here if a common
 * ClipEntry text factory ever lands, and no call site changes.
 */
@Suppress("DEPRECATION")
@Composable
fun rememberClipboardCopy(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    return remember(clipboard) { { text -> clipboard.setText(AnnotatedString(text)) } }
}
