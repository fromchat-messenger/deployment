package ru.fromchat.api.outbox

import com.pr0gramm3r101.utils.crypto.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.fromchat.api.ApiClient
import ru.fromchat.api.AttachmentUploadNotifier
import ru.fromchat.api.AttachmentUploadProgress
import ru.fromchat.api.DmUploadCompleteResponse
import ru.fromchat.api.SendDmFile
import ru.fromchat.api.db.MessageCacheStore
import ru.fromchat.api.db.MessageDatabaseProvider
import ru.fromchat.api.db.conversationIdForDm
import ru.fromchat.core.cache.OutboundFileUnavailableException
import ru.fromchat.core.cache.clearUploadSecretsOnly
import ru.fromchat.api.optimisticMessageIdForClientMessageId
import ru.fromchat.core.cache.isOutboundFileUnavailable
import ru.fromchat.ui.chat.AttachmentMediaLog
import ru.fromchat.ui.chat.clearOutboundImageCaches
import ru.fromchat.core.cache.loadEncryptedUploadBlob
import ru.fromchat.core.cache.loadUploadTransportCipherJson
import ru.fromchat.core.cache.readOutboundFileBytes
import ru.fromchat.core.cache.saveEncryptedUploadBlob
import ru.fromchat.core.cache.saveUploadTransportCipherJson
import ru.fromchat.core.cache.stageOutboundFileForUpload
import ru.fromchat.crypto.transport.TransportCiphertext
import ru.fromchat.crypto.transport.TransportCrypto
import ru.fromchat.db.Outbox

private const val INLINE_UPLOAD_THRESHOLD_BYTES = 512 * 1024
private const val DEFAULT_CHUNK_SIZE = 262_144

@Serializable
private data class StoredTransportCipher(
    val clientPublicKeyB64: String,
    val nonceB64: String,
    val ciphertextB64: String,
)

object DmAttachmentOutboxHandler {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun outboxRowExists(instanceId: String, clientMessageId: String): Boolean =
        MessageDatabaseProvider.database.messageDatabaseQueries
            .selectOutboxItem(instanceId, clientMessageId)
            .executeAsOneOrNull() != null

    private fun ensureStillQueued(instanceId: String, clientMessageId: String) {
        if (!outboxRowExists(instanceId, clientMessageId)) {
            AttachmentMediaLog.upload("cancelled_in_flight", "job" to clientMessageId)
            throw kotlinx.coroutines.CancellationException("Upload cancelled")
        }
    }

