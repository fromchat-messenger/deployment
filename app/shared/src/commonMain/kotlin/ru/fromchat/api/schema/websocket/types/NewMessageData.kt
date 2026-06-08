package ru.fromchat.api.schema.websocket.types

import kotlinx.serialization.Serializable
import ru.fromchat.api.schema.messages.Message

@Serializable
data class NewMessageData(
    val message: Message
)