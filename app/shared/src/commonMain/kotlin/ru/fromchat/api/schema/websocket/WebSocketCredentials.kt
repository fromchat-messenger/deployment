package ru.fromchat.api.schema.websocket

import kotlinx.serialization.Serializable

@Serializable
data class WebSocketCredentials(
    val scheme: String,
    val credentials: String
)