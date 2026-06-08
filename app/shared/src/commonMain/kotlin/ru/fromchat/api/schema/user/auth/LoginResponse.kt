package ru.fromchat.api.schema.user.auth

import kotlinx.serialization.Serializable
import ru.fromchat.api.schema.user.User

@Serializable
data class LoginResponse(
    val user: User,
    val token: String
)