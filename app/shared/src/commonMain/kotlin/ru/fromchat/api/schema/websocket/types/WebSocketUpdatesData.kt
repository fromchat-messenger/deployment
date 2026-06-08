package ru.fromchat.api.schema.websocket.types

import kotlinx.serialization.Serializable
import ru.fromchat.api.schema.websocket.WebSocketMessage

@Serializable
data class WebSocketUpdatesData(
    val seq: Int,
    val updates: List<WebSocketMessage>
)