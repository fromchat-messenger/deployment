package ru.fromchat.api.local.cache

private const val MIN_BYTES = 512L * 1024L
private const val MAX_BYTES = 48L * 1024L * 1024L

actual fun maxInMemoryEncryptPlaintextBytes(): Long {
    val runtime = Runtime.getRuntime()
    val used = runtime.totalMemory() - runtime.freeMemory()
    val headroom = (runtime.maxMemory() - used).coerceAtLeast(0L)
    // Plaintext + ciphertext + transient buffers during NaCl box.
    val budget = headroom / 3L
    return budget.coerceIn(MIN_BYTES, MAX_BYTES)
}
