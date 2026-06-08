package ru.fromchat.api.schema.server

import kotlinx.serialization.Serializable

@Serializable
data class RegisteredUserCountResponse(
    val count: Int
)