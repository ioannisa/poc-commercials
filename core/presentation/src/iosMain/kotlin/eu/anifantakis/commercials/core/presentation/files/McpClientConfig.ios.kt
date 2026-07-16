package eu.anifantakis.commercials.core.presentation.files

// No desktop MCP client / file manager on iOS (MCP tokens are a desktop/web
// concern), so there is nothing to reveal.
actual val mcpClientConfigPath: String? = null

actual fun revealInFileManager(path: String) {}
