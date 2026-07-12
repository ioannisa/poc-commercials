package eu.anifantakis.commercials.core.presentation.design_system.text

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.input.PlatformImeOptions
import androidx.compose.ui.text.input.usingNativeTextInput

@OptIn(ExperimentalComposeUiApi::class)
actual fun nativePlatformImeOptions(): PlatformImeOptions? =
    PlatformImeOptions { usingNativeTextInput(true) }
