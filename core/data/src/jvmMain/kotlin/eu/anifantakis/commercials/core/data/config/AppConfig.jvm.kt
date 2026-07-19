package eu.anifantakis.commercials.core.data.config

import java.io.File

/**
 * Read `config.properties` - first match wins:
 *
 * 1. An explicit path: `-Dcommercials.config=...` or `COMMERCIALS_CONFIG`.
 * 2. The working directory - dev runs launch from the repo root, and Windows
 *    installs historically keep the file next to the launcher.
 * 3. The per-user config dir ([perUserConfigDir]) - the location that
 *    SURVIVES app upgrades: a DMG replace or MSI upgrade only touches the
 *    installation directory, never the user profile.
 *
 * The working dir is deliberately searched BEFORE the per-user dir so a dev
 * run can never be shadowed by a stray per-user file. Upgrade-survival comes
 * from the MIRROR instead: every successful working-dir load is copied
 * (best effort) into the per-user dir, so the install-dir copy an MSI major
 * upgrade may sweep away is self-backed-up - after the upgrade, resolution
 * falls through to the per-user copy and the app keeps its server URL.
 */
internal actual suspend fun loadAppConfig(): AppConfig {
    val explicit = System.getProperty("commercials.config") ?: System.getenv("COMMERCIALS_CONFIG")
    val workingFile = File("config.properties")
    val perUserFile = File(perUserConfigDir(), "config.properties")

    val file = when {
        explicit != null -> File(explicit)
        workingFile.exists() -> workingFile.also { mirrorToPerUser(it, perUserFile) }
        else -> perUserFile
    }
    require(file.exists()) {
        "config.properties not found. Searched: " + (
            explicit?.let { File(it).absolutePath }
                ?: "${workingFile.absolutePath}, ${perUserFile.absolutePath}"
            )
    }
    return parseProperties(file.readText()).toAppConfig()
}

/**
 * The platform-appropriate per-user config directory:
 * - Windows: `%APPDATA%\CommercialsManager`
 * - macOS:   `~/Library/Application Support/CommercialsManager`
 * - Linux:   `$XDG_CONFIG_HOME/CommercialsManager` (or `~/.config/...`)
 *
 * Probed from the ENVIRONMENT and the FILESYSTEM, never `os.name` - the
 * client codebase allows that read only at its sanctioned detection points
 * (ArchitectureTest), and config-dir discovery genuinely doesn't need it:
 * `APPDATA` exists only on Windows, `~/Library/Application Support` only on
 * macOS. Parameters exist for tests; production callers use the defaults.
 */
internal fun perUserConfigDir(
    home: File = File(System.getProperty("user.home")),
    appData: String? = System.getenv("APPDATA"),
    xdgConfigHome: String? = System.getenv("XDG_CONFIG_HOME"),
): File {
    val base = when {
        !appData.isNullOrBlank() -> File(appData)
        File(home, "Library/Application Support").isDirectory -> File(home, "Library/Application Support")
        else -> xdgConfigHome?.takeIf { it.isNotBlank() }?.let(::File) ?: File(home, ".config")
    }
    return File(base, "CommercialsManager")
}

/**
 * Best-effort write-through backup of a working-dir config into the per-user
 * dir (only when the content differs - no churn on every launch). Failures
 * are swallowed: a read-only profile must never break app startup.
 */
private fun mirrorToPerUser(source: File, target: File) {
    runCatching {
        val text = source.readText()
        if (!target.exists() || target.readText() != text) {
            target.parentFile?.mkdirs()
            target.writeText(text)
        }
    }
}
