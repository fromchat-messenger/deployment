package ru.fromchat.api.schema.messages.publicchat

import kotlinx.serialization.Serializable

@Serializable
data class EditMessageRequest(
    val content: String
)