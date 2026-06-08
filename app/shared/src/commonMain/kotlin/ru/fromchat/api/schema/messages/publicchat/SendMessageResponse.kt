package ru.fromchat.api.schema.messages.publicchat

import kotlinx.serialization.Serializable
import ru.fromchat.api.schema.messages.Message

@Serializable
data class SendMessageResponse(
    val status: String,
    val message: Message
)