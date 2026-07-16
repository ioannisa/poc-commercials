package eu.anifantakis.commercials.core.presentation.files

// A browser has no local filesystem or file manager - the token is copied by
// hand into whatever MCP client the user runs, so there is nothing to reveal.
actual val mcpClientConfigPath: String? = null

actual fun revealInFileManager(path: String) {}
