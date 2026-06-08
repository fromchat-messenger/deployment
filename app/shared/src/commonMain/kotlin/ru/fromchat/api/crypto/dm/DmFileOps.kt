package ru.fromchat.api.crypto.dm

/**
 * DM attachment file decrypt (streaming; shared across Android and iOS).
 */
internal object DmFileOps {
    suspend fun aesGcmDecryptFileToPath(
        iv: ByteArray,
        encryptedPath: String,
        key: ByteArray,
        outputPath: String,
    ): Long = aesGcmDecryptMekFileToPath(
        iv = iv,
        encryptedPath = encryptedPath,
        key = key,
        outputPath = outputPath,
    )
}
