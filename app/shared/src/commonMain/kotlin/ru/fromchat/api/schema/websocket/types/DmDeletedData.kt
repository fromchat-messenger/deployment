package ru.fromchat.api.schema.websocket.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DmDeletedData(
    val id: Int,
    @SerialName("senderId") val senderId: Int,
    @SerialName("recipientId") val recipientId: Int? = null,
)