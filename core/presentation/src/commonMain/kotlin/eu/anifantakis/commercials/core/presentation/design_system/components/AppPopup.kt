package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst

/**
 * Generic overlay for CUSTOM dialog content (skill naming: AppPopup - the
 * name AppModal is reserved for the server-driven CTA modal). The base for
 * the email-preview and spot-finder style overlays: caller owns the content
 * and its sizing; this owns the scrim, shape, tonal depth, and the COMPACT
 * full-bleed rule.
 */
@Composable
fun AppPopup(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val t = AppTheme.visualTokens
    val compact = AppTheme.window.isCompact
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = modifier.then(
                if (compact) Modifier.fillMaxWidth().padding(UIConst.paddingSmall) else Modifier,
            ),
            shape = RoundedCornerShape(t.cornerLarge),
            tonalElevation = t.dialogTonalElevation,
            content = content,
        )
    }
}
