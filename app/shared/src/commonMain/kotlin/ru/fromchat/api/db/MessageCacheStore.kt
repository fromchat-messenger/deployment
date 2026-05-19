package ru.fromchat.api.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import ru.fromchat.api.ApiClient
import ru.fromchat.api.DmConversation
import ru.fromchat.api.Message
import ru.fromchat.api.sortMessagesForChatDisplay
import ru.fromchat.ui.chat.DecryptedImageCache
import ru.fromchat.ui.chat.dedupeMessagesByClientId
import ru.fromchat.ui.chat.dropSupersededOptimisticMessages
import ru.fromchat.api.ProfileCache
import ru.fromchat.api.outbox.DmAttachmentOutboxPayload
import ru.fromchat.api.outbox.OutgoingMessageCoordinator
import ru.fromchat.core.cache.CacheContext
import ru.fromchat.core.cache.CacheValidator
import ru.fromchat.db.Conversation
import ru.fromchat.db.MessageDatabase
import ru.fromchat.db.Message as DbMessage

data class CachedConversation(
    val id: String,
    val otherUserId: Int,
    val displayName: String,
    val lastMessagePreview: String?,
    val unreadCount: Int
)

object MessageCacheStore {
    private val db: MessageDatabase get() = MessageDatabaseProvider.database
    private val outboxJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun instanceId(): String = CacheContext.requireActiveInstanceId()

    private fun conversationIdForPublic(): String = conversationIdForGroup(GENERAL_PUBLIC_GROUP_ID)

