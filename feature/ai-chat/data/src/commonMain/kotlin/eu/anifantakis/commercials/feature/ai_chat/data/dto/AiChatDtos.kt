package eu.anifantakis.commercials.feature.ai_chat.data.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class AiChatTurnDto(val role: String, val text: String)

/** [provider]/[model] = the user's dropdown picks; the server validates both. */
@Serializable
internal data class AiChatRequestDto(
    val messages: List<AiChatTurnDto>,
    val provider: String? = null,
    val model: String? = null,
)

@Serializable
internal data class AiToolStepDto(val tool: String, val isError: Boolean = false)

@Serializable
internal data class AiChatResponseDto(val text: String, val steps: List<AiToolStepDto> = emptyList())
