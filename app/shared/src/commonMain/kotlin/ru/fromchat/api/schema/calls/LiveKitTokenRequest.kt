package ru.fromchat.api.schema.calls

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LiveKitTokenRequest(
    @SerialName("peer_user_id") val peerUserId: Int,
    @SerialName("room_name") val roomName: String? = null,
)