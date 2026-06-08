package ru.fromchat.api.crypto.transport

private const val TRANSPORT_FILE_KEY_CONTEXT = "fromchat_transport_file_v1"

internal expect fun deriveTransportFileAesKey(
    transportPublicKeyB64: String,
    ephemeralSecretKey: ByteArray,
): ByteArray

internal expect suspend fun aesGcmEncryptChunk(
    key: ByteArray,
    plaintext: ByteArray,
): Pair<ByteArray, ByteArray>

internal expect fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

/** Matches Python `derive_key_from_shared_secret` (HKDF-SHA256, 16 zero salt). */
internal fun hkdfTransportFileKey(sharedSecret: ByteArray): ByteArray {
    val salt = ByteArray(16)
    val prk = hmacSha256(salt, sharedSecret)
    val info = TRANSPORT_FILE_KEY_CONTEXT.encodeToByteArray()
    val okm = ByteArray(32)
    var t = byteArrayOf()
    var offset = 0
    var counter = 1
    while (offset < okm.size) {
        val input = t + info + counter.toByte()
        t = hmacSha256(prk, input)
        val copyLen = minOf(t.size, okm.size - offset)
        t.copyInto(okm, destinationOffset = offset, startIndex = 0, endIndex = copyLen)
        offset += copyLen
        counter++
    }
    return okm
}
