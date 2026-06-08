package ru.fromchat.api.local.messages

import korlibs.crypto.SHA256
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Opaque id for optimistic send / server ack matching.
 * SHA-256 hex of send-time material (epoch ms + nonce); echoed to the sender only on [dmNew].
 */
fun generateClientMessageId(): String {
    val material = "${Clock.System.now().toEpochMilliseconds()}:${Random.nextInt()}"
    return SHA256.digest(material.encodeToByteArray()).hexLower
}
