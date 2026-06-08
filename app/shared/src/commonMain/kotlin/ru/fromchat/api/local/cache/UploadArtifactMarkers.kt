package ru.fromchat.api.local.cache

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class UploadArtifactOkMarker(
    val actualBytes: Long,
    val expectedBytes: Long = 0L,
)

private val markerJson = Json { ignoreUnknownKeys = true }

internal fun encodeUploadArtifactOkMarker(actualBytes: Long, expectedBytes: Long = 0L): String =
    markerJson.encodeToString(UploadArtifactOkMarker(actualBytes, expectedBytes))

internal fun decodeUploadArtifactOkMarker(raw: String): UploadArtifactOkMarker? =
    runCatching { markerJson.decodeFromString<UploadArtifactOkMarker>(raw.trim()) }.getOrNull()

internal fun UploadArtifactOkMarker.isValidOnDisk(diskBytes: Long, expectedBytes: Long): Boolean {
    if (actualBytes != diskBytes) return false
    if (expectedBytes > 0L && this.expectedBytes > 0L && this.expectedBytes != expectedBytes) return false
    if (expectedBytes > 0L && actualBytes != expectedBytes) return false
    return actualBytes > 0L
}
