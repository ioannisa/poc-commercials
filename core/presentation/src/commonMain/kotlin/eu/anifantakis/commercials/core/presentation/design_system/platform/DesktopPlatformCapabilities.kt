package eu.anifantakis.commercials.core.presentation.design_system.platform

/**
 * Neutral shortcut modifier - deliberately NOT a Compose Desktop type
 * (KeyModifier/KeyShortcut are jvm-only and may not leak into commonMain).
 * The desktop app translates this into its real KeyShortcut.
 */
enum class PrimaryShortcutModifier { META, CONTROL }

/**
 * Desktop-idiom facts the entry app needs, derived from the ONE OS-detection
 * point (never a second `os.name` read - the fitness rule enforces that).
 * Plain data: unit-testable without any UI dependency.
 */
data class DesktopPlatformCapabilities(
    val primaryShortcutModifier: PrimaryShortcutModifier,
    val usesScreenMenuBar: Boolean,
)

/** Non-null only on desktop JVM. */
expect fun desktopPlatformCapabilities(): DesktopPlatformCapabilities?
