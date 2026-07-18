package eu.anifantakis.commercials.feature.ai_chat.data.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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

/** A mutation the model prepared - rendered as a confirmation card. */
@Serializable
internal data class AiProposalDto(
    val tool: String,
    val arguments: JsonObject,
    val preview: String = "",
)

@Serializable
internal data class AiChatResponseDto(
    val text: String,
    val steps: List<AiToolStepDto> = emptyList(),
    val proposals: List<AiProposalDto> = emptyList(),
)

@Serializable
internal data class AiExecuteRequestDto(val tool: String, val arguments: JsonObject)

@Serializable
internal data class AiExecuteResponseDto(val text: String = "", val isError: Boolean = false)
