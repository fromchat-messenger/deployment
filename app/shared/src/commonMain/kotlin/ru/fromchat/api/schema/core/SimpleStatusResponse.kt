package ru.fromchat.api.schema.core

import kotlinx.serialization.Serializable

@Serializable
data class SimpleStatusResponse(
    val status: String? = null,
    val message: String? = null
)