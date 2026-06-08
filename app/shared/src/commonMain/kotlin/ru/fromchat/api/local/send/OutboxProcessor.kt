package ru.fromchat.api.local.send

/**
 * Drains pending outbox rows for the active instance (Android: WorkManager; iOS: BG task hook).
 */
expect fun scheduleOutboxProcessing(instanceId: String)

expect fun cancelOutboxProcessing(instanceId: String)
