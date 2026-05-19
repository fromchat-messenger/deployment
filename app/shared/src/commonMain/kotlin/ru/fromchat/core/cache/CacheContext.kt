package ru.fromchat.core.cache

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.fromchat.api.ProfileCache
import ru.fromchat.api.db.MessageDatabaseProvider
import ru.fromchat.ui.chat.PublicChatPanelCache
import ru.fromchat.ui.dm.DmPanelCache

/**
 * Active server instance partition for reads/writes.
 * All repository queries must use [activeInstanceId] (non-empty when session is valid).
 */
object CacheContext {
    private val _activeInstanceId = MutableStateFlow("")
    val activeInstanceId: StateFlow<String> = _activeInstanceId.asStateFlow()

    private val _activeUserId = MutableStateFlow<Int?>(null)
    val activeUserId: StateFlow<Int?> = _activeUserId.asStateFlow()

    fun setActiveInstance(instanceId: String, userId: Int?) {
        val trimmed = instanceId.trim()
        val changed = _activeInstanceId.value != trimmed
        _activeInstanceId.value = trimmed
        _activeUserId.value = userId
        if (changed) {
            ProfileCache.onActiveInstanceChanged(trimmed)
            PublicChatPanelCache.onActiveInstanceChanged(trimmed)
            DmPanelCache.onActiveInstanceChanged(trimmed)
            if (trimmed.isNotEmpty()) {
                MessageDatabaseProvider.rebindUnboundPartition(trimmed)
            }
        }
    }

    fun requireActiveInstanceId(): String {
        val id = _activeInstanceId.value.trim()
        require(id.isNotEmpty()) { "No active server instance id" }
        return id
    }

    fun clearActive() {
        _activeInstanceId.value = ""
        _activeUserId.value = null
    }
}
