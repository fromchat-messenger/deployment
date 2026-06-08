package ru.fromchat.api.schema.messages.dm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DmHistoryResponse(
    val messages: List<DmEnvelope> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean? = null
)