package ru.fromchat.api.schema.user.profile

import kotlinx.serialization.Serializable

@Serializable
data class VerifyResponse(
    val verified: Boolean
)