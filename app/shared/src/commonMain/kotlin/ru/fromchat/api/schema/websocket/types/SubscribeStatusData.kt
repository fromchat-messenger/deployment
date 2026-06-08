package ru.fromchat.api.schema.websocket.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SubscribeStatusData(
    @SerialName("userId") val userId: Int
)