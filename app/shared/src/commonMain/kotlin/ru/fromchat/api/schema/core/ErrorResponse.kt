package ru.fromchat.api.schema.core

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    val detail: String
)