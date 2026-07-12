package eu.anifantakis.commercials.core.presentation.design_system.platform

// iOS/iPadOS: touch-first. An iPad trackpad is real but optional and
// hot-pluggable; v1's startup snapshot stays conservative (TOUCH_FRIENDLY
// under AUTO) and the Preferences override covers keyboard-and-trackpad
// iPad workstations. Keyboard FOCUS visibility is handled separately via
// InputModeManager, never disabled by platform.
internal actual fun startupInputCapabilities(): InputCapabilities = InputCapabilities(
    hasCoarsePointer = true,
    hasFinePointer = false,
    canHover = false,
)
