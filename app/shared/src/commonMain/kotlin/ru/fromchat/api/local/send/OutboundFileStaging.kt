package ru.fromchat.api.local.send

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.fromchat.api.local.cache.CacheContext
import ru.fromchat.api.local.cache.DecryptedFileCache
import ru.fromchat.api.local.cache.stageOutboundFileForUpload
import ru.fromchat.api.local.download.DownloadedFileRegistry

/**
 * Copy a non-image attachment into instance upload storage before any upload work runs.
 * Seeds decrypted-file cache under the real filename (extension preserved for open/install).
 */
suspend fun prepareOutboundFileForSend(
    clientMessageId: String,
    sourceUri: String,
    optimisticMessageId: Int,
    displayFilename: String,
): StagedOutboundPreview? = withContext(Dispatchers.Default) {
    val instanceId = runCatching { CacheContext.requireActiveInstanceId() }.getOrNull() ?: return@withContext null
    val staged = runCatching {
        stageOutboundFileForUpload(instanceId, clientMessageId, sourceUri)
    }.getOrNull() ?: return@withContext null
    if (staged.sizeBytes <= 0L) return@withContext null

    val cacheUri = DecryptedFileCache.seedFromLocalFile(
        messageId = optimisticMessageId,
        fileIndex = 0,
        localFileUri = staged.uri,
        displayFilename = displayFilename,
        clientMessageId = clientMessageId,
    ) ?: return@withContext null
    DownloadedFileRegistry.setExportUri(
        messageId = optimisticMessageId,
        fileIndex = 0,
        clientMessageId = clientMessageId,
        exportUri = cacheUri,
    )

    StagedOutboundPreview(stagedUri = staged.uri, aspectRatio = null, sizeBytes = staged.sizeBytes)
}
