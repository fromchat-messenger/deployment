package ru.fromchat.api.crypto.dm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.fromchat.api.crypto.DmCiphertextCorruptedException

/**
 * Streams a MEK-encrypted attachment (ciphertext || tag on disk; IV passed separately) to [outputPath].
 */
internal suspend fun aesGcmDecryptMekFileToPath(
    iv: ByteArray,
    encryptedPath: String,
    key: ByteArray,
    outputPath: String,
): Long = withContext(Dispatchers.Default) {
    try {
        platformAesGcmStreamDecryptMekFile(
            iv = iv,
            encryptedPath = encryptedPath,
            key = key,
            outputPath = outputPath,
        )
    } catch (e: Throwable) {
        throw e as? DmCiphertextCorruptedException ?: DmCiphertextCorruptedException(cause = e)
    }
}

internal expect suspend fun platformAesGcmStreamDecryptMekFile(
    iv: ByteArray,
    encryptedPath: String,
    key: ByteArray,
    outputPath: String,
): Long

internal fun Throwable.decryptFailureMessage(): String =
    message?.takeIf { it.isNotBlank() }
        ?: cause?.message?.takeIf { it.isNotBlank() }
        ?: this::class.simpleName
        ?: "download_failed"
