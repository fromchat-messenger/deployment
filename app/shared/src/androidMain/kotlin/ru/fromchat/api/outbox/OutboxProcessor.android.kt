package ru.fromchat.api.outbox

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.pr0gramm3r101.utils.UtilsLibrary

actual fun scheduleOutboxProcessing(instanceId: String) {
    val id = instanceId.trim()
    if (id.isEmpty()) return
    val request = OneTimeWorkRequestBuilder<OutboxSendWorker>()
        .setInputData(
            Data.Builder()
                .putString(OutboxSendWorker.KEY_INSTANCE_ID, id)
                .build(),
        )
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build(),
        )
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()
    WorkManager.getInstance(UtilsLibrary.context).enqueueUniqueWork(
        "outbox-send-$id",
        ExistingWorkPolicy.APPEND_OR_REPLACE,
        request,
    )
}

actual fun cancelOutboxProcessing(instanceId: String) {
    val id = instanceId.trim()
    if (id.isEmpty()) return
    WorkManager.getInstance(UtilsLibrary.context).cancelUniqueWork("outbox-send-$id")
}
