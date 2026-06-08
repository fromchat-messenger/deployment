package ru.fromchat.api.crypto.transport

/**
 * Streaming transport file blob (AES-256-GCM, chunked).
 * Layout: "FCAE" | version(1) | frames…
 * Each frame: uint32_be(frame_len) | iv(12) | aes_gcm_ciphertext (plaintext chunk + tag).
 * Legacy blobs: nonce(24) | box_ciphertext (no magic).
 */
object TransportStreamFormat {
    const val MAGIC = "FCAE"
    const val VERSION: Byte = 1
    const val AES_IV_BYTES = 12
    const val PLAINTEXT_CHUNK_BYTES = 256 * 1024
    const val FRAME_LENGTH_BYTES = 4

    fun isStreamBlobPrefix(prefix: ByteArray): Boolean =
        prefix.size >= MAGIC.length &&
            prefix[0] == MAGIC[0].code.toByte() &&
            prefix[1] == MAGIC[1].code.toByte() &&
            prefix[2] == MAGIC[2].code.toByte() &&
            prefix[3] == MAGIC[3].code.toByte()
}

