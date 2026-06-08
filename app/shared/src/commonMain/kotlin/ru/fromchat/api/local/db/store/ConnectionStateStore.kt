package ru.fromchat.api.local.db.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global connection state for the realtime WebSocket layer.
 *
 * The app never exposes a terminal "disconnected" state – it always keeps
 * trying to reconnect, so the public states are:
 * - CONNECTING: transport trying to establish or re-establish connection.
 * - UPDATING: connection is up and we are fetching missed updates.
 * - CONNECTED: healthy, up-to-date connection.
 */
enum class ConnectionStatus {
    CONNECTING,
    UPDATING,
    CONNECTED
}

data class ConnectionMetadata(
    val lastSeq: Int = 0,
    val lastMissedCount: Int? = null
)

object ConnectionStateStore {
    private val _status = MutableStateFlow(ConnectionStatus.CONNECTING)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _metadata = MutableStateFlow(ConnectionMetadata())
    val metadata: StateFlow<ConnectionMetadata> = _metadata.asStateFlow()

    fun onConnecting() {
        _status.value = ConnectionStatus.CONNECTING
    }

    fun onConnected() {
        _status.value = ConnectionStatus.CONNECTED
    }

    fun onUpdating(start: Boolean) {
        _status.value = if (start) ConnectionStatus.UPDATING else ConnectionStatus.CONNECTED
    }

    fun updateSeqAndMissed(lastSeq: Int, missedCount: Int?) {
        _metadata.value = ConnectionMetadata(lastSeq = lastSeq, lastMissedCount = missedCount)
    }
}

