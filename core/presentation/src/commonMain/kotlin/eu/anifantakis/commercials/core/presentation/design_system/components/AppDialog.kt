package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.DialogProperties
import eu.anifantakis.commercials.core.presentation.design_system.AppIcons
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import androidx.compose.ui.tooling.preview.Preview

/**
 * The design-system alert/confirm dialog.
 *
 * `usePlatformDefaultWidth = false` is what stops a dialog looking like a
 * phone dialog on a 27" monitor - which makes the COMPACT branch below
 * NON-OPTIONAL: without it, `dialogMinWidth` would overflow a phone.
 */
@Composable
fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String? = null,
    confirmEnabled: Boolean = true,
    /** In-flight confirm: spinner on the confirm button, both actions locked. */
    confirmBusy: Boolean = false,
    destructive: Boolean = false,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = AppTheme.visualTokens
    val compact = AppTheme.window.isCompact
    AlertDialog(
        onDismissRequest = { if (!confirmBusy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = modifier.then(
            if (compact) Modifier.fillMaxWidth().padding(UIConst.paddingRegular)
            else Modifier.widthIn(min = t.dialogMinWidth, max = t.dialogMaxWidth),
        ),
        shape = RoundedCornerShape(t.cornerExtraLarge),
        tonalElevation = t.dialogTonalElevation,
        icon = icon?.let { { AppIcon(it, contentDescription = null, size = AppIconSize.LARGE) } },
        title = { AppText(title, AppTextStyle.DIALOG_TITLE) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
                content = content,
            )
        },
        confirmButton = {
            AppButton(
                text = confirmText,
                onClick = onConfirm,
                variant = if (destructive) AppButtonVariant.DESTRUCTIVE else AppButtonVariant.PRIMARY,
                enabled = confirmEnabled,
                busy = confirmBusy,
            )
        },
        dismissButton = dismissText?.let {
            {
                AppButton(
                    text = it,
                    onClick = onDismiss,
                    variant = AppButtonVariant.TEXT,
                    enabled = !confirmBusy,
                )
            }
        },
    )
}

@Preview
@Composable
private fun AppDialogPreview() = AppPreview {
    AppDialog(
        title = "Send tomorrow's schedule?",
        onDismiss = {},
        confirmText = "Send",
        onConfirm = {},
        dismissText = "Cancel",
        icon = AppIcons.email,
    ) {
        AppText("Crete TV - Wednesday 15 July", AppTextStyle.BODY_STRONG)
        AppText("14 breaks, 38 spots. The traffic desk gets one email per station.", AppTextStyle.BODY)
    }
}

@Preview
@Composable
private fun AppDialogDestructivePreview() = AppPreview {
    AppDialog(
        title = "Delete the 21:00 break?",
        onDismiss = {},
        confirmText = "Delete",
        onConfirm = {},
        dismissText = "Keep it",
        destructive = true,
        icon = AppIcons.delete,
    ) {
        AppText("The break on Radio 984 holds 4 spots from 2 contracts.", AppTextStyle.BODY)
        AppText("Deleting it releases those spots back to the pool.", AppTextStyle.NOTE)
    }
}

// In-flight confirm: the whole reason the busy flag exists - the confirm button
// spins and BOTH actions lock, so a second click cannot double-send.
@Preview
@Composable
private fun AppDialogBusyPreview() = AppPreview {
    AppDialog(
        title = "Sending to Radio 984",
        onDismiss = {},
        confirmText = "Send",
        onConfirm = {},
        dismissText = "Cancel",
        confirmBusy = true,
    ) {
        AppText("Uploading 38 spots to the station traffic desk.", AppTextStyle.BODY)
    }
}

// Nothing to confirm yet: the primary action is disabled but the dialog is live.
@Preview
@Composable
private fun AppDialogConfirmDisabledPreview() = AppPreview {
    AppDialog(
        title = "Move spot to another break",
        onDismiss = {},
        confirmText = "Move",
        onConfirm = {},
        dismissText = "Cancel",
        confirmEnabled = false,
    ) {
        AppText("Pick a target break on Crete TV first.", AppTextStyle.BODY)
    }
}
