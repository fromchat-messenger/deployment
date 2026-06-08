package ru.fromchat.api.schema.websocket.requests

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GetUpdatesRequest(
    @SerialName("lastSeq") val lastSeq: Int
)