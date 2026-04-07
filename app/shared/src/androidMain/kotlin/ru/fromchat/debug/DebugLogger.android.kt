package ru.fromchat.debug

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

actual object DebugLogger {
    private const val ENDPOINT =
        "http://127.0.0.1:7809/ingest/d9aebf8d-fb01-41c9-af88-eb0676507989"
    private const val SESSION_ID = "9be525"

    // Single shared Ktor client for all debug logs.
    private val client = HttpClient(OkHttp)

    // Lightweight scope for fire-and-forget debug logging.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    actual fun log(payload: DebugLogPayload) {
        // Network logging is best-effort; never throw from here.
        val safeMessage = payload.message.replace("\"", "'")
        val safeLocation = payload.location.replace("\"", "'")
        val json = buildString {
            append('{')
            append("\"sessionId\":\"").append(payload.sessionId).append('"')
            append(",\"runId\":\"").append(payload.runId).append('"')
            append(",\"hypothesisId\":\"").append(payload.hypothesisId).append('"')
            append(",\"location\":\"").append(safeLocation).append('"')
            append(",\"message\":\"").append(safeMessage).append('"')
            append(",\"data\":").append(payload.data)
            append(",\"timestamp\":").append(payload.timestamp)
            append('}')
        }

        scope.launch {
            try {
                client.post(ENDPOINT) {
                    contentType(ContentType.Application.Json)
                    header("X-Debug-Session-Id", SESSION_ID)
                    setBody(json)
                }
            } catch (_: Exception) {
                // Swallow all errors in debug logger.
            }
        }
    }
}

