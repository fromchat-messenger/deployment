package ru.fromchat.core.cache

data class StagedOutboundFile(
    val uri: String,
    val sizeBytes: Long,
)

/**
 * Copies the picked attachment into instance-scoped cache so uploads survive process death.
 */
expect suspend fun stageOutboundFileForUpload(
    instanceId: String,
    clientMessageId: String,
    sourceUri: String,
): StagedOutboundFile

/** Reads the picked attachment from a platform URI string. */
expect suspend fun readOutboundFileBytes(fileUri: String): ByteArray

expect suspend fun saveEncryptedUploadBlob(instanceId: String, clientMessageId: String, bytes: ByteArray)

expect suspend fun loadEncryptedUploadBlob(instanceId: String, clientMessageId: String): ByteArray?

expect suspend fun saveUploadTransportCipherJson(instanceId: String, clientMessageId: String, json: String)

expect suspend fun loadUploadTransportCipherJson(instanceId: String, clientMessageId: String): String?

expect suspend fun clearUploadArtifacts(instanceId: String, clientMessageId: String)

/** Drops upload secrets and staging copy; keeps [DecryptedImageCache] files intact. */
expect suspend fun clearUploadSecretsOnly(instanceId: String, clientMessageId: String)
