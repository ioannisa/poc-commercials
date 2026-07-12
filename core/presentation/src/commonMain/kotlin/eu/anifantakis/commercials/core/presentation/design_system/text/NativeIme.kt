package eu.anifantakis.commercials.core.presentation.design_system.text

import androidx.compose.ui.text.input.PlatformImeOptions

/**
 * Per-platform IME extras for the app's FORM fields. iOS opts into the
 * UIView-backed native text input: native caret placement/movement, native
 * selection gestures + handles, and the system context menu with Autofill /
 * Look Up / Translate. Every other target returns null (their default input
 * already IS the platform one).
 *
 * Injected in exactly ONE place - AppWireframeField - and deliberately NOT
 * in the grids' EditableCell: a UIView per cell inside a virtualized
 * LazyColumn is the worst possible host for an experimental interop view.
 */
expect fun nativePlatformImeOptions(): PlatformImeOptions?
