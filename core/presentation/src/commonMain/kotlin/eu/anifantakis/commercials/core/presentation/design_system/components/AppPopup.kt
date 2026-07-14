package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import androidx.compose.ui.tooling.preview.Preview

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

// The email-preview overlay: the caller owns the content AND its width - this
// component only supplies the scrim, the shape and the tonal depth.
@Preview
@Composable
private fun AppPopupPreview() = AppPreview {
    AppPopup(onDismissRequest = {}) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp).padding(UIConst.paddingRegular),
            verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
        ) {
            AppText("Email preview", AppTextStyle.SECTION_TITLE)
            AppText("To: traffic@crete-tv.example", AppTextStyle.NOTE)
            AppText(
                "Schedule for Wednesday 15 July: 14 breaks, 38 spots, 4 customers. " +
                    "The 21:00 break is at 174s of its 180s ceiling.",
                AppTextStyle.BODY,
            )
            Row {
                AppButton(text = "Cancel", onClick = {}, variant = AppButtonVariant.TEXT)
                Spacer(Modifier.padding(UIConst.paddingExtraSmall))
                AppButton(text = "Send", onClick = {})
            }
        }
    }
}

// A DENSE overlay (the spot-finder table): the popup must not cap or pad it -
// if it silently constrained content, this is where it would show.
@Preview
@Composable
private fun AppPopupDensePreview() = AppPreview {
    AppPopup(onDismissRequest = {}) {
        Column(
            modifier = Modifier.widthIn(max = 520.dp).padding(UIConst.paddingRegular),
            verticalArrangement = Arrangement.spacedBy(UIConst.paddingExtraSmall),
        ) {
            AppText("Find a spot", AppTextStyle.SECTION_TITLE)
            AppText("CUSTOMER            STATION      BREAK    LENGTH", AppTextStyle.TABLE_HEADER)
            AppText("Aegean Foods        Crete TV     21:00    30s", AppTextStyle.TABLE_CELL)
            AppText("Minoan Travel       Crete TV     21:00    20s", AppTextStyle.TABLE_CELL_STRONG)
            AppText("Heraklion Motors    Radio 984    08:30    15s", AppTextStyle.TABLE_CELL)
        }
    }
}
