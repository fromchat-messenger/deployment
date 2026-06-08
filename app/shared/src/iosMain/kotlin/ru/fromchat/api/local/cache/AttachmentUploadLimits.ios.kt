package ru.fromchat.api.local.cache

private const val IOS_MAX_BYTES = 32L * 1024L * 1024L

actual fun maxInMemoryEncryptPlaintextBytes(): Long = IOS_MAX_BYTES
