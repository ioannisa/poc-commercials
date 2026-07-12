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
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst

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
