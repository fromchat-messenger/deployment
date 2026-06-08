package ru.fromchat.api.schema.websocket.types

import kotlinx.serialization.Serializable

@Serializable
data class MessageDeletedData(
    val message_id: Int
)