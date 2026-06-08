package ru.fromchat.api.schema.websocket.requests

import kotlinx.serialization.Serializable

@Serializable
data class WebSocketSendMessageRequest(
    val content: String,
    val reply_to_id: Int? = null,
    val client_message_id: String? = null
)