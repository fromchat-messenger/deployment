package ru.fromchat.api.schema.user.keys

import kotlinx.serialization.Serializable

@Serializable
data class BackupBlobResponse(
    val blob: String?
)