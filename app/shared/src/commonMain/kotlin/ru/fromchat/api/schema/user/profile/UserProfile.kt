package ru.fromchat.api.schema.user.profile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    val id: Int,
    val username: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("profile_picture") val profilePicture: String? = null,
    val bio: String? = null,
    val online: Boolean = false,
    @SerialName("last_seen") val lastSeen: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val verified: Boolean? = null,
    val suspended: Boolean? = null,
    @SerialName("suspension_reason") val suspensionReason: String? = null,
    val deleted: Boolean? = null,
    /**
     * Client-only: true when this row was built from public-chat message metadata, not a full
     * `/user/...` response. The backend does not send this key.
     */
    @SerialName("client_preview_only") val isClientPreviewOnly: Boolean = false
)