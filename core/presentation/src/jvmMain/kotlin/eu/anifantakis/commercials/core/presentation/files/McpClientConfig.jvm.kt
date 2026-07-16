package eu.anifantakis.commercials.core.presentation.files

import java.io.File

private val osName: String = System.getProperty("os.name").orEmpty().lowercase()
private val isMac: Boolean = "mac" in osName || "darwin" in osName
private val isWindows: Boolean = "win" in osName

/**
 * The per-OS location Claude Desktop reads its `mcpServers` config from:
 * macOS `~/Library/Application Support/Claude/…`, Windows `%APPDATA%\Claude\…`,
 * Linux `~/.config/Claude/…`.
 */
actual val mcpClientConfigPath: String? = run {
    val home = System.getProperty("user.home").orEmpty()
    when {
        isMac -> "$home/Library/Application Support/Claude/claude_desktop_config.json"
        isWindows -> {
            val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: "$home\\AppData\\Roaming"
            "$appData\\Claude\\claude_desktop_config.json"
        }
        else -> "$home/.config/Claude/claude_desktop_config.json"
    }
}

/**
 * Reveals [path] in the native file manager. macOS/Windows can SELECT the exact
 * file (`open -R`, `explorer /select,`); on Linux (and when the file is absent)
 * we open the containing folder. Fire-and-forget - a failure to launch the file
 * manager is swallowed rather than surfaced as an app error.
 */
actual fun revealInFileManager(path: String) {
    val file = File(path)
    val folder = file.parentFile ?: file
    runCatching {
        val cmd = when {
            isMac && file.exists() -> listOf("open", "-R", file.absolutePath)
            isMac -> listOf("open", folder.absolutePath)
            isWindows && file.exists() -> listOf("explorer", "/select,${file.absolutePath}")
            isWindows -> listOf("explorer", folder.absolutePath)
            else -> listOf("xdg-open", folder.absolutePath)
        }
        ProcessBuilder(cmd).start()
    }
}
