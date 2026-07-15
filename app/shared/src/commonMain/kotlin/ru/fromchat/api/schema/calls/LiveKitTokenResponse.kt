package ru.fromchat.api.schema.calls

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LiveKitTokenResponse(
    val token: String,
    @SerialName("room_name") val roomName: String,
)
