package eu.anifantakis.commercials.feature.ai_chat.domain

/**
 * SILENT chat preferences - no settings screen, just remembered choices:
 * the provider/model the user last picked in the chat dropdowns (restored
 * next time the panel opens, validated against the server's catalog first)
 * and the companion panel's width in dp (the user can drag-resize it).
 * Blank provider/model = nothing stored yet, the catalog default applies.
 */
interface AiChatPreferences {
    var provider: String
    var model: String
    var panelWidthDp: Int

    /** DESKTOP: the companion lives in its own OS window (detached) vs the in-app overlay. */
    var detached: Boolean

    companion object {
        const val DEFAULT_PANEL_WIDTH_DP = 400
    }
}
