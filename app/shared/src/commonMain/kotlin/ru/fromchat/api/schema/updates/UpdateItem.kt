package ru.fromchat.api.schema.updates

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class UpdateItem(
    val type: String,
    val data: JsonElement? = null
)