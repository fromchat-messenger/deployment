package ru.fromchat.core.cache

/**
 * Detects OS "clear cache" (generation sentinel missing) and records a new generation after open.
 */
expect suspend fun ensureFromChatCacheGeneration()

expect suspend fun writeFromChatCacheGeneration()
