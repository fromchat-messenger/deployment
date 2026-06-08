package ru.fromchat.api

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import com.pr0gramm3r101.utils.settings.settings
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import ru.fromchat.api.schema.core.SimpleStatusResponse
import ru.fromchat.config.ServerConfig
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val PENDING_FCM_TOKEN_KEY = "pending_fcm_token"
private const val CURRENT_FCM_TOKEN_KEY = "current_fcm_token"

private suspend fun fetchCurrentFcmToken(): String? = suspendCancellableCoroutine { cont ->
    FirebaseMessaging.getInstance().token
        .addOnCompleteListener { task: Task<String> ->
            if (task.isSuccessful) {
                cont.resume(task.result)
            } else {
                cont.resumeWithException(
                    task.exception ?: IllegalStateException("Failed to fetch FCM token")
                )
            }
        }
}

private suspend fun postFcmToken(token: String): Boolean {
    return runCatching {
        val suffix = token.takeLast(8)
        ApiClient.http
            .post("${ServerConfig.apiBaseUrl}/push/register") {
                header("Content-Type", "application/json")
                setBody(ApiClient.json.encodeToString(mapOf("token" to token)))
            }
            .body<SimpleStatusResponse>()
        Log.d("FcmReg", "Uploaded FCM token to server: ...$suffix")
        true
    }.getOrElse { e ->
        Log.e("FcmReg", "Failed to upload FCM token: ${e.message}", e)
        false
    }
}

actual suspend fun uploadPendingFcmTokenIfAvailable() = withContext(Dispatchers.IO) {
    try {
        val pending = settings.getString(PENDING_FCM_TOKEN_KEY, "")

        // Only upload if we have auth token
        if (ApiClient.token.isNullOrEmpty() || pending.isBlank()) {
            Log.d("FcmReg", "Auth token missing or no FCM token; deferring FCM token upload")
            return@withContext
        }

        if (postFcmToken(pending)) {
            settings.putString(CURRENT_FCM_TOKEN_KEY, pending)
            settings.remove(PENDING_FCM_TOKEN_KEY)
        } else {
            Log.d("FcmReg", "Deferring pending FCM token upload")
        }
    } catch (e: Exception) {
        Log.e("FcmReg", "uploadPendingFcmTokenIfAvailable error: ${e.message}")
    }
}

actual suspend fun ensureFcmTokenRegistered(): Boolean = withContext(Dispatchers.IO) {
    if (ApiClient.token.isNullOrEmpty()) {
        Log.d("FcmReg", "Auth token missing; skip explicit FCM sync")
        return@withContext false
    }

    try {
        uploadPendingFcmTokenIfAvailable()
        val token = fetchCurrentFcmToken() ?: return@withContext false
        val prepared = token.trim()
        if (prepared.isBlank()) return@withContext false

        val current = settings.getString(CURRENT_FCM_TOKEN_KEY, "")
        if (prepared == current) return@withContext true

        val result = postFcmToken(prepared)
        if (result) {
            settings.putString(CURRENT_FCM_TOKEN_KEY, prepared)
            settings.remove(PENDING_FCM_TOKEN_KEY)
        }
        result
    } catch (e: Exception) {
        Log.e("FcmReg", "ensureFcmTokenRegistered error: ${e.message}")
        false
    }
}

actual suspend fun unregisterFcmTokenFromServer(): Boolean = withContext(Dispatchers.IO) {
    if (ApiClient.token.isNullOrEmpty()) {
        Log.d("FcmReg", "Auth token missing; cannot unregister FCM token")
        return@withContext false
    }

    val token = settings.getString(CURRENT_FCM_TOKEN_KEY, "").trim()
    Log.d("FcmReg", "unregisterFcmTokenFromServer requested with token=...${token.takeLast(8)}")
    return@withContext runCatching {
        ApiClient.http.post("${ServerConfig.apiBaseUrl}/push/unregister") {
            header("Content-Type", "application/json")
            if (token.isNotEmpty()) {
                setBody(ApiClient.json.encodeToString(mapOf("token" to token)))
            }
        }
        settings.remove(PENDING_FCM_TOKEN_KEY)
        if (token.isNotBlank()) {
            settings.remove(CURRENT_FCM_TOKEN_KEY)
        }
        true
    }.getOrElse { e ->
        Log.e("FcmReg", "Failed to unregister FCM token: ${e.message}")
        false
    }
}
