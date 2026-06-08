package ru.fromchat.api.local.send

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** iOS: drain outbox on enqueue and when the app returns to foreground (parity with WorkManager). */
actual fun scheduleOutboxProcessing(instanceId: String) {
    if (instanceId.trim().isEmpty()) return
    MainScope().launch {
        OutgoingMessageCoordinator.drainActiveInstanceOutbox()
    }
}

actual fun cancelOutboxProcessing(instanceId: String) = Unit
