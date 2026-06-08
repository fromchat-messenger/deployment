package ru.fromchat.api.schema.websocket.requests

import kotlinx.serialization.Serializable

@Serializable
data class WebSocketEditMessageRequest(
    val message_id: Int,
    val content: String
)