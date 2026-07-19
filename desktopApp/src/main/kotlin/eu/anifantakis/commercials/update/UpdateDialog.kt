package eu.anifantakis.commercials.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.CommercialsTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButtonVariant
import eu.anifantakis.commercials.core.presentation.design_system.components.AppPopup
import eu.anifantakis.commercials.core.presentation.design_system.components.AppProgressBar
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.string_resources.LocalizationProvider
import eu.anifantakis.commercials.core.presentation.string_resources.StringKey
import eu.anifantakis.commercials.core.presentation.string_resources.Strings
import eu.anifantakis.commercials.core.presentation.string_resources.withArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The dialog's lifecycle: offer → stream the installer → hand off (or fail). */
private sealed interface Phase {
    data object Prompt : Phase
    data class Downloading(val progress: Float?) : Phase
    data class Failed(val reason: String) : Phase
}

/**
 * The update dialog as mounted by the desktop SHELL (main.kt) - OUTSIDE
 * App(), which owns its own theme/localization providers. So this wrapper
 * provides them itself: [LocalizationProvider] resolves the user's language
 * (LocalizationManager is process-global, seeded by App() at startup), and
 * [CommercialsTheme] with defaults follows the OS light/dark - the shell has
 * no access to the in-app theme preference, an acceptable divergence for one
 * transient dialog.
 */
@Composable
fun UpdateOverlay(
    update: UpdateDecision.Available,
    currentVersion: String,
    onDismiss: () -> Unit,
    onExitApp: () -> Unit,
) = CommercialsTheme {
    LocalizationProvider {
        UpdateDialog(update, currentVersion, onDismiss, onExitApp)
    }
}

@Composable
private fun UpdateDialog(
    update: UpdateDecision.Available,
    currentVersion: String,
    onDismiss: () -> Unit,
    onExitApp: () -> Unit,
) {
    var phase by remember { mutableStateOf<Phase>(Phase.Prompt) }

    // The download runs off the UI thread; onProgress mutates snapshot state,
    // which is safe from any thread. On success the installer is handed to
    // the OS and the app EXITS - on Windows especially, MSI cannot replace
    // files the running app still holds open.
    val downloading = phase is Phase.Downloading
    LaunchedEffect(downloading) {
        if (!downloading) return@LaunchedEffect
        try {
            val file = withContext(Dispatchers.IO) {
                UpdateDownloader.download(update.installerUrl) { p ->
                    phase = Phase.Downloading(p)
                }
            }
            withContext(Dispatchers.IO) { UpdateDownloader.launchInstaller(file) }
            onExitApp()
        } catch (e: Exception) {
            phase = Phase.Failed(e.message ?: e::class.simpleName ?: "?")
        }
    }

    AppPopup(
        // A mandatory update (or an in-flight download) cannot be waved away
        // with Esc/outside-click; the buttons are the only exits.
        onDismissRequest = { if (!update.mandatory && !downloading) onDismiss() },
    ) {
        Column(
            modifier = Modifier.widthIn(max = 440.dp).padding(UIConst.paddingRegular),
            verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
        ) {
            AppText(
                text = Strings[if (update.mandatory) StringKey.UPDATE_REQUIRED_TITLE else StringKey.UPDATE_AVAILABLE_TITLE],
                style = AppTextStyle.SECTION_TITLE,
            )
            AppText(
                text = Strings[if (update.mandatory) StringKey.UPDATE_REQUIRED_MESSAGE else StringKey.UPDATE_AVAILABLE_MESSAGE]
                    .withArgs(listOf(update.latest, currentVersion)),
                style = AppTextStyle.BODY,
            )

            when (val p = phase) {
                is Phase.Downloading -> AppProgressBar(
                    fraction = p.progress,
                    caption = Strings[StringKey.UPDATE_DOWNLOADING],
                )

                is Phase.Failed -> AppText(
                    text = Strings[StringKey.UPDATE_FAILED].withArgs(listOf(p.reason)),
                    style = AppTextStyle.NOTE,
                )

                Phase.Prompt -> Unit
            }

            if (!downloading) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    if (update.mandatory) {
                        AppButton(
                            text = Strings[StringKey.UPDATE_EXIT],
                            onClick = onExitApp,
                            variant = AppButtonVariant.TEXT,
                        )
                    } else {
                        AppButton(
                            text = Strings[StringKey.UPDATE_LATER],
                            onClick = onDismiss,
                            variant = AppButtonVariant.TEXT,
                        )
                    }
                    Spacer(Modifier.padding(horizontal = UIConst.paddingSmall / 2))
                    AppButton(
                        text = Strings[StringKey.UPDATE_INSTALL],
                        onClick = { phase = Phase.Downloading(null) },
                        variant = AppButtonVariant.PRIMARY,
                    )
                }
            }
        }
    }
}
