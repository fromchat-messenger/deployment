package ru.fromchat.api.schema.user.devices

import kotlinx.serialization.Serializable

@Serializable
data class DevicesListResponse(
    val devices: List<DeviceSessionInfo> = emptyList()
)