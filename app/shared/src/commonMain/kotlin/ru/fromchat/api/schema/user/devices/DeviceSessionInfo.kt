package ru.fromchat.api.schema.user.devices

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceSessionInfo(
    @SerialName("session_id") val sessionId: String,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("device_type") val deviceType: String? = null,
    @SerialName("os_name") val osName: String? = null,
    @SerialName("os_version") val osVersion: String? = null,
    @SerialName("browser_name") val browserName: String? = null,
    @SerialName("browser_version") val browserVersion: String? = null,
    val brand: String? = null,
    val model: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_seen") val lastSeen: String? = null,
    val revoked: Boolean? = null,
    val current: Boolean = false
)