package ru.fromchat.api.local.cache

/** Error key stored on [ru.fromchat.api.schema.Message.uploadError] for localized UI. */
const val UPLOAD_ERROR_FILE_TOO_LARGE = "file_too_large"

/** Maximum plaintext attachment size (matches file_storage MAX_UPLOAD_SIZE). */
const val MAX_OUTBOUND_ATTACHMENT_BYTES: Long = 5L * 1024L * 1024L * 1024L

/** Legacy in-memory encrypt threshold; larger files use [TransportFileEncryptor] streaming. */
expect fun maxInMemoryEncryptPlaintextBytes(): Long

internal fun isFileTooLargeForUpload(fileSizeBytes: Long): Boolean =
    fileSizeBytes > MAX_OUTBOUND_ATTACHMENT_BYTES

internal fun shouldStreamEncryptPlaintext(fileSizeBytes: Long): Boolean =
    fileSizeBytes > maxInMemoryEncryptPlaintextBytes()

internal fun isLikelyUploadMemoryError(error: Throwable?): Boolean {
    if (error == null) return false
    if (error::class.simpleName == "OutOfMemoryError") return true
    val msg = error.message?.lowercase().orEmpty()
    return msg.contains("outofmemory") || msg.contains("failed to allocate")
}
