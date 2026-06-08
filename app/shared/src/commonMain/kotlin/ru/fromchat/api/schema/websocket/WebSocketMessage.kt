package ru.fromchat.api.schema.websocket

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class WebSocketMessage(
    val type: String,
    val credentials: WebSocketCredentials? = null,
    val data: JsonElement? = null,
    val error: WebSocketError? = null
)