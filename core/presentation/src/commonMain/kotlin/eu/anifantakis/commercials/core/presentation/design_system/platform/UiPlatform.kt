package eu.anifantakis.commercials.core.presentation.design_system.platform

/**
 * The OS look we adapt to. INTERNAL on purpose: the only thing allowed to
 * branch on it is the [eu.anifantakis.commercials.core.presentation.design_system.PlatformVisualTokens]
 * factory (enforced by ArchitectureTest). Feature screens and ViewModels
 * never see it — they read `AppTheme.visualTokens` / `AppTheme.interaction`.
 *
 * Platform is NOT input modality: a Windows machine can have a touchscreen,
 * a web page can run on a phone, an iPad can have a trackpad. Input is
 * carried by [InputCapabilities] (startup snapshot) and, per gesture, by the
 * actual `PointerType` of the event.
 *
 * There is no macosArm64 native target — MACOS means the Compose Desktop
 * JVM app running on macOS, which is why the JVM actual resolves the OS at
 * runtime while every other target is a constant.
 */
internal enum class UiPlatform {
    ANDROID,
    IOS,
    MACOS,
    WINDOWS,
    LINUX,
    WEB,
}

/** Detected once per process. The single OS-detection door for UI purposes. */
internal expect fun detectUiPlatform(): UiPlatform
