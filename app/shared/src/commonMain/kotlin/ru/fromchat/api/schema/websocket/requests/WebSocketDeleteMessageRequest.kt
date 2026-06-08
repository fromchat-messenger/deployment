package ru.fromchat.api.schema.websocket.requests

import kotlinx.serialization.Serializable

@Serializable
data class WebSocketDeleteMessageRequest(
    val message_id: Int
)