package ru.fromchat.api

actual suspend fun syncPushTokenAfterStartup() {
    uploadPendingFcmTokenIfAvailable()
    ensureFcmTokenRegistered()
}
