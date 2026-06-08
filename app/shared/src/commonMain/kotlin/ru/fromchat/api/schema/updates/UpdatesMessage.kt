package ru.fromchat.api.schema.updates

import kotlinx.serialization.Serializable

@Serializable
data class UpdatesMessage(
    val type: String,
    val seq: Int,
    val updates: List<UpdateItem>
)