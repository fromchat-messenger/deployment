package ru.fromchat.api.local.send

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.fromchat.api.local.cache.CacheContext
import ru.fromchat.api.local.cache.DecryptedImageCache
import ru.fromchat.api.local.cache.stageOutboundFileForUpload
import ru.fromchat.api.local.download.ChatPreviewDecodeSize
import ru.fromchat.api.local.download.LocalDecodedImageCache

data class StagedOutboundPreview(
    val stagedUri: String,
    val aspectRatio: Float?,
    val sizeBytes: Long = 0L,
)

/**
 * Copy attachment into instance upload storage, seed disk + decoded bitmap caches.
 * Upload worker reads the staged path; UI reads bitmap cache (no picker URI after revoke).
 */
suspend fun prepareOutboundImageForSend(
    clientMessageId: String,
    sourceUri: String,
    optimisticMessageId: Int,
    aspectRatio: Float?,
): StagedOutboundPreview? = withContext(Dispatchers.Default) {
    val instanceId = runCatching { CacheContext.requireActiveInstanceId() }.getOrNull() ?: return@withContext null
    val staged = runCatching {
        stageOutboundFileForUpload(instanceId, clientMessageId, sourceUri)
    }.getOrNull() ?: return@withContext null
    if (staged.sizeBytes <= 0L) return@withContext null

    DecryptedImageCache.seedFromLocalFile(
        messageId = optimisticMessageId,
        fileIndex = 0,
        localFileUri = staged.uri,
        clientMessageId = clientMessageId,
    )

    val storageKey = DecryptedImageCache.storageKey(optimisticMessageId, 0, clientMessageId)
    val decodeTarget = previewSeedDecodeSize(aspectRatio)
    LocalDecodedImageCache.loadFull(storageKey, staged.uri, decodeTarget)

    StagedOutboundPreview(stagedUri = staged.uri, aspectRatio = aspectRatio, sizeBytes = staged.sizeBytes)
}

/** High-quality seed decode before the tile is measured (refined when laid out). */
internal fun previewSeedDecodeSize(aspectRatio: Float?): ChatPreviewDecodeSize {
    val longEdge = 1440
    val ratio = aspectRatio?.takeIf { it.isFinite() && it > 0f } ?: 1f
    return if (ratio >= 1f) {
        ChatPreviewDecodeSize(longEdge, (longEdge / ratio).toInt().coerceAtLeast(1))
    } else {
        ChatPreviewDecodeSize((longEdge * ratio).toInt().coerceAtLeast(1), longEdge)
    }
}

suspend fun clearOutboundImageCaches(clientMessageId: String, optimisticMessageId: Int) {
    withContext(Dispatchers.Default) {
        DecryptedImageCache.invalidateForClientMessage(clientMessageId)
        val storageKey = DecryptedImageCache.storageKey(optimisticMessageId, 0, clientMessageId)
        LocalDecodedImageCache.evict(storageKey)
    }
}
