package eu.anifantakis.commercials.update

/**
 * Which OS this desktop binary is running on, as the UPDATER needs it: the
 * installer format to download and the way to hand it to the OS.
 *
 * This file is the desktop SHELL's one sanctioned `os.name` read (fitness
 * rule: ArchitectureTest "os name is read only at sanctioned detection
 * points"). The design system's door (UiPlatform.jvm.kt) is deliberately
 * internal and serves LOOK decisions - installer format is a distribution
 * concern, so the shell derives its own capability here instead of widening
 * that API.
 */
enum class HostOs(val installerKey: String) {
    MAC("dmg"),
    WINDOWS("msi"),
    LINUX("deb");

    companion object {
        fun detect(osName: String = System.getProperty("os.name") ?: ""): HostOs =
            with(osName.lowercase()) {
                when {
                    contains("mac") || contains("darwin") -> MAC
                    contains("win") -> WINDOWS
                    else -> LINUX
                }
            }
    }
}
