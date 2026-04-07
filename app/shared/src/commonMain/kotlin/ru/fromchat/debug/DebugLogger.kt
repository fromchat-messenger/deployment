package ru.fromchat.debug

data class DebugLogPayload(
    val sessionId: String = "9be525",
    val runId: String,
    val hypothesisId: String,
    val location: String,
    val message: String,
    val data: String,
    val timestamp: Long,
)

expect object DebugLogger {
    fun log(payload: DebugLogPayload)
}

