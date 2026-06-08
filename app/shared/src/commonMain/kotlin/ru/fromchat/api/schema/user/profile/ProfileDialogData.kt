package ru.fromchat.api.schema.user.profile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProfileDialogData(
    @SerialName("user_id") val userId: Int? = null,
    val username: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("profile_picture") val profilePicture: String? = null,
    val bio: String? = null,
    @SerialName("member_since") val memberSince: String? = null,
    val online: Boolean? = null,
    @SerialName("is_own_profile") val isOwnProfile: Boolean = false,
    val verified: Boolean? = null,
    val suspended: Boolean? = null,
    @SerialName("suspension_reason") val suspensionReason: String? = null,
    val deleted: Boolean? = null
)