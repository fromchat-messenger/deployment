package ru.fromchat.api.schema.messages.dm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.fromchat.api.schema.user.profile.VerificationStatus

@Serializable
data class DmConversationUser(
    val id: Int,
    val username: String = "",
    @SerialName("display_name") val displayName: String? = null,
    val profile_picture: String? = null,
    val online: Boolean? = null,
    val last_seen: String? = null,
    val verified: Boolean? = null,
    @SerialName("verification_status") val verificationStatus: VerificationStatus? = null,
    val suspended: Boolean? = null,
    @SerialName("suspension_reason") val suspensionReason: String? = null,
    val deleted: Boolean? = null,
)
