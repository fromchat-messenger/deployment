package ru.fromchat.api.local.workers

import kotlinx.coroutines.flow.SharedFlow
import ru.fromchat.api.local.send.scheduleOutboxProcessing
import ru.fromchat.api.local.cache.CacheContext

actual object AttachmentUploadQueue {
    actual val progressFlow: SharedFlow<AttachmentUploadProgress> = AttachmentUploadNotifier.progressFlow

    actual fun enqueue(job: AttachmentUploadJob) {
        scheduleOutboxProcessing(CacheContext.activeInstanceId.value.trim())
    }

    actual fun cancel(jobId: String) {
        // Outbox + artifact cleanup handled when user deletes pending message from UI.
    }
}
