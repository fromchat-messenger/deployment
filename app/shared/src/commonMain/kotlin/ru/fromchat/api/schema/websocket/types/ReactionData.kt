package ru.fromchat.api.schema.websocket.types

import kotlinx.serialization.Serializable
import ru.fromchat.api.schema.messages.ReactionUser

@Serializable
data class ReactionData(
    val emoji: String,
    val count: Int,
    val users: List<ReactionUser>
)