package ru.fromchat.api.schema.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ServerInstanceIdResponse(
    @SerialName("instance_id") val instanceId: String,
)