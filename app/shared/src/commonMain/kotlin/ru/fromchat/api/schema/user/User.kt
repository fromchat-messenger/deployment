package ru.fromchat.api.schema.user

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: Int,
    val created_at: String,
    val last_seen: String,
    val online: Boolean,
    val username: String,
    @SerialName("display_name") val displayName: String? = null,
    val admin: Boolean? = null,
    val bio: String? = null,
    val profile_picture: String? = null,
    val suspended: Boolean? = null,
    @SerialName("suspension_reason") val suspensionReason: String? = null
)

