package ru.fromchat.api.schema.websocket

import kotlinx.serialization.Serializable

@Serializable
data class WebSocketError(
    val code: Int,
    val detail: String
)