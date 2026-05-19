package ru.fromchat.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.fromchat.ui.chat.AttachmentMediaLog
import ru.fromchat.ui.chat.DecryptedImageCache

sealed class AttachmentDownloadProgress {
    data class InProgress(val storageKey: String, val percent: Int) : AttachmentDownloadProgress()
    data class Success(
        val storageKey: String,
        val messageId: Int = 0,
    ) : AttachmentDownloadProgress()
    data class Failed(val storageKey: String, val error: String) : AttachmentDownloadProgress()
}

/**
 * In-chat download/decrypt progress (no system notification).
 * [progressPercentByKey] is the source of truth for UI; [progressFlow] is for one-shot side effects.
 */
object AttachmentDownloadNotifier {
    private val _progressFlow = MutableSharedFlow<AttachmentDownloadProgress>(extraBufferCapacity = 64)
    val progressFlow: SharedFlow<AttachmentDownloadProgress> = _progressFlow

    private val _progressPercentByKey = MutableStateFlow<Map<String, Int>>(emptyMap())
    val progressPercentByKey: StateFlow<Map<String, Int>> = _progressPercentByKey.asStateFlow()

    private val _failedKeys = MutableStateFlow<Set<String>>(emptySet())
    val failedKeys: StateFlow<Set<String>> = _failedKeys.asStateFlow()

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun emit(
        progress: AttachmentDownloadProgress,
        messageLabel: String? = null,
        messageId: Int = 0,
        fileIndex: Int = 0,
        clientMessageId: String? = null,
    ) {
        val msg = AttachmentMediaLog.messageLabel(messageLabel)
        val primaryKey = when (progress) {
            is AttachmentDownloadProgress.InProgress -> progress.storageKey
            is AttachmentDownloadProgress.Success -> progress.storageKey
            is AttachmentDownloadProgress.Failed -> progress.storageKey
        }
        val mirrorKeys = DecryptedImageCache.progressLookupKeys(
            messageId = messageId,
            fileIndex = fileIndex,
            clientMessageId = clientMessageId,
        ).ifEmpty { listOf(primaryKey) }
        when (progress) {
            is AttachmentDownloadProgress.InProgress -> {
                if (progress.percent == 1 || progress.percent % 15 == 0 || progress.percent >= 95) {
                    AttachmentMediaLog.download(
                        "progress",
                        "key" to progress.storageKey,
                        "pct" to progress.percent,
                        "msg" to msg,
                        "mirror" to mirrorKeys.joinToString(","),
                    )
                }
            }
            is AttachmentDownloadProgress.Success ->
                AttachmentMediaLog.download(
                    "success",
                    "key" to progress.storageKey,
                    "msgId" to progress.messageId,
                    "msg" to msg,
                )
            is AttachmentDownloadProgress.Failed ->
                AttachmentMediaLog.download(
                    "failed",
                    "key" to progress.storageKey,
                    "err" to progress.error,
                    "msg" to msg,
                )
        }
        when (progress) {
            is AttachmentDownloadProgress.InProgress -> {
                val pct = progress.percent.coerceIn(1, 100)
                _progressPercentByKey.update { map ->
                    map + mirrorKeys.associateWith { pct }
                }
            }
            is AttachmentDownloadProgress.Success -> {
                _progressPercentByKey.update { map ->
                    map + mirrorKeys.associateWith { 100 }
                }
            }
            is AttachmentDownloadProgress.Failed -> {
                _progressPercentByKey.update { map -> map - mirrorKeys.toSet() }
                _failedKeys.update { keys -> keys + mirrorKeys.toSet() }
            }
        }
        mainScope.launch {
            _progressFlow.emit(progress)
        }
    }

    fun clearProgress(messageId: Int, fileIndex: Int, clientMessageId: String? = null) {
        val keys = DecryptedImageCache.progressLookupKeys(messageId, fileIndex, clientMessageId).toSet()
        _progressPercentByKey.update { map -> map - keys }
        _failedKeys.update { failed -> failed - keys }
    }

    fun isFailed(messageId: Int, fileIndex: Int, clientMessageId: String? = null): Boolean =
        DecryptedImageCache.progressLookupKeys(messageId, fileIndex, clientMessageId)
            .any { it in _failedKeys.value }
}
