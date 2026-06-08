package ru.fromchat.api.schema.messages

import kotlinx.serialization.Serializable

@Serializable
data class ReactionUser(
    val id: Int,
    val username: String
)