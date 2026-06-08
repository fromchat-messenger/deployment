package ru.fromchat.api.schema.websocket.types

import kotlinx.serialization.Serializable

@Serializable
data class TypingData(
    val userId: Int,
    val username: String
)