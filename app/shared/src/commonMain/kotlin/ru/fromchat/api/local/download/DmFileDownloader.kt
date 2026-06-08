package ru.fromchat.api.local.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.fromchat.api.local.cache.DecryptedFileCache
import ru.fromchat.api.schema.messages.dm.DmEnvelope
import ru.fromchat.api.schema.messages.dm.DmFile

object DmFileDownloader {
    suspend fun downloadToCache(
        messageId: Int,
        fileIndex: Int,
        file: DmFile,
        envelope: DmEnvelope,
        currentUserId: Int?,
        clientMessageId: String?,
        messageLabel: String? = null,
    ): Boolean = withContext(Dispatchers.Default) {
        DecryptedFileCache.getOrDecrypt(
            messageId = messageId,
            fileIndex = fileIndex,
            file = file,
            envelope = envelope,
            currentUserId = currentUserId,
            clientMessageId = clientMessageId,
            messageLabel = messageLabel,
        ) != null
    }
}

expect suspend fun openCachedAttachmentFile(
    cacheUri: String,
    mimeType: String,
    displayFilename: String? = null,
): Boolean
