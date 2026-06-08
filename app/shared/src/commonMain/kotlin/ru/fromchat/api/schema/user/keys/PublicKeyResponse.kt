package ru.fromchat.api.schema.user.keys

import kotlinx.serialization.Serializable

@Serializable
data class PublicKeyResponse(
    val publicKey: String? = null
)