package ru.fromchat.api.schema.messages

import kotlinx.serialization.Serializable

@Serializable
data class MessagesResponse(
    val status: String,
    val messages: List<Message>
)