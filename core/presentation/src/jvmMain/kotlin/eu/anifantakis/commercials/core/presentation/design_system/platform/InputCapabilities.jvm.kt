package eu.anifantakis.commercials.core.presentation.design_system.platform

// Desktop JVM: assume mouse/trackpad. A touchscreen laptop still resolves to
// COMPACT density under AUTO ("a touchscreen laptop is a laptop"), so not
// probing for touch here loses nothing in v1.
internal actual fun startupInputCapabilities(): InputCapabilities = InputCapabilities(
    hasCoarsePointer = false,
    hasFinePointer = true,
    canHover = true,
)
