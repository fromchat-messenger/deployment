package ru.fromchat.api.schema.messages.dm

import kotlinx.serialization.Serializable

@Serializable
data class DmConversation(
    val user: DmConversationUser,
    val lastMessage: DmEnvelope,
    val unreadCount: Int
)