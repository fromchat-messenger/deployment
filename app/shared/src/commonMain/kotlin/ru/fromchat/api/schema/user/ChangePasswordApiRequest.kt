package ru.fromchat.api.schema.user

import kotlinx.serialization.Serializable

@Serializable
data class ChangePasswordApiRequest(
    val currentPasswordDerived: String,
    val newPasswordDerived: String,
    val logoutAllExceptCurrent: Boolean = false
)