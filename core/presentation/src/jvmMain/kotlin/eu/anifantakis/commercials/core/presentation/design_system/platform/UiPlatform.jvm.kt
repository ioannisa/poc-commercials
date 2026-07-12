package eu.anifantakis.commercials.core.presentation.design_system.platform

/**
 * The ONE place in the codebase that reads `os.name` (fitness-rule enforced):
 * a single jvm target serves three desktop OSes, so the split has to happen
 * at runtime. This is an OS-string parse, not a look decision - looks are
 * selected in the PlatformVisualTokens factory.
 */
internal actual fun detectUiPlatform(): UiPlatform {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        "mac" in os || "darwin" in os -> UiPlatform.MACOS
        "win" in os -> UiPlatform.WINDOWS
        else -> UiPlatform.LINUX
    }
}
