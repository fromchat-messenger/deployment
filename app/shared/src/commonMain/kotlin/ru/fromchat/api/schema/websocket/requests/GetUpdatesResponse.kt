package ru.fromchat.api.schema.websocket.requests

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GetUpdatesResponse(
    val status: String,
    @SerialName("lastSeq") val lastSeq: Int,
    @SerialName("missedCount") val missedCount: Int
)