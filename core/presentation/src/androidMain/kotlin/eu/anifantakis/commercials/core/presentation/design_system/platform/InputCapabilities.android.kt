package eu.anifantakis.commercials.core.presentation.design_system.platform

// Android: touch-first. Mice/DeX exist but are the minority; under AUTO a
// fine pointer would flip density to COMPACT, and mis-detecting one on a
// phone is the worse failure. v1 keeps the conservative constant - the user
// override in Preferences covers desktop-docked setups.
internal actual fun startupInputCapabilities(): InputCapabilities = InputCapabilities(
    hasCoarsePointer = true,
    hasFinePointer = false,
    canHover = false,
)