    private fun truncateDmListPreview(text: String, maxLen: Int = 120): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        return if (t.length > maxLen) t.take(maxLen) + "\u2026" else t
    }

    fun observeMessages(instanceId: String, conversationId: String): Flow<List<Message>> =
        db.messageDatabaseQueries
            .selectMessagesByConversation(instanceId, conversationId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows ->
                val raw = rows.map { it.toAppMessage() }
                val withoutSuperseded = dropSupersededOptimisticMessages(raw, ApiClient.user?.id)
                sortMessagesForChatDisplay(
                    validatedOrEmpty(
                        conversationId,
                        dedupeMessagesByClientId(
                            enrichQueuedOutboundUi(withoutSuperseded, conversationId),
                        ),
                    ),
                )
            }

    suspend fun loadPublicMessages(): List<Message> =
        loadMessages(conversationIdForPublic())

    suspend fun loadRecentPublicMessages(limit: Long): List<Message> =
        loadRecentMessages(conversationIdForPublic(), limit)

    suspend fun replacePublicMessages(messages: List<Message>) {
        val convId = conversationIdForPublic()
        val pending = loadPendingMessages(convId)
        val stillPending = pending.filter { p ->
            val cid = p.client_message_id
            cid == null || messages.none { it.client_message_id == cid }
        }
        val merged = dedupeMessagesByClientId(messages + stillPending)
            .let { sortMessagesForChatDisplay(it) }
        replaceMessages(convId, merged)
    }

    suspend fun clearPublicMessages() {
        clearConversationMessages(conversationIdForPublic())
    }

    suspend fun loadDmMessages(otherUserId: Int): List<Message> =
        loadMessages(conversationIdForDm(otherUserId))

    suspend fun clearDmMessages(otherUserId: Int) {
        clearConversationMessages(conversationIdForDm(otherUserId))
    }

    suspend fun replaceDmMessages(otherUserId: Int, messages: List<Message>) {
        val convId = conversationIdForDm(otherUserId)
        val pending = loadPendingMessages(convId)
        val stillPending = filterStillPendingForReplace(convId, pending, messages)
        val before = messages + stillPending
        val merged = dedupeMessagesByClientId(
            dropSupersededOptimisticMessages(before, ApiClient.user?.id),
        ).let { sortMessagesForChatDisplay(it) }
        val iid = instanceId()
        withContext(Dispatchers.Default) {
            purgeSupersededPendingRows(iid, convId, before, merged)
        }
        replaceMessages(convId, merged)
    }

    private suspend fun filterStillPendingForReplace(
        conversationId: String,
        pending: List<Message>,
        messages: List<Message>,
    ): List<Message> {
        val selfId = ApiClient.user?.id
        val selfHasConfirmedAttachment = messages.any { msg ->
            msg.id > 0 && msg.user_id == selfId && !msg.files.isNullOrEmpty()
        }
        val loneSelfPending = pending.count { it.id < 0 && it.user_id == selfId } == 1
        return pending.filter { p ->
            val cid = p.client_message_id?.trim().orEmpty()
            if (cid.isNotEmpty()) {
                if (hasSentMessageWithClientId(conversationId, cid)) return@filter false
                if (messages.any { it.id > 0 && it.client_message_id == cid }) return@filter false
            }
            val ghostTextOnly = p.id < 0 &&
                p.files.isNullOrEmpty() &&
                p.pendingFileUri.isNullOrBlank() &&
                p.uploadJobId.isNullOrBlank()
            if (
                ghostTextOnly &&
                selfHasConfirmedAttachment &&
                loneSelfPending &&
                p.user_id == selfId
            ) {
                return@filter false
            }
            cid.isEmpty() || messages.none { it.client_message_id == cid }
        }
    }

    suspend fun upsertPublicMessage(message: Message) {
        upsertSingle(conversationIdForPublic(), message)
    }

    suspend fun upsertDmMessage(otherUserId: Int, message: Message) {
        upsertSingle(conversationIdForDm(otherUserId), message)
        syncDmConversationPreviewFromCache(otherUserId)
    }

    suspend fun deletePublicMessageByClientMessageId(clientMessageId: String) {
        deleteByClientMessageId(conversationIdForPublic(), clientMessageId)
    }

    suspend fun deleteDmMessageByClientMessageId(otherUserId: Int, clientMessageId: String) {
        deleteByClientMessageId(conversationIdForDm(otherUserId), clientMessageId)
    }

    suspend fun deleteDmMessageById(otherUserId: Int, messageId: Int) {
        deleteMessageById(conversationIdForDm(otherUserId), messageId)
    }

    suspend fun deleteMessageByClientMessageId(conversationId: String, clientMessageId: String) {
        deleteByClientMessageId(conversationId, clientMessageId)
    }

    suspend fun confirmPublicMessage(clientMessageId: String, confirmed: Message) {
        confirmMessage(conversationIdForPublic(), clientMessageId, confirmed)
    }

    suspend fun confirmDmMessage(otherUserId: Int, clientMessageId: String, confirmed: Message) {
        confirmMessage(conversationIdForDm(otherUserId), clientMessageId, confirmed)
    }

    /** After decrypt, persist [localPreviewUri] so reopen skips network. */
    suspend fun patchDmMessageLocalPreview(
        otherUserId: Int,
        messageId: Int,
        localPreviewUri: String,
    ) {
        if (messageId <= 0 || !DecryptedImageCache.isDecryptedImageCacheUri(localPreviewUri)) return
        val convId = conversationIdForDm(otherUserId)
        val iid = instanceId()
        withContext(Dispatchers.Default) {
            val row = db.messageDatabaseQueries
                .selectMessageById(iid, convId, messageId.toLong())
                .executeAsOneOrNull() ?: return@withContext
            val msg = row.toAppMessage().copy(pendingFileUri = localPreviewUri)
            if (msg.files.isNullOrEmpty() || msg.dmEnvelope == null) return@withContext
            db.messageDatabaseQueries.upsertMessage(
                instanceId = iid,
                id = msg.id.toLong(),
                conversationId = convId,
                userId = msg.user_id.toLong(),
                content = encodePersistedDmMessage(msg),
                timestamp = msg.timestamp,
                isRead = if (msg.is_read) 1L else 0L,
                isEdited = if (msg.is_edited) 1L else 0L,
                replyToId = msg.reply_to?.id?.toLong(),
                clientMessageId = msg.client_message_id,
                deletedFlag = 0L,
                sendStatus = "sent",
            )
        }
    }

    suspend fun markMessageDeleted(conversationId: String, messageId: Int) {
        val iid = instanceId()
        withContext(Dispatchers.Default) {
            db.messageDatabaseQueries.markMessageDeleted(
                instanceId = iid,
                id = messageId.toLong(),
                conversationId = conversationId
            )
        }
    }

    suspend fun replaceDmConversations(conversations: List<DmConversation>) {
        val iid = instanceId()
        withContext(Dispatchers.Default) {
            db.messageDatabaseQueries.transaction {
                conversations.forEach { conv ->
                    val conversationId = conversationIdForDm(conv.user.id)
                    val displayLabel = conv.user.displayName?.trim()?.takeIf { it.isNotEmpty() }
                        ?: conv.user.username.trim()
                    val recent = db.messageDatabaseQueries
                        .selectRecentMessagesByConversation(iid, conversationId, 1)
                        .executeAsList()
                        .firstOrNull()
                    val rawPreview = recent?.content?.orEmpty()?.trim().orEmpty()
                    val preview = rawPreview.takeIf { it.isNotEmpty() }
                        ?.let { truncateDmListPreview(it) }
                        ?.takeIf { it.isNotEmpty() }
                    db.messageDatabaseQueries.upsertConversation(
                        instanceId = iid,
                        id = conversationId,
                        type = "dm",
                        otherUserId = conv.user.id.toLong(),
                        displayName = displayLabel,
                        lastMessageId = conv.lastMessage.id.toLong(),
                        lastMessagePreview = preview,
                        unreadCount = conv.unreadCount.toLong(),
                        updatedAt = conv.lastMessage.timestamp
                    )
                }
            }
        }
    }

    suspend fun loadCachedDmConversations(): List<CachedConversation> =
        withContext(Dispatchers.Default) {
            db.messageDatabaseQueries
                .selectConversationsForInstance(instanceId())
                .executeAsList()
                .filter { row: Conversation -> row.type == "dm" }
                .map { row: Conversation ->
                    CachedConversation(
                        id = row.id,
                        otherUserId = row.otherUserId?.toInt() ?: 0,
                        displayName = row.displayName ?: "",
                        lastMessagePreview = row.lastMessagePreview,
                        unreadCount = row.unreadCount.toInt()
                    )
                }
        }

    private suspend fun syncDmConversationPreviewFromCache(otherUserId: Int) {
        val iid = instanceId()
        val convId = conversationIdForDm(otherUserId)
        withContext(Dispatchers.Default) {
            val row = db.messageDatabaseQueries
                .selectConversationsForInstance(iid)
                .executeAsList()
                .find { it.id == convId } ?: return@withContext
            val recent = db.messageDatabaseQueries
                .selectRecentMessagesByConversation(iid, convId, 1)
                .executeAsList()
                .firstOrNull()
            val rawPreview = recent?.content?.orEmpty()?.trim().orEmpty()
            val preview = rawPreview.takeIf { it.isNotEmpty() }
                ?.let { truncateDmListPreview(it) }
                ?.takeIf { it.isNotEmpty() }
            db.messageDatabaseQueries.upsertConversation(
                instanceId = iid,
                id = row.id,
                type = row.type,
                otherUserId = row.otherUserId,
                displayName = row.displayName,
                lastMessageId = row.lastMessageId,
                lastMessagePreview = preview,
                unreadCount = row.unreadCount,
                updatedAt = row.updatedAt
            )
        }
    }

    private suspend fun clearConversationMessages(conversationId: String) {
        val iid = instanceId()
        withContext(Dispatchers.Default) {
            db.messageDatabaseQueries.deleteMessagesForConversation(iid, conversationId)
        }
    }

    private suspend fun deleteByClientMessageId(conversationId: String, clientMessageId: String) {
        val iid = instanceId()
        withContext(Dispatchers.Default) {
            db.messageDatabaseQueries.deleteMessageByClientMessageId(iid, conversationId, clientMessageId)
        }
    }

    private suspend fun deleteMessageById(conversationId: String, messageId: Int) {
        val iid = instanceId()
        withContext(Dispatchers.Default) {
            db.messageDatabaseQueries.deleteMessageById(
                instanceId = iid,
                conversationId = conversationId,
                id = messageId.toLong(),
            )
        }
    }

    private suspend fun upsertSingle(conversationId: String, msg: Message) {
        val iid = instanceId()
        withContext(Dispatchers.Default) {
            db.messageDatabaseQueries.upsertMessage(
                instanceId = iid,
                id = msg.id.toLong(),
                conversationId = conversationId,
                userId = msg.user_id.toLong(),
                content = msg.content,
                timestamp = msg.timestamp,
                isRead = if (msg.is_read) 1L else 0L,
                isEdited = if (msg.is_edited) 1L else 0L,
                replyToId = msg.reply_to?.id?.toLong(),
                clientMessageId = msg.client_message_id,
                deletedFlag = 0L,
                sendStatus = if (msg.id < 0) "pending" else "sent"
            )
        }
    }

    private suspend fun confirmMessage(conversationId: String, clientMessageId: String, confirmed: Message) {
        val iid = instanceId()
        val storedContent = encodePersistedDmMessage(confirmed)
        withContext(Dispatchers.Default) {
            db.messageDatabaseQueries.transaction {
                db.messageDatabaseQueries.deleteMessageByClientMessageId(iid, conversationId, clientMessageId)
                db.messageDatabaseQueries.upsertMessage(
                    instanceId = iid,
                    id = confirmed.id.toLong(),
                    conversationId = conversationId,
                    userId = confirmed.user_id.toLong(),
                    content = storedContent,
                    timestamp = confirmed.timestamp,
                    isRead = if (confirmed.is_read) 1L else 0L,
                    isEdited = if (confirmed.is_edited) 1L else 0L,
                    replyToId = confirmed.reply_to?.id?.toLong(),
                    clientMessageId = confirmed.client_message_id,
                    deletedFlag = 0L,
                    sendStatus = "sent"
                )
            }
        }
        dmOtherUserIdFromConversationId(conversationId)?.let { syncDmConversationPreviewFromCache(it) }
    }

    private suspend fun loadMessages(conversationId: String): List<Message> {
        val iid = instanceId()
        OutgoingMessageCoordinator.pruneStaleAttachmentOutboxForInstance(iid)
        return withContext(Dispatchers.Default) {
            val raw = db.messageDatabaseQueries
                .selectMessagesByConversation(iid, conversationId)
                .executeAsList()
                .map { row: DbMessage -> row.toAppMessage() }
            val withoutSuperseded = dropSupersededOptimisticMessages(raw, ApiClient.user?.id)
            purgeSupersededPendingRows(iid, conversationId, raw, withoutSuperseded)
            sortMessagesForChatDisplay(
                validatedOrEmpty(
                    conversationId,
                    dedupeMessagesByClientId(
                        enrichQueuedOutboundUi(withoutSuperseded, conversationId),
                    ),
                ),
            )
        }
    }

    private suspend fun loadRecentMessages(conversationId: String, limit: Long): List<Message> {
        val iid = instanceId()
        return withContext(Dispatchers.Default) {
            db.messageDatabaseQueries
                .selectRecentMessagesByConversation(iid, conversationId, limit)
                .executeAsList()
                .map { row: DbMessage -> row.toAppMessage() }
                .reversed()
        }
    }

    private suspend fun loadPendingMessages(conversationId: String): List<Message> {
        val iid = instanceId()
        return withContext(Dispatchers.Default) {
            db.messageDatabaseQueries
                .selectPendingMessagesByConversation(iid, conversationId)
                .executeAsList()
                .map { row: DbMessage -> row.toAppMessage() }
                .filter { row ->
                    val cid = row.client_message_id?.trim().orEmpty()
                    cid.isEmpty() ||
                        db.messageDatabaseQueries
                            .selectSentMessageIdByClientMessageId(iid, conversationId, cid)
                            .executeAsOneOrNull() == null
                }
                .let { enrichQueuedOutboundUi(it, conversationId) }
                .let { dedupeMessagesByClientId(it) }
                .let { sortMessagesForChatDisplay(it) }
        }
    }

    private fun enrichQueuedOutboundUi(
        messages: List<Message>,
        conversationId: String,
    ): List<Message> {
        if (messages.none { it.id < 0 }) return messages
        val iid = instanceId()
        val attachmentOutbox = db.messageDatabaseQueries
            .selectPendingOutboxForInstance(iid)
            .executeAsList()
            .filter {
                it.conversationId == conversationId &&
                    (
                        it.kind == OutgoingMessageCoordinator.KIND_SEND_DM_ATTACHMENT ||
                            it.kind == OutgoingMessageCoordinator.KIND_SEND_DM_ATTACHMENT_AWAITING_ACK
                        )
            }
        if (attachmentOutbox.isEmpty()) return messages
        val confirmedClientIds = messages
            .filter { it.id > 0 }
            .mapNotNull { it.client_message_id?.trim()?.takeIf { cid -> cid.isNotEmpty() } }
            .toSet()
        val payloads = attachmentOutbox.associate { row ->
            row.clientMessageId to runCatching {
                Triple(
                    outboxJson.decodeFromString<DmAttachmentOutboxPayload>(row.payloadJson),
                    row.bytesUploaded,
                    row.kind,
                )
            }.getOrNull()
        }
        return messages.mapNotNull { msg ->
            if (msg.id >= 0) return@mapNotNull msg
            val cid = msg.client_message_id?.trim().orEmpty()
            if (cid.isNotEmpty() && cid in confirmedClientIds) return@mapNotNull null
            if (cid.isEmpty()) return@mapNotNull msg
            val entry = payloads[cid] ?: return@mapNotNull msg
            val (payload, bytesUploaded, kind) = entry
            val uploadFinished = kind == OutgoingMessageCoordinator.KIND_SEND_DM_ATTACHMENT_AWAITING_ACK
            val totalBytes = when {
                payload.encryptedFileSizeBytes > 0L -> payload.encryptedFileSizeBytes
                else -> payload.fileSizeBytes
            }
            val percent = when {
                uploadFinished -> null
                totalBytes > 0L && bytesUploaded > 0L ->
                    ((bytesUploaded.toDouble() / totalBytes.toDouble()) * 100.0).toInt().coerceIn(0, 99)
                bytesUploaded > 0L -> 1
                else -> msg.uploadProgress ?: 0
            }
            msg.copy(
                pendingFileUri = payload.fileUri,
                pendingFilename = payload.filename,
                pendingFileAspectRatio = payload.aspectRatio?.takeIf { it > 0f }
                    ?: msg.pendingFileAspectRatio,
                uploadJobId = cid,
                uploadProgress = percent,
            )
        }
    }

    private fun purgeSupersededPendingRows(
        instanceId: String,
        conversationId: String,
        before: List<Message>,
        after: List<Message>,
    ) {
        val keptIds = after.map { it.id }.toSet()
        before.filter { it.id < 0 && it.id !in keptIds }.forEach { dropped ->
            val cid = dropped.client_message_id?.trim().orEmpty()
            if (cid.isNotEmpty()) {
                db.messageDatabaseQueries.deletePendingMessageByClientMessageId(
                    instanceId,
                    conversationId,
                    cid,
                )
                db.messageDatabaseQueries.deleteOutboxItem(instanceId, cid)
            }
        }
    }

    private fun DbMessage.toAppMessage(): Message {
        val uid = userId.toInt()
        val self = ApiClient.user
        val profile = ProfileCache.get(uid)
        val usernameResolved = when {
            self != null && uid == self.id -> self.username
            else -> profile?.username?.takeIf { it.isNotBlank() }
                ?: profile?.displayName?.takeIf { it.isNotBlank() }
                ?: ""
        }
        val pictureResolved = when {
            self != null && uid == self.id -> self.profile_picture
            else -> profile?.profilePicture?.takeIf { it.isNotBlank() }
        }
        val parsed = parseDmMessageContent(content)
        val base = Message(
            id = id.toInt(),
            user_id = uid,
            content = parsed.text,
            timestamp = timestamp,
            is_read = isRead != 0L,
            is_edited = isEdited != 0L,
            username = usernameResolved,
            profile_picture = pictureResolved,
            verified = profile?.verified,
            reply_to = null,
            client_message_id = clientMessageId,
            reactions = null,
            files = parsed.envelope?.files,
            dmEnvelope = parsed.envelope,
            fileThumbnails = parsed.fileThumbnails,
            fileAspectRatios = parsed.fileAspectRatios,
            fileSizes = parsed.fileSizes,
            fileDimensions = parsed.fileDimensions,
            isContentCorrupted = parsed.isContentCorrupted,
        )
        return base.copy(
            pendingFileUri = parsed.localPreviewUri ?: resolveLocalPreviewUri(base),
            pendingFileAspectRatio = parsed.fileAspectRatios?.firstOrNull()
                ?: parsed.fileDimensions?.firstOrNull()?.let { (w, h) ->
                    aspectRatioFromDimensionPair(w, h)
                },
        )
    }

    suspend fun clearAll() {
        withContext(Dispatchers.Default) {
            db.messageDatabaseQueries.purgeAllCache()
        }
    }

    private fun validatedOrEmpty(conversationId: String, messages: List<Message>): List<Message> {
        val self = ApiClient.user?.id
        if (!CacheValidator.isConversationCacheCoherent(conversationId, messages, self)) {
            return emptyList()
        }
        return CacheValidator.filterMessages(conversationId, messages, self)
    }

    private suspend fun replaceMessages(conversationId: String, messages: List<Message>) {
        val self = ApiClient.user?.id
        if (!CacheValidator.isConversationCacheCoherent(conversationId, messages, self)) {
            clearConversationMessages(conversationId)
            return
        }
        val validated = CacheValidator.filterMessages(conversationId, messages, self)
        val iid = instanceId()
        withContext(Dispatchers.Default) {
            db.messageDatabaseQueries.transaction {
                db.messageDatabaseQueries.deleteMessagesForConversation(iid, conversationId)
                validated.forEach { msg ->
                    db.messageDatabaseQueries.upsertMessage(
                        instanceId = iid,
                        id = msg.id.toLong(),
                        conversationId = conversationId,
                        userId = msg.user_id.toLong(),
                        content = when {
                            msg.id < 0 -> msg.content
                            !msg.files.isNullOrEmpty() && msg.dmEnvelope != null ->
                                encodePersistedDmMessage(msg)
                            else -> msg.content
                        },
                        timestamp = msg.timestamp,
                        isRead = if (msg.is_read) 1L else 0L,
                        isEdited = if (msg.is_edited) 1L else 0L,
                        replyToId = msg.reply_to?.id?.toLong(),
                        clientMessageId = msg.client_message_id,
                        deletedFlag = 0L,
                        sendStatus = if (msg.id < 0) "pending" else "sent"
                    )
                }
            }
        }
        dmOtherUserIdFromConversationId(conversationId)?.let { syncDmConversationPreviewFromCache(it) }
    }

    suspend fun hasSentMessageWithClientId(conversationId: String, clientMessageId: String): Boolean {
        val cid = clientMessageId.trim()
        if (cid.isEmpty()) return false
        val iid = instanceId()
        return withContext(Dispatchers.Default) {
            db.messageDatabaseQueries
                .selectSentMessageIdByClientMessageId(iid, conversationId, cid)
                .executeAsOneOrNull() != null
        }
    }
}
