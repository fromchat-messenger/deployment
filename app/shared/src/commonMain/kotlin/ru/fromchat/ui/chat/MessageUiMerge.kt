package ru.fromchat.ui.chat

import ru.fromchat.api.Message
import ru.fromchat.api.db.aspectRatioFromDimensionPair
import ru.fromchat.api.sortMessagesForChatDisplay
import ru.fromchat.ui.chat.DecryptedImageCache

/**
 * SQLDelight rows omit optimistic attachment fields; merge DB snapshot with in-memory UI state.
 */
internal fun mergeDatabaseMessagesWithPanelState(
    panelMessages: List<Message>,
    dbMessages: List<Message>,
): List<Message> {
    val panelByClientId = panelMessages.mapNotNull { msg ->
        msg.client_message_id?.trim()?.takeIf { it.isNotEmpty() }?.let { it to msg }
    }.toMap()
    val panelById = panelMessages.associateBy { it.id }

    val mergedDb = dbMessages.map { db ->
        val panel = db.client_message_id?.trim()?.takeIf { it.isNotEmpty() }?.let { panelByClientId[it] }
            ?: panelById[db.id]
        mergeMessageUiFields(db, panel)
    }

    val mergedClientIds = mergedDb.mapNotNull { it.client_message_id?.trim()?.takeIf { id -> id.isNotEmpty() } }.toSet()
    val mergedIds = mergedDb.map { it.id }.toSet()
    val extraPanel = panelMessages.filter { panel ->
        val cid = panel.client_message_id?.trim()?.takeIf { it.isNotEmpty() }
        when {
            panel.id < 0 && cid != null && cid !in mergedClientIds -> true
            panel.id > 0 && panel.id !in mergedIds && (cid.isNullOrEmpty() || cid !in mergedClientIds) -> true
            else -> false
        }
    }

    return dedupeMessagesByClientId(
        dropSupersededOptimisticMessages(mergedDb + extraPanel, ru.fromchat.api.ApiClient.user?.id),
    ).let { sortMessagesForChatDisplay(it) }
}

internal fun mergeMessageUiFields(db: Message, panel: Message?): Message {
    if (panel == null) return db
    val confirmed = db.id > 0
    val localPreview = db.pendingFileUri?.takeIf { DecryptedImageCache.isDecryptedImageCacheUri(it) }
        ?: panel.pendingFileUri?.takeIf { DecryptedImageCache.isDecryptedImageCacheUri(it) }
    return db.copy(
        pendingFileUri = when {
            confirmed -> localPreview ?: db.pendingFileUri
            else -> panel.pendingFileUri ?: db.pendingFileUri
        },
        pendingFilename = if (confirmed) null else panel.pendingFilename ?: db.pendingFilename,
        pendingFileAspectRatio = if (confirmed) {
            db.fileDimensions?.firstOrNull()?.let { (w, h) -> aspectRatioFromDimensionPair(w, h) }
                ?: db.fileAspectRatios?.firstOrNull()
                ?: db.pendingFileAspectRatio
        } else {
            panel.pendingFileAspectRatio ?: db.pendingFileAspectRatio
        },
        uploadJobId = if (confirmed) null else panel.uploadJobId ?: db.uploadJobId,
        uploadProgress = if (confirmed) null else panel.uploadProgress ?: db.uploadProgress,
        files = db.files ?: panel.files,
        dmEnvelope = db.dmEnvelope ?: panel.dmEnvelope,
        fileThumbnails = db.fileThumbnails ?: panel.fileThumbnails,
        fileAspectRatios = db.fileAspectRatios ?: panel.fileAspectRatios,
        fileSizes = db.fileSizes ?: panel.fileSizes,
        fileDimensions = db.fileDimensions ?: panel.fileDimensions,
        content = db.content.ifBlank { panel.content },
        isContentCorrupted = panel.isContentCorrupted || db.isContentCorrupted,
    )
}
