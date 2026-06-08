package ru.fromchat.api.schema.calls

import kotlinx.serialization.Serializable

@Serializable
data class CallSignalingLiveKitControl(
    val toUserId: Int,
    val kind: String,
    val roomName: String? = null,
)