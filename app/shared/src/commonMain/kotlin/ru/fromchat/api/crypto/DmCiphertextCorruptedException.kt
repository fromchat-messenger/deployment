package ru.fromchat.api.crypto

/**
 * AES-GCM authentication failed or ciphertext is unrecoverable (wrong key, truncated, or tampered).
 * UI may show [CorruptedDmMessagePlaceholder] when this is caught.
 */
class DmCiphertextCorruptedException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)