    suspend fun process(row: Outbox): Boolean {
        if (row.kind != OutgoingMessageCoordinator.KIND_SEND_DM_ATTACHMENT) return false
        val instanceId = row.instanceId
        val payload = json.decodeFromString<DmAttachmentOutboxPayload>(row.payloadJson)
        val clientMessageId = payload.clientMessageId.trim()
        if (clientMessageId.isEmpty() || payload.recipientId <= 0) return true
        if (!outboxRowExists(instanceId, clientMessageId)) return true

        val conversationId = row.conversationId.ifBlank {
            conversationIdForDm(payload.recipientId)
        }
        if (MessageCacheStore.hasSentMessageWithClientId(conversationId, clientMessageId)) {
            withContext(Dispatchers.Default) {
                MessageDatabaseProvider.database.messageDatabaseQueries.deleteOutboxItem(
                    instanceId,
                    clientMessageId,
                )
                MessageDatabaseProvider.database.messageDatabaseQueries.deletePendingMessageByClientMessageId(
                    instanceId,
                    conversationId,
                    clientMessageId,
                )
            }
            clearPrepared(instanceId, clientMessageId)
            clearUploadSecretsOnly(instanceId, clientMessageId)
            return true
        }

        val serverUploadId = arrayOf(payload.uploadId.trim())
        return runCatching {
            ensureStillQueued(instanceId, clientMessageId)
            AttachmentUploadNotifier.emit(
                AttachmentUploadProgress.Pending(clientMessageId, payload.filename),
                messageLabel = payload.plaintext,
            )
            val stagedPayload = ensureStagedPayload(instanceId, row, payload)
            ensureStillQueued(instanceId, clientMessageId)
            val restoredPercent = uploadPercent(row.bytesUploaded, stagedPayload)
            if (restoredPercent > 0) {
                emitProgress(clientMessageId, restoredPercent, stagedPayload.filename, stagedPayload.plaintext)
            } else {
                emitProgress(clientMessageId, 0, stagedPayload.filename, stagedPayload.plaintext)
            }

            val prepared = loadPrepared(instanceId, clientMessageId)
            val encryptedBlob: ByteArray
            val msgCipher: TransportCiphertext
            var activePayload = stagedPayload

            if (prepared != null) {
                encryptedBlob = prepared.first
                msgCipher = prepared.second
                if (activePayload.encryptedFileSizeBytes <= 0L) {
                    activePayload = activePayload.copy(encryptedFileSizeBytes = encryptedBlob.size.toLong())
                }
            } else {
                ensureStillQueued(instanceId, clientMessageId)
                val bytes = readOutboundFileBytes(stagedPayload.fileUri)
                OutgoingMessageCoordinator.abortDmServerUploadIfNeeded(stagedPayload.uploadId)
                serverUploadId[0] = ""
                activePayload = stagedPayload.copy(uploadId = "", encryptedFileSizeBytes = 0L)
                persistPayloadProgress(instanceId, row, activePayload, bytesUploaded = 0L)
                ensureStillQueued(instanceId, clientMessageId)
                val transportKey = ApiClient.getTransportPublicKey()
                val (freshCipher, ephemeralSecret) = TransportCrypto.encryptWithTransportKeyWithEphemeralSecret(
                    plaintext = stagedPayload.plaintext,
                    transportPublicKeyB64 = transportKey.publicKeyB64,
                )
                try {
                    val blob = TransportCrypto.encryptFileForTransport(
                        fileBytes = bytes,
                        transportPublicKeyB64 = transportKey.publicKeyB64,
                        ephemeralSecretKey = ephemeralSecret,
                    )
                    encryptedBlob = blob
                    msgCipher = freshCipher
                    savePrepared(instanceId, clientMessageId, encryptedBlob, msgCipher)
                    activePayload = activePayload.copy(encryptedFileSizeBytes = encryptedBlob.size.toLong())
                    persistPayloadProgress(instanceId, row, activePayload, bytesUploaded = 0L)
                } finally {
                    ephemeralSecret.fill(0)
                }
            }

            ensureStillQueued(instanceId, clientMessageId)
            if (encryptedBlob.size <= INLINE_UPLOAD_THRESHOLD_BYTES) {
                sendInline(activePayload, encryptedBlob, msgCipher)
            } else {
                sendResumable(
                    instanceId = instanceId,
                    row = row,
                    payload = activePayload,
                    encryptedBlob = encryptedBlob,
                    msgCipher = msgCipher,
                    serverUploadId = serverUploadId,
                )
            }
            ensureStillQueued(instanceId, clientMessageId)

            clearPrepared(instanceId, clientMessageId)
            withContext(Dispatchers.Default) {
                MessageDatabaseProvider.database.messageDatabaseQueries.upsertOutbox(
                    instanceId = instanceId,
                    clientMessageId = clientMessageId,
                    conversationId = row.conversationId,
                    kind = OutgoingMessageCoordinator.KIND_SEND_DM_ATTACHMENT_AWAITING_ACK,
                    payloadJson = row.payloadJson,
                    retryCount = row.retryCount,
                    nextAttemptAt = null,
                    bytesUploaded = row.bytesUploaded,
                )
            }
            AttachmentUploadNotifier.emit(
                AttachmentUploadProgress.Success(clientMessageId),
                messageLabel = payload.plaintext,
            )
            true
        }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) {
                OutgoingMessageCoordinator.abortDmServerUploadIfNeeded(serverUploadId[0])
                return true
            }
            if (error.isOutboundFileUnavailable()) {
                AttachmentUploadNotifier.emit(
                    AttachmentUploadProgress.Failed(
                        jobId = clientMessageId,
                        error = error.message ?: "Attachment unavailable",
                    ),
                    messageLabel = payload.plaintext,
                )
                runCatching {
                    clearOutboundImageCaches(
                        clientMessageId,
                        optimisticMessageIdForClientMessageId(clientMessageId),
                    )
                    OutgoingMessageCoordinator.cancelOutboundMessage(clientMessageId, row.conversationId)
                }
                return true
            }
            AttachmentUploadNotifier.emit(
                AttachmentUploadProgress.Failed(
                    jobId = clientMessageId,
                    error = error.message ?: "Upload failed",
                ),
                messageLabel = payload.plaintext,
            )
            false
        }
    }

    private suspend fun sendInline(
        payload: DmAttachmentOutboxPayload,
        encryptedBlob: ByteArray,
        msgCipher: TransportCiphertext,
    ) {
        val file = SendDmFile(
            encryptedFileDataB64 = Base64.encode(encryptedBlob),
            filename = payload.filename,
            fileSize = encryptedBlob.size.toLong(),
        )
        ApiClient.sendDm(
            recipientId = payload.recipientId,
            plaintext = payload.plaintext,
            clientMessageId = payload.clientMessageId,
            replyToId = payload.replyToId,
            transportFiles = listOf(file),
            preparedTransport = msgCipher,
        )
    }

    private suspend fun sendResumable(
        instanceId: String,
        row: Outbox,
        payload: DmAttachmentOutboxPayload,
        encryptedBlob: ByteArray,
        msgCipher: TransportCiphertext,
        serverUploadId: Array<String>,
    ) {
        var uploadId = payload.uploadId.trim().ifBlank { serverUploadId[0] }
        try {
            if (uploadId.isEmpty()) {
                val init = ApiClient.initDmUpload(
                    filename = payload.filename,
                    totalSize = encryptedBlob.size.toLong(),
                    recipientId = payload.recipientId,
                    chunkSize = DEFAULT_CHUNK_SIZE,
                )
                uploadId = init.uploadId
                serverUploadId[0] = uploadId
                persistPayloadProgress(instanceId, row, payload.copy(uploadId = uploadId), row.bytesUploaded)
            } else {
                serverUploadId[0] = uploadId
            }

            var offset = row.bytesUploaded.toInt().coerceAtLeast(0)
            val serverOffset = ApiClient.getDmUploadStatus(uploadId).offset.toInt().coerceAtLeast(0)
            offset = maxOf(offset, serverOffset)
            while (offset < encryptedBlob.size) {
                ensureStillQueued(instanceId, payload.clientMessageId)
                val nextOffset = minOf(offset + DEFAULT_CHUNK_SIZE, encryptedBlob.size)
                val chunk = encryptedBlob.copyOfRange(offset, nextOffset)
                ApiClient.uploadDmChunk(
                    uploadId = uploadId,
                    offset = offset.toLong(),
                    dataB64 = Base64.encode(chunk),
                )
                offset = nextOffset
                val percent = ((offset.toDouble() / encryptedBlob.size.toDouble()) * 100.0).toInt()
                emitProgress(payload.clientMessageId, percent, payload.filename, payload.plaintext)
                persistPayloadProgress(
                    instanceId,
                    row,
                    payload.copy(uploadId = uploadId),
                    offset.toLong(),
                )
            }

            val completed: DmUploadCompleteResponse = ApiClient.completeDmUpload(uploadId)
            ApiClient.sendDm(
                recipientId = payload.recipientId,
                plaintext = payload.plaintext,
                clientMessageId = payload.clientMessageId,
                replyToId = payload.replyToId,
                uploadedFileIds = listOf(completed.fileId),
                preparedTransport = msgCipher,
            )
            serverUploadId[0] = ""
        } catch (e: kotlinx.coroutines.CancellationException) {
            OutgoingMessageCoordinator.abortDmServerUploadIfNeeded(uploadId)
            serverUploadId[0] = ""
            throw e
        }
    }

    private suspend fun ensureStagedPayload(
        instanceId: String,
        row: Outbox,
        payload: DmAttachmentOutboxPayload,
    ): DmAttachmentOutboxPayload {
        val staged = stageOutboundFileForUpload(instanceId, payload.clientMessageId, payload.fileUri)
        if (staged.sizeBytes <= 0L) {
            throw OutboundFileUnavailableException("Attachment file is empty or unavailable")
        }
        if (staged.uri == payload.fileUri && staged.sizeBytes == payload.fileSizeBytes) {
            return payload
        }
        val updated = payload.copy(fileUri = staged.uri, fileSizeBytes = staged.sizeBytes)
        persistPayloadProgress(instanceId, row, updated, row.bytesUploaded)
        return updated
    }

    private suspend fun persistPayloadProgress(
        instanceId: String,
        row: Outbox,
        payload: DmAttachmentOutboxPayload,
        bytesUploaded: Long,
    ) {
        if (!outboxRowExists(instanceId, row.clientMessageId)) return
        withContext(Dispatchers.Default) {
            if (!outboxRowExists(instanceId, row.clientMessageId)) return@withContext
            MessageDatabaseProvider.database.messageDatabaseQueries.upsertOutbox(
                instanceId = instanceId,
                clientMessageId = row.clientMessageId,
                conversationId = row.conversationId,
                kind = row.kind,
                payloadJson = json.encodeToString(payload),
                retryCount = row.retryCount,
                nextAttemptAt = row.nextAttemptAt,
                bytesUploaded = bytesUploaded,
            )
        }
    }

    private suspend fun loadPrepared(
        instanceId: String,
        clientMessageId: String,
    ): Pair<ByteArray, TransportCiphertext>? {
        val blob = loadEncryptedUploadBlob(instanceId, clientMessageId) ?: return null
        val raw = loadUploadTransportCipherJson(instanceId, clientMessageId) ?: return null
        return runCatching {
            val stored = json.decodeFromString<StoredTransportCipher>(raw)
            val cipher = TransportCiphertext(
                clientPublicKeyB64 = stored.clientPublicKeyB64,
                nonceB64 = stored.nonceB64,
                ciphertextB64 = stored.ciphertextB64,
            )
            blob to cipher
        }.getOrNull()
    }

    private suspend fun savePrepared(
        instanceId: String,
        clientMessageId: String,
        blob: ByteArray,
        cipher: TransportCiphertext,
    ) {
        saveEncryptedUploadBlob(instanceId, clientMessageId, blob)
        val stored = StoredTransportCipher(
            clientPublicKeyB64 = cipher.clientPublicKeyB64,
            nonceB64 = cipher.nonceB64,
            ciphertextB64 = cipher.ciphertextB64,
        )
        saveUploadTransportCipherJson(instanceId, clientMessageId, json.encodeToString(stored))
    }

    private suspend fun clearPrepared(instanceId: String, clientMessageId: String) {
        clearUploadSecretsOnly(instanceId, clientMessageId)
    }

    private fun uploadPercent(bytesUploaded: Long, payload: DmAttachmentOutboxPayload): Int {
        val total = when {
            payload.encryptedFileSizeBytes > 0L -> payload.encryptedFileSizeBytes
            payload.fileSizeBytes > 0L -> payload.fileSizeBytes
            else -> 0L
        }
        if (total <= 0L || bytesUploaded <= 0L) return 0
        return ((bytesUploaded.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 99)
    }

    private fun emitProgress(
        jobId: String,
        percent: Int,
        filename: String? = null,
        messageLabel: String? = null,
    ) {
        AttachmentUploadNotifier.emit(
            AttachmentUploadProgress.InProgress(
                jobId = jobId,
                percent = percent.coerceIn(0, 100),
                filename = filename,
            ),
            messageLabel = messageLabel,
        )
    }

    suspend fun hasPendingAttachmentWork(instanceId: String): Boolean =
        withContext(Dispatchers.Default) {
            MessageDatabaseProvider.database.messageDatabaseQueries
                .selectPendingOutboxForInstance(instanceId)
                .executeAsList()
                .any { it.kind == OutgoingMessageCoordinator.KIND_SEND_DM_ATTACHMENT }
        }
}
