package ru.fromchat.api.local.cache

internal actual fun enqueuePlatformCopy(storageKey: String) {
    AttachmentFileCopyWorker.enqueue(storageKey)
}