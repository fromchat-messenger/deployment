package ru.fromchat.debug

actual object DebugLogger {
    actual fun log(payload: DebugLogPayload) {
        // iOS target: no-op for debug logging in this session.
    }
}

