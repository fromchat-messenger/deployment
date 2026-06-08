package ru.fromchat.api.schema.user.auth

import kotlinx.serialization.Serializable

@Serializable
data class CheckAuthResponse(
    val authenticated: Boolean = false,
    val username: String? = null,
    val admin: Boolean? = null,
)