package eu.anifantakis.commercials.core.presentation.design_system.platform

// Derived from detectUiPlatform() - the single OS-detection point - never
// from a second os.name read.
actual fun desktopPlatformCapabilities(): DesktopPlatformCapabilities? =
    when (detectUiPlatform()) {
        UiPlatform.MACOS -> DesktopPlatformCapabilities(
            primaryShortcutModifier = PrimaryShortcutModifier.META,
            usesScreenMenuBar = true,
        )
        UiPlatform.WINDOWS, UiPlatform.LINUX -> DesktopPlatformCapabilities(
            primaryShortcutModifier = PrimaryShortcutModifier.CONTROL,
            usesScreenMenuBar = false,
        )
        else -> null   // jvm actual, but be honest about the enum domain
    }
