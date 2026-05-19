package ru.fromchat.api.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.fromchat.api.outbox.cancelOutboxProcessing
import ru.fromchat.core.cache.CacheContext
import ru.fromchat.core.cache.wipeFromChatCacheDirectory
import ru.fromchat.ui.chat.PublicChatPanelCache
import ru.fromchat.ui.dm.DmPanelCache

/**
 * Drops the on-disk FromChat cache tree and reopens SQLite on next access.
 * Call [writeFromChatCacheGeneration] after this when wiping from settings.
 */
suspend fun wipeLocalCacheOnDisk() {
    val instanceId = runCatching { CacheContext.activeInstanceId.value.trim() }.getOrDefault("")
    if (instanceId.isNotEmpty()) {
        cancelOutboxProcessing(instanceId)
    }
    withContext(Dispatchers.Default) {
        MessageDatabaseProvider.closeAndReset()
    }
    wipeFromChatCacheDirectory()
    PublicChatPanelCache.clear()
    DmPanelCache.clearAll()
}
