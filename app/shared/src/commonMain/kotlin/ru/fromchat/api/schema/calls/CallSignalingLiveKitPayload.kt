package ru.fromchat.api.schema.calls

import kotlinx.serialization.Serializable

@Serializable
data class CallSignalingLiveKitPayload(
    val toUserId: Int,
    val roomName: String,
    val serverUrl: String,
)