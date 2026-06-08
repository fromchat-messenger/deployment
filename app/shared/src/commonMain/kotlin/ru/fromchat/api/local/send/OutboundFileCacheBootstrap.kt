package ru.fromchat.api.local.send

import ru.fromchat.api.local.cache.DecryptedFileCache
import ru.fromchat.api.local.download.AttachmentDownloadNotifier
import ru.fromchat.api.local.download.DownloadedFileRegistry

/**
 * Copies a staged outbound file into the cache under [displayFilename] (correct extension on disk).
 */
suspend fun seedOutboundFileAsDownloaded(
    messageId: Int,
    fileIndex: Int,
    localFileUri: String,
    displayFilename: String,
    clientMessageId: String?,
) {
    val cacheUri = DecryptedFileCache.seedFromLocalFile(
        messageId = messageId,
        fileIndex = fileIndex,
        localFileUri = localFileUri,
        displayFilename = displayFilename,
        clientMessageId = clientMessageId,
    ) ?: return
    DownloadedFileRegistry.setExportUri(
        messageId = messageId,
        fileIndex = fileIndex,
        clientMessageId = clientMessageId,
        exportUri = cacheUri,
    )
}

suspend fun clearOutboundFileCaches(clientMessageId: String, optimisticMessageId: Int) {
    DecryptedFileCache.invalidateForClientMessage(clientMessageId)
    DownloadedFileRegistry.invalidateForClientMessage(clientMessageId)
    AttachmentDownloadNotifier.clearProgress(
        messageId = optimisticMessageId,
        fileIndex = 0,
        clientMessageId = clientMessageId,
        mirrorAsFileAttachment = true,
    )
}
