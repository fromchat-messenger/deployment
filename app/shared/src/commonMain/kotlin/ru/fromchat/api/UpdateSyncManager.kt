package ru.fromchat.api

import com.pr0gramm3r101.utils.settings.settings
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.fromchat.Logger
import ru.fromchat.api.local.WebSocketManager
import ru.fromchat.api.local.db.store.ConnectionStateStore
import ru.fromchat.api.schema.websocket.WebSocketCredentials
import ru.fromchat.api.schema.websocket.WebSocketMessage
import ru.fromchat.api.schema.websocket.requests.GetUpdatesRequest
import ru.fromchat.api.schema.websocket.requests.GetUpdatesResponse
import kotlin.concurrent.Volatile

/**
 * Tracks the last seen WebSocket update sequence for the current user and
 * persists it between sessions so we can ask the backend for missed updates.
 */
object UpdateSyncManager {
    private const val KEY_UPDATES_LAST_SEQ_PREFIX = "updates_last_seq_user_"

    private val _lastSeq = MutableStateFlow(0)
    val lastSeq: StateFlow<Int> = _lastSeq.asStateFlow()

    private val _lastMissedCount = MutableStateFlow<Int?>(null)
    val lastMissedCount: StateFlow<Int?> = _lastMissedCount.asStateFlow()

    @Volatile
    private var gapDetectionInProgress: Boolean = false

    suspend fun initializeFromStorage(currentUserId: Int?) {
        val userId = currentUserId ?: return
        val key = KEY_UPDATES_LAST_SEQ_PREFIX + userId
        val stored = runCatching { settings.getInt(key, 0) }.getOrDefault(0)
        Logger.d("UpdateSyncManager", "Loaded lastSeq=$stored for userId=$userId")
        _lastSeq.value = stored
        ConnectionStateStore.updateSeqAndMissed(lastSeq = stored, missedCount = null)
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun onUpdatesBatch(seq: Int) {
        if (seq <= 0) return
        val currentUserId = ApiClient.user?.id ?: return
        val newSeq = seq.coerceAtLeast(_lastSeq.value)
        if (newSeq == _lastSeq.value) return

        _lastSeq.value = newSeq
        ConnectionStateStore.updateSeqAndMissed(lastSeq = newSeq, missedCount = _lastMissedCount.value)

        val key = KEY_UPDATES_LAST_SEQ_PREFIX + currentUserId
        GlobalScope.launch(Dispatchers.Default) {
            runCatching {
                settings.putInt(key, newSeq)
            }.onFailure {
                Logger.w("UpdateSyncManager", "Failed to persist lastSeq=$newSeq for userId=$currentUserId: ${it.message}", it)
            }
        }
    }

    fun updateMissedCount(missedCount: Int?) {
        _lastMissedCount.value = missedCount
        ConnectionStateStore.updateSeqAndMissed(lastSeq = _lastSeq.value, missedCount = missedCount)
    }

    fun resetInMemoryOnLogout() {
        _lastSeq.value = 0
        _lastMissedCount.value = null
        gapDetectionInProgress = false
        ConnectionStateStore.updateSeqAndMissed(lastSeq = 0, missedCount = null)
    }

    suspend fun clearPersistedSeqForUser(userId: Int) {
        runCatching {
            settings.remove("updates_last_seq_user_$userId")
        }
    }

    /**
     * Ask the backend for missed updates between our lastSeq and the current sequence.
     * This call is idempotent while in progress and will no-op if there is no active
     * WebSocket session or no authenticated user.
     */
    suspend fun runGapDetectionIfNeeded() {
        if (gapDetectionInProgress) {
            Logger.d("UpdateSyncManager", "Gap detection already in progress, ignoring request")
            return
        }

        val token = ApiClient.token
        if (token.isNullOrEmpty()) {
            Logger.d("UpdateSyncManager", "No auth token; skipping gap detection")
            return
        }

        gapDetectionInProgress = true
        val startSeq = _lastSeq.value

        try {
            Logger.d("UpdateSyncManager", "Running gap detection from lastSeq=$startSeq")
            if (startSeq > 0) {
                ConnectionStateStore.onUpdating(start = true)
            }

            val requestMessage = WebSocketMessage(
                type = "getUpdates",
                credentials = WebSocketCredentials(
                    scheme = "Bearer",
                    credentials = token
                ),
                data = ApiClient.json.encodeToJsonElement(
                    GetUpdatesRequest.serializer(),
                    GetUpdatesRequest(lastSeq = startSeq)
                )
            )

            val response = WebSocketManager.request(requestMessage)
            val data = response?.data

            if (data != null) {
                runCatching {
                    val parsed = ApiClient.json.decodeFromJsonElement(GetUpdatesResponse.serializer(), data)
                    Logger.d(
                        "UpdateSyncManager",
                        "Gap detection result: status=${parsed.status}, lastSeq=${parsed.lastSeq}, missed=${parsed.missedCount}"
                    )
                    onUpdatesBatch(parsed.lastSeq)
                    updateMissedCount(parsed.missedCount)
                }.onFailure {
                    Logger.w("UpdateSyncManager", "Failed to parse getUpdates response: ${it.message}", it)
                }
            } else {
                Logger.d("UpdateSyncManager", "No data returned from getUpdates; treating as no-op")
            }
        } catch (t: Throwable) {
            Logger.w("UpdateSyncManager", "Gap detection failed: ${t.message}", t)
        } finally {
            if (startSeq > 0) {
                ConnectionStateStore.onUpdating(start = false)
            }
            gapDetectionInProgress = false
        }
    }
}

