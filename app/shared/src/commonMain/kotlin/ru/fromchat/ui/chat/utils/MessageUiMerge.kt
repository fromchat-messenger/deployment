package ru.fromchat.ui.chat.utils

import ru.fromchat.api.ApiClient
import ru.fromchat.api.local.cache.DecryptedImageCache
import ru.fromchat.api.local.db.aspectRatioFromDimensionPair
import ru.fromchat.api.local.messages.sortMessagesForChatDisplay
import ru.fromchat.api.schema.messages.Message

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
    // Keep in-flight panel optimistics even when the DB Flow emission already stripped them.
    val extraPanel = panelMessages.filter { panel ->
        val cid = panel.client_message_id?.trim()?.takeIf { it.isNotEmpty() }
        when {
            panel.id < 0 && cid != null && cid !in mergedClientIds -> true
            panel.id > 0 && panel.id !in mergedIds && (cid.isNullOrEmpty() || cid !in mergedClientIds) -> true
            else -> false
        }
    }

    return dedupeMessagesByClientId(
        dropSupersededOptimisticMessages(mergedDb + extraPanel, ApiClient.user?.id),
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
        uploadJobId = if (confirmed) null else panel.uploadJobId ?: db.uploadJobId,
        pendingFileAspectRatio = if (confirmed) {
            db.fileDimensions?.firstOrNull()?.let { (w, h) -> aspectRatioFromDimensionPair(w, h) }
                ?: db.fileAspectRatios?.firstOrNull()
                ?: db.pendingFileAspectRatio
        } else {
            panel.pendingFileAspectRatio ?: db.pendingFileAspectRatio
        },
        uploadProgress = if (confirmed) null else panel.uploadProgress ?: db.uploadProgress,
        uploadError = if (confirmed) null else panel.uploadError ?: db.uploadError,
        files = db.files ?: panel.files,
        dmEnvelope = db.dmEnvelope ?: panel.dmEnvelope,
        fileThumbnails = db.fileThumbnails ?: panel.fileThumbnails,
        fileAspectRatios = db.fileAspectRatios ?: panel.fileAspectRatios,
        fileSizes = db.fileSizes ?: panel.fileSizes,
        fileDimensions = db.fileDimensions ?: panel.fileDimensions,
        content = db.content.ifBlank { panel.content },
        isContentCorrupted = panel.isContentCorrupted || db.isContentCorrupted,
        reply_to = db.reply_to ?: panel.reply_to,
    )
}

/** Keeps hydrated [Message.reply_to] when a network/DB refresh omits nested reply payloads. */
internal fun preserveReplyToFromExisting(
    existing: List<Message>,
    incoming: List<Message>,
): List<Message> {
    val existingById = existing.associateBy { it.id }
    return incoming.map { msg ->
        if (msg.reply_to != null) msg
        else existingById[msg.id]?.reply_to?.let { msg.copy(reply_to = it) } ?: msg
    }
}
