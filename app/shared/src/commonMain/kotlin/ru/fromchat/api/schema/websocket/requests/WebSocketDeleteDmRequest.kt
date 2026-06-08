package ru.fromchat.api.schema.websocket.requests

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WebSocketDeleteDmRequest(
    val id: Int,
    @SerialName("recipientId") val recipientId: Int,
)