package ru.fromchat.api.schema.messages.dm

import kotlinx.serialization.Serializable
import ru.fromchat.api.schema.user.User

@Serializable
data class DmConversation(
    val user: User,
    val lastMessage: DmEnvelope,
    val unreadCount: Int
)