package ru.fromchat.api.local.cache

data class StagedOutboundFile(
    val uri: String,
    val sizeBytes: Long,
)

/** Best-effort size for a picker URI without loading file contents. */
expect suspend fun queryOutboundUriSizeBytes(fileUri: String): Long?

/**
 * Copies the picked attachment into instance-scoped cache so uploads survive process death.
 * Uses atomic write (`.part` → rename) and a `.source.ok` marker.
 */
expect suspend fun stageOutboundFileForUpload(
    instanceId: String,
    clientMessageId: String,
    sourceUri: String,
    expectedSizeBytes: Long = 0L,
): StagedOutboundFile

/** Reads the picked attachment from a platform URI string. */
expect suspend fun readOutboundFileBytes(fileUri: String): ByteArray

/** Copies a staged/picked file into [destinationPath] without loading the whole file into RAM. */
expect suspend fun copyOutboundFileToPath(sourceUri: String, destinationPath: String)

/** Absolute path for the committed encrypted upload blob (`*.enc`). */
expect fun encryptedUploadBlobPath(instanceId: String, clientMessageId: String): String

/** Absolute path for in-progress encrypted blob (`*.enc.part`). */
expect fun encryptedUploadBlobPartPath(instanceId: String, clientMessageId: String): String

/** Opens a streaming reader for a staged outbound file URI. Caller must close. */
expect suspend fun openOutboundFileInputStream(fileUri: String): OutboundFileInputStream?

interface OutboundFileInputStream {
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int
    suspend fun close()
}

expect suspend fun saveEncryptedUploadBlob(instanceId: String, clientMessageId: String, bytes: ByteArray)

expect suspend fun loadEncryptedUploadBlob(instanceId: String, clientMessageId: String): ByteArray?

/** Size of committed `.enc` only (requires `.enc.ok` marker). */
expect suspend fun encryptedUploadBlobSizeBytes(instanceId: String, clientMessageId: String): Long?

expect suspend fun readEncryptedUploadBlobRange(
    instanceId: String,
    clientMessageId: String,
    offset: Long,
    length: Int,
): ByteArray

expect suspend fun saveUploadTransportCipherJson(instanceId: String, clientMessageId: String, json: String)

/** Atomic write: `.cipher.json.part` then rename. */
expect suspend fun saveUploadTransportCipherJsonAtomic(
    instanceId: String,
    clientMessageId: String,
    json: String,
)

expect suspend fun loadUploadTransportCipherJson(instanceId: String, clientMessageId: String): String?

expect suspend fun isStagedSourceReady(
    instanceId: String,
    clientMessageId: String,
    expectedSizeBytes: Long,
): Boolean

expect suspend fun isEncryptedBlobReady(
    instanceId: String,
    clientMessageId: String,
    expectedEncryptedSizeBytes: Long?,
): Boolean

/** After streaming encrypt to `.enc.part`, rename and write `.enc.ok`. */
expect suspend fun commitEncryptedUploadBlob(
    instanceId: String,
    clientMessageId: String,
    encryptedSizeBytes: Long,
)

/** Drop stale partial files and uncommitted blobs after process death. */
expect suspend fun repairInterruptedUploadArtifacts(instanceId: String, clientMessageId: String)

expect suspend fun clearUploadArtifacts(instanceId: String, clientMessageId: String)

/** Drops upload secrets and staging copy; keeps [DecryptedImageCache] files intact. */
expect suspend fun clearUploadSecretsOnly(instanceId: String, clientMessageId: String)
