package ru.fromchat.api.local.workers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import ru.fromchat.api.local.AttachmentMediaLog

/** Shared upload/download progress events (outbox worker + UI + decrypt pipeline). */
object AttachmentUploadNotifier {
    private val _progressFlow = MutableSharedFlow<AttachmentUploadProgress>(extraBufferCapacity = 64)
    val progressFlow: SharedFlow<AttachmentUploadProgress> = _progressFlow
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun emit(progress: AttachmentUploadProgress, messageLabel: String? = null) {
        val msg = AttachmentMediaLog.messageLabel(messageLabel)
        when (progress) {
            is AttachmentUploadProgress.Pending ->
                AttachmentMediaLog.upload(
                    "pending",
                    "job" to progress.jobId,
                    "file" to progress.filename,
                    "msg" to msg,
                )
            is AttachmentUploadProgress.InProgress -> {
                if (progress.percent == 1 || progress.percent % 10 == 0 || progress.percent >= 95) {
                    AttachmentMediaLog.upload(
                        "progress",
                        "job" to progress.jobId,
                        "pct" to progress.percent,
                        "file" to progress.filename,
                        "msg" to msg,
                    )
                }
            }
            is AttachmentUploadProgress.Success ->
                AttachmentMediaLog.upload("success", "job" to progress.jobId, "msg" to msg)
            is AttachmentUploadProgress.Failed ->
                AttachmentMediaLog.upload(
                    "failed",
                    "job" to progress.jobId,
                    "err" to progress.error,
                    "msg" to msg,
                )
        }
        mainScope.launch {
            _progressFlow.emit(progress)
        }
    }
}
