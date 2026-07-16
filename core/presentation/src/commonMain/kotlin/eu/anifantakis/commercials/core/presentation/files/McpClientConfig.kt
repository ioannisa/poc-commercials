package eu.anifantakis.commercials.core.presentation.files

/**
 * The conventional Claude Desktop MCP config path on this OS, or null where
 * there is no local filesystem / file manager (web, mobile). When non-null the
 * UI can offer a "reveal in file manager" action, so the user is taken straight
 * to the file to paste a freshly minted token.
 *
 * This is only the CONVENTIONAL location - the app cannot be sure Claude Desktop
 * is installed or that the file exists yet. [revealInFileManager] handles a
 * missing file by opening its folder instead.
 */
expect val mcpClientConfigPath: String?

/**
 * Opens the OS file manager (Finder / Explorer) at [path], selecting the file
 * if it exists, otherwise opening its containing folder. No-op on platforms
 * without a local file manager.
 */
expect fun revealInFileManager(path: String)
