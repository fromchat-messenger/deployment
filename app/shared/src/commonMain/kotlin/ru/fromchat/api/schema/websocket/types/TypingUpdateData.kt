package ru.fromchat.api.schema.websocket.types

import kotlinx.serialization.Serializable

@Serializable
data class TypingUpdateData(
    val userId: Int,
    val username: String
)