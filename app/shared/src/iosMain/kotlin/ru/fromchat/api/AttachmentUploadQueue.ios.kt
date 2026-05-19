package ru.fromchat.api

import kotlinx.coroutines.flow.SharedFlow
import ru.fromchat.api.outbox.scheduleOutboxProcessing
import ru.fromchat.core.cache.CacheContext

actual object AttachmentUploadQueue {
    actual val progressFlow: SharedFlow<AttachmentUploadProgress> = AttachmentUploadNotifier.progressFlow

    actual fun enqueue(job: AttachmentUploadJob) {
        scheduleOutboxProcessing(CacheContext.activeInstanceId.value.trim())
    }

    actual fun cancel(jobId: String) {
        // Outbox + artifact cleanup handled when user deletes pending message from UI.
    }
}
