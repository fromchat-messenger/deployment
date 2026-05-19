package ru.fromchat.api.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import ru.fromchat.core.ServerConfigData
import ru.fromchat.core.cache.CacheContext
import ru.fromchat.core.configKey
import ru.fromchat.api.outbox.cancelOutboxProcessing
import ru.fromchat.ui.chat.PublicChatPanelCache
import ru.fromchat.ui.dm.DmPanelCache

object InstanceRegistryStore {
    private val db get() = MessageDatabaseProvider.database

    private fun nowIso(): String = Clock.System.now().toString()

    suspend fun getActiveInstanceIdForConfig(config: ServerConfigData): String? =
        withContext(Dispatchers.Default) {
            MessageDatabaseProvider.withDatabaseRecover {
                db.messageDatabaseQueries
                    .selectActiveInstanceIdForConfig(config.configKey())
                    .executeAsOneOrNull()
            }
        }

    suspend fun registerInstanceEncountered(instanceId: String) {
        val id = instanceId.trim()
        if (id.isEmpty()) return
        val now = nowIso()
        withContext(Dispatchers.Default) {
            MessageDatabaseProvider.withDatabaseRecover {
                val existing = db.messageDatabaseQueries
                    .selectAllInstanceIds()
                    .executeAsList()
                    .any { it.equals(id, ignoreCase = true) }
                if (existing) {
                    db.messageDatabaseQueries.touchInstanceRegistry(now, id)
                } else {
                    db.messageDatabaseQueries.upsertInstanceRegistry(id, now, now)
                }
            }
        }
    }

    suspend fun rebindServerInstance(config: ServerConfigData, newInstanceId: String) {
        val newId = newInstanceId.trim()
        require(newId.isNotEmpty())
        val previousActive = CacheContext.activeInstanceId.value.trim()
        if (previousActive.isNotEmpty() && !previousActive.equals(newId, ignoreCase = true)) {
            cancelOutboxProcessing(previousActive)
        }
        val key = config.configKey()
        val now = nowIso()
        withContext(Dispatchers.Default) {
            MessageDatabaseProvider.withDatabaseRecover {
                db.messageDatabaseQueries.upsertServerBinding(key, newId, now)
                val existing = db.messageDatabaseQueries
                    .selectAllInstanceIds()
                    .executeAsList()
                    .any { it.equals(newId, ignoreCase = true) }
                if (existing) {
                    db.messageDatabaseQueries.touchInstanceRegistry(now, newId)
                } else {
                    db.messageDatabaseQueries.upsertInstanceRegistry(newId, now, now)
                }
            }
        }
        val userId = CacheContext.activeUserId.value
        CacheContext.setActiveInstance(newId, userId)
        PublicChatPanelCache.clear()
        DmPanelCache.clearAll()
    }

    suspend fun rebindServerInstanceOnMismatch(
        config: ServerConfigData,
        previousId: String?,
        fetchedId: String,
    ) {
        val prev = previousId?.trim().orEmpty()
        val next = fetchedId.trim()
        if (prev.isEmpty() || prev.equals(next, ignoreCase = true)) {
            rebindServerInstance(config, next)
            return
        }
        rebindServerInstance(config, next)
    }

    /** User-initiated clear for one instance partition. */
    suspend fun purgePartition(instanceId: String) {
        val id = instanceId.trim()
        if (id.isEmpty()) return
        withContext(Dispatchers.Default) {
            MessageDatabaseProvider.withDatabaseRecover {
                db.messageDatabaseQueries.deleteAllMessagesForInstance(id)
                db.messageDatabaseQueries.deleteAllConversationsForInstance(id)
                db.messageDatabaseQueries.deleteAllOutboxForInstance(id)
                db.messageDatabaseQueries.deleteAllAttachmentsForInstance(id)
                db.messageDatabaseQueries.deleteAllProfilesForInstance(id)
            }
        }
    }

    suspend fun purgeAllCache() {
        withContext(Dispatchers.Default) {
            MessageDatabaseProvider.withDatabaseRecover {
                db.messageDatabaseQueries.purgeAllCache()
            }
        }
    }
}
