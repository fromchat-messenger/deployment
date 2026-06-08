package ru.fromchat.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ServerConfig {
    private val _serverConfig = MutableStateFlow<ServerConfigData?>(null)
    val serverConfig: StateFlow<ServerConfigData?> = _serverConfig.asStateFlow()

    private val config: ServerConfigData
        get() {
            if (_serverConfig.value == null) initialize()
            return _serverConfig.value
                ?: throw IllegalStateException("Server configuration not initialized")
        }

    /**
     * Initialize configuration by loading from storage
     */
    fun initialize() {
        _serverConfig.value = Settings.serverConfig
    }

    /**
     * Update server configuration (persists to storage before updating in-memory state).
     */
    suspend fun updateServerConfig(config: ServerConfigData) {
        Settings.setServerConfig(config)
        _serverConfig.value = config
    }

    /** Voice/video calls when the last server-config apply probe to the calls port succeeded. */
    val callsEnabled: Boolean
        get() = config.callsEnabled

    /** Calls port used for LiveKit WS / reachability (defaults to [DEFAULT_CALLS_PORT]). */
    val callsPort: Int
        get() = config.callsPort

    /**
     * LiveKit WS endpoint derived from the active server config:
     * - host = [ServerConfigData.serverIp]
     * - port = [ServerConfigData.callsPort]
     * - scheme = ws or wss based on [ServerConfigData.httpsEnabled]
     */
    fun liveKitWsUrl(): String {
        val scheme = if (config.httpsEnabled) "wss" else "ws"
        return "$scheme://${config.serverIp}:${config.callsPort}"
    }

    /**
     * LiveKit signaling WebSocket URL: same host and API port as HTTPS reverse proxy (`/api/livekit/rtc`).
     * WebRTC media uses the configured calls port on the server (e.g. HAProxy in front of LiveKit).
     */
    fun liveKitSignalingWsUrl(): String {
        val scheme = if (config.httpsEnabled) "wss" else "ws"
        return "$scheme://${config.serverIp}:${config.apiPort}/api/livekit/rtc"
    }

    /**
     * Get API base URL based on current server configuration
     */
    val apiBaseUrl: String
        get() {
            val scheme = if (config.httpsEnabled) "https" else "http"
            return "$scheme://${config.serverIp}:${config.apiPort}/api"
        }

    /**
     * Get WebSocket URL for app chat signaling (proxied on the same port as HTTPS / API).
     */
    val webSocketUrl: String
        get() {
            val scheme = if (config.httpsEnabled) "wss" else "ws"
            return "$scheme://${config.serverIp}:${config.apiPort}/api/chat/ws"
        }
}

/** Default WebRTC / calls port when none is stored or the field is left empty in UI. */
const val DEFAULT_CALLS_PORT = 8303

/**
 * Server configuration: [serverIp], HTTP(S) [apiPort], [callsPort] for WebRTC / HAProxy (defaults to [DEFAULT_CALLS_PORT]).
 * [callsEnabled] is updated when saving server settings (probe on the calls port); defaults to true when unset.
 */
data class ServerConfigData(
    val serverIp: String,
    val apiPort: Int,
    val callsPort: Int,
    val httpsEnabled: Boolean,
    val callsEnabled: Boolean = true,
)