package eu.anifantakis.commercials.feature.auth.data.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class LoginRequestDto(val username: String, val password: String)

@Serializable
internal data class StationAccessDto(
    val id: String,
    val name: String,
    val role: String,
    val clientCode: String? = null,
)

/** One AI-chat provider option from the server's catalog (models: first = default). */
@Serializable
internal data class AiProviderOptionDto(
    val id: String,
    val models: List<String> = emptyList(),
)

@Serializable
internal data class LoginResponseDto(
    val token: String,
    val displayName: String,
    val isAdmin: Boolean = false,
    val swaggerEnabled: Boolean = false,
    /** The AI assistant's provider catalog, default first; empty = feature off. */
    val aiChat: List<AiProviderOptionDto> = emptyList(),
    val mustChangePassword: Boolean = false,
    val stations: List<StationAccessDto> = emptyList(),
)

@Serializable
internal data class ChangePasswordDto(val currentPassword: String, val newPassword: String)

@Serializable
internal data class ForgotPasswordDto(val username: String)

@Serializable
internal data class ResetPasswordDto(val username: String, val code: String, val newPassword: String)

/** status: "ok" | "invalid_code" | "locked" | "expired"; retryAfterSeconds set on a lock. */
@Serializable
internal data class ResetResultDto(val status: String, val retryAfterSeconds: Long? = null)

@Serializable
internal data class ApiTokenDto(val id: Long, val workstationName: String, val createdAt: String, val lastUsedAt: String? = null)

@Serializable
internal data class CreateApiTokenRequestDto(val workstation: String, val confirmTakeover: Boolean = false)

@Serializable
internal data class CreateApiTokenResponseDto(val token: String)

@Serializable
internal data class WorkstationAvailabilityDto(val status: String)

@Serializable
internal data class OAuthGrantDto(
    val id: Long,
    val clientName: String,
    val createdAt: String,
    val lastUsedAt: String? = null,
    val refreshExpiresAt: String,
    val connectedAccount: String? = null,
    val userApproved: Boolean = true,
    val adminApproved: Boolean = true,
)

@Serializable
internal data class AiConfirmationDto(val enabled: Boolean, val hasEmail: Boolean)

@Serializable
internal data class SetAiConfirmationDto(val enabled: Boolean)
