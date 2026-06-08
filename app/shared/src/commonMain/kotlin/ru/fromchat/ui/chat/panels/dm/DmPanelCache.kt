package ru.fromchat.ui.chat.panels.dm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.fromchat.api.ApiClient
import ru.fromchat.api.local.cache.CacheContext

/**
 * Cache of DM panels by otherUserId so that when navigating back from profile
 * we reuse the same panel and don't reload messages.
 *
 * Uses a [SupervisorJob] scope instead of [androidx.compose.runtime.rememberCoroutineScope]
 * so [ru.fromchat.ui.chat.ChatPanel] state callbacks keep working after the composable is left.
 */
object DmPanelCache {
    private var supervisorJob = SupervisorJob()
    private var panelScope: CoroutineScope = CoroutineScope(supervisorJob + Dispatchers.Main.immediate)

    private val panels = mutableMapOf<Int, DmPanel>()
    private var cachedInstanceId: String = ""

    fun onActiveInstanceChanged(instanceId: String) {
        if (cachedInstanceId.isNotEmpty() && cachedInstanceId != instanceId) {
            clearAll()
        }
        cachedInstanceId = instanceId
    }

    private fun ensureScope() {
        if (!supervisorJob.isActive) {
            supervisorJob = SupervisorJob()
            panelScope = CoroutineScope(supervisorJob + Dispatchers.Main.immediate)
        }
    }

    fun getOrCreate(otherUserId: Int): DmPanel {
        ensureScope()
        val instanceId = CacheContext.activeInstanceId.value.trim()
        if (instanceId.isNotEmpty() && cachedInstanceId.isNotEmpty() && cachedInstanceId != instanceId) {
            clearAll()
        }
        if (instanceId.isNotEmpty()) cachedInstanceId = instanceId
        return panels.getOrPut(otherUserId) {
            DmPanel(
                otherUserId = otherUserId,
                coroutineScope = panelScope,
                currentUserId = ApiClient.user?.id
            )
        }
    }

    fun remove(otherUserId: Int) {
        panels.remove(otherUserId)?.destroy()
    }

    fun clearAll() {
        panels.values.forEach { it.destroy() }
        panels.clear()
        supervisorJob.cancel()
    }
}
