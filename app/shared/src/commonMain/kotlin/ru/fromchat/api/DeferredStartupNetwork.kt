package ru.fromchat.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Network work that must not block cold start or the first frame.
 * Call once after auth UI is routable (logged-in shell or login).
 */
object DeferredStartupNetwork {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scheduled = false

    fun scheduleAfterUiVisible() {
        if (scheduled) return
        scheduled = true
        scope.launch {
            if (ApiClient.token.isNullOrEmpty()) return@launch
            runCatching {
                val profile = ApiClient.getOwnProfile()
                ApiClient.syncSuspensionStateFromProfile(profile)
            }
            runCatching { syncPushTokenAfterStartup() }
        }
    }
}

/** Platform push token registration (FCM on Android). No-op where unsupported. */
expect suspend fun syncPushTokenAfterStartup()
