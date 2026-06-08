package ru.fromchat.api.crypto.transport

import ru.fromchat.api.local.cache.openOutboundFileInputStream

/**
 * Encrypts a plaintext attachment file to a transport blob on disk without loading the full file into RAM.
 * Uses chunked AES-256-GCM ([TransportStreamFormat]).
 */
expect object TransportFileEncryptor {
    suspend fun encryptPlaintextFileToTransportBlob(
        sourceUri: String,
        destinationPath: String,
        transportPublicKeyB64: String,
        ephemeralSecretKey: ByteArray,
        plaintextSizeBytes: Long,
        onPlaintextProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ): Long
}

internal fun buildAesTransportFrame(iv: ByteArray, ciphertext: ByteArray): ByteArray {
    val frameLen = TransportStreamFormat.AES_IV_BYTES + ciphertext.size
    val frame = ByteArray(TransportStreamFormat.FRAME_LENGTH_BYTES + frameLen)
    frame[0] = ((frameLen shr 24) and 0xFF).toByte()
    frame[1] = ((frameLen shr 16) and 0xFF).toByte()
    frame[2] = ((frameLen shr 8) and 0xFF).toByte()
    frame[3] = (frameLen and 0xFF).toByte()
    iv.copyInto(frame, destinationOffset = TransportStreamFormat.FRAME_LENGTH_BYTES)
    ciphertext.copyInto(
        frame,
        destinationOffset = TransportStreamFormat.FRAME_LENGTH_BYTES + TransportStreamFormat.AES_IV_BYTES,
    )
    return frame
}

internal suspend fun encryptPlaintextFileToFcaeBlob(
    sourceUri: String,
    writeBytes: suspend (bytes: ByteArray) -> Unit,
    finish: suspend () -> Long,
    transportPublicKeyB64: String,
    ephemeralSecretKey: ByteArray,
    plaintextSizeBytes: Long,
    onPlaintextProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)?,
): Long {
    val key = deriveTransportFileAesKey(transportPublicKeyB64, ephemeralSecretKey)
    try {
        writeBytes(TransportStreamFormat.MAGIC.encodeToByteArray())
        writeBytes(byteArrayOf(TransportStreamFormat.VERSION))
        var bytesRead = 0L
        val readBuffer = ByteArray(TransportStreamFormat.PLAINTEXT_CHUNK_BYTES)
        val input = openOutboundFileInputStream(sourceUri)
            ?: error("Failed to open outbound file for streaming encrypt")
        try {
            while (true) {
                val n = input.read(readBuffer, 0, readBuffer.size)
                if (n <= 0) break
                val (iv, ciphertext) = aesGcmEncryptChunk(key, readBuffer.copyOf(n))
                writeBytes(buildAesTransportFrame(iv, ciphertext))
                bytesRead += n
                val progressTotal = plaintextSizeBytes.takeIf { it > 0L } ?: bytesRead
                onPlaintextProgress?.invoke(bytesRead, progressTotal)
            }
        } finally {
            input.close()
        }
        return finish()
    } finally {
        key.fill(0)
    }
}
