package ru.fromchat.api.schema.messages.dm

import kotlinx.serialization.Serializable

@Serializable
data class DmConversationsResponse(
    val conversations: List<DmConversation> = emptyList()
)