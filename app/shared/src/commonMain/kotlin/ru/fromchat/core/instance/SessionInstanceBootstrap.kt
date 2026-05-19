package ru.fromchat.core.instance

import ru.fromchat.api.ApiClient
import ru.fromchat.api.db.InstanceRegistryStore
import ru.fromchat.core.Settings
import ru.fromchat.api.outbox.scheduleOutboxProcessing
import ru.fromchat.core.cache.CacheContext
import ru.fromchat.core.config.Config

sealed interface SessionBootstrapResult {
    data object Ready : SessionBootstrapResult
    data object OfflineCached : SessionBootstrapResult
    data object LogoutRequired : SessionBootstrapResult
}

/**
 * Ensures [CacheContext] has an active instance for the current server config when a session exists.
 */
suspend fun bootstrapSessionInstance(hasToken: Boolean): SessionBootstrapResult {
    if (!hasToken) return SessionBootstrapResult.Ready
    val config = Settings.serverConfig
    val apiBase = apiBaseUrlFor(config)
    val resolve = resolveInstanceId(
        config = config,
        apiBaseUrl = apiBase,
        forceNetwork = true,
    )
    return when (resolve) {
        is InstanceIdResolveResult.Cached,
        is InstanceIdResolveResult.Fetched,
        is InstanceIdResolveResult.InstanceIdChanged,
        -> {
            val id = when (resolve) {
                is InstanceIdResolveResult.Cached -> resolve.instanceId
                is InstanceIdResolveResult.Fetched -> resolve.instanceId
                is InstanceIdResolveResult.InstanceIdChanged -> resolve.newId
            }
            CacheContext.setActiveInstance(id, ApiClient.user?.id)
            scheduleOutboxProcessing(id)
            SessionBootstrapResult.Ready
        }
        InstanceIdResolveResult.Timeout,
        InstanceIdResolveResult.Unreachable,
        -> {
            val cached = InstanceRegistryStore.getActiveInstanceIdForConfig(config)?.trim().orEmpty()
            if (cached.isNotEmpty() && isValidInstanceUuid(cached)) {
                CacheContext.setActiveInstance(cached, ApiClient.user?.id)
                scheduleOutboxProcessing(cached)
                SessionBootstrapResult.OfflineCached
            } else {
                SessionBootstrapResult.OfflineCached
            }
        }
        InstanceIdResolveResult.Unsupported -> SessionBootstrapResult.LogoutRequired
    }
}

suspend fun logoutIfInstanceUnsupported() {
    runCatching { ApiClient.logout() }
    ApiClient.clearMemorySession()
    CacheContext.clearActive()
}
