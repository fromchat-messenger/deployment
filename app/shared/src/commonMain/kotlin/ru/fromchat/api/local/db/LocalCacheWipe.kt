package ru.fromchat.api.local.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.fromchat.api.local.db.store.MessageDatabaseProvider
import ru.fromchat.api.local.send.cancelOutboxProcessing
import ru.fromchat.api.local.cache.CacheContext
import ru.fromchat.api.local.cache.wipeFromChatCacheDirectory
import ru.fromchat.ui.chat.utils.PublicChatPanelCache
import ru.fromchat.ui.chat.panels.dm.DmPanelCache

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
