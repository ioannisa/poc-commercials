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

@Serializable
internal data class LoginResponseDto(
    val token: String,
    val displayName: String,
    val isAdmin: Boolean = false,
    val stations: List<StationAccessDto> = emptyList(),
)

@Serializable
internal data class ChangePasswordDto(val currentPassword: String, val newPassword: String)

@Serializable
internal data class RecoverPasswordDto(val username: String, val recoveryCode: String, val newPassword: String)

@Serializable
internal data class RecoveryCodesDto(val codes: List<String> = emptyList())
