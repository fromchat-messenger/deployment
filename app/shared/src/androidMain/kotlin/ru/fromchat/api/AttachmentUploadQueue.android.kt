package ru.fromchat.api

import android.content.Context
import android.net.Uri
import java.io.File
import org.json.JSONObject
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pr0gramm3r101.utils.UtilsLibrary
import com.pr0gramm3r101.utils.crypto.Base64
import com.pr0gramm3r101.utils.settings.settings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import ru.fromchat.crypto.transport.TransportCiphertext
import ru.fromchat.crypto.transport.TransportCrypto

private const val INLINE_UPLOAD_THRESHOLD_BYTES = 512 * 1024
private const val DEFAULT_CHUNK_SIZE = 262_144

private fun dmBlobCacheFile(context: Context, jobId: String): File =
    File(context.cacheDir, "dm_resumable_$jobId.blob")

private fun dmTransportCipherPrefsKey(jobId: String): String = "dm_upload_transport_cipher_$jobId"

private object AttachmentUploadEvents {
    val flow = MutableSharedFlow<AttachmentUploadProgress>(extraBufferCapacity = 64)
}

actual object AttachmentUploadQueue {
    actual val progressFlow: SharedFlow<AttachmentUploadProgress> = AttachmentUploadEvents.flow

    actual fun enqueue(job: AttachmentUploadJob) {
        AttachmentUploadEvents.flow.tryEmit(
            AttachmentUploadProgress.Pending(
                jobId = job.jobId,
                filename = job.filename
            )
        )

        val request = OneTimeWorkRequestBuilder<DmAttachmentUploadWorker>()
            .setInputData(
                Data.Builder()
                    .putString(DmAttachmentUploadWorker.KEY_JOB_ID, job.jobId)
                    .putString(DmAttachmentUploadWorker.KEY_FILE_URI, job.fileUri)
                    .putString(DmAttachmentUploadWorker.KEY_FILENAME, job.filename)
                    .putInt(DmAttachmentUploadWorker.KEY_RECIPIENT_ID, job.recipientId)
                    .putString(DmAttachmentUploadWorker.KEY_PLAINTEXT, job.plaintext)
                    .build()
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(UtilsLibrary.context).enqueueUniqueWork(
            uniqueWorkName(job.jobId),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    actual fun cancel(jobId: String) {
        val ctx = UtilsLibrary.context
        dmBlobCacheFile(ctx, jobId).delete()
        runBlocking {
            settings.putString(dmTransportCipherPrefsKey(jobId), "")
        }
        WorkManager.getInstance(ctx).cancelUniqueWork(uniqueWorkName(jobId))
    }

    private fun uniqueWorkName(jobId: String): String = "dm-attachment-upload-$jobId"
}

class DmAttachmentUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_FILE_URI = "file_uri"
        const val KEY_FILENAME = "filename"
        const val KEY_RECIPIENT_ID = "recipient_id"
        const val KEY_PLAINTEXT = "plaintext"
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID)
            ?: return Result.failure()
        val fileUri = inputData.getString(KEY_FILE_URI)
            ?: return Result.failure()
        val filename = inputData.getString(KEY_FILENAME)
            ?: "file"
        val recipientId = inputData.getInt(KEY_RECIPIENT_ID, -1)
        val plaintext = inputData.getString(KEY_PLAINTEXT)?.trim().orEmpty()

        if (recipientId <= 0) return Result.failure()
        if (plaintext.isBlank()) return Result.failure()

        return runCatching {
            emitProgress(jobId, 0)

            val prepared = loadPreparedTransportAndBlob(applicationContext, jobId)
            val encryptedBlob: ByteArray
            val msgCipher: TransportCiphertext

            if (prepared != null) {
                encryptedBlob = prepared.first
                msgCipher = prepared.second
            } else {
                val staleUploadId = settings.getString(uploadIdKey(jobId), "").ifBlank { null }
                if (staleUploadId != null) {
                    runCatching { ApiClient.abortDmUpload(staleUploadId) }
                    settings.putString(uploadIdKey(jobId), "")
                }

                val transportKey = ApiClient.getTransportPublicKey()
                val (freshCipher, ephemeralSecret) = TransportCrypto.encryptWithTransportKeyWithEphemeralSecret(
                    plaintext = plaintext,
                    transportPublicKeyB64 = transportKey.publicKeyB64
                )
                try {
                    val bytes = applicationContext.contentResolver.openInputStream(Uri.parse(fileUri))?.use { it.readBytes() }
                        ?: error("Failed to read file from URI")
                    val blob = TransportCrypto.encryptFileForTransport(
                        fileBytes = bytes,
                        transportPublicKeyB64 = transportKey.publicKeyB64,
                        ephemeralSecretKey = ephemeralSecret
                    )
                    encryptedBlob = blob
                    msgCipher = freshCipher
                    savePreparedTransportAndBlob(applicationContext, jobId, encryptedBlob, msgCipher)
                } finally {
                    ephemeralSecret.fill(0)
                }
            }

            if (encryptedBlob.size <= INLINE_UPLOAD_THRESHOLD_BYTES) {
                sendInline(jobId, recipientId, plaintext, filename, encryptedBlob, msgCipher)
            } else {
                sendResumable(jobId, recipientId, plaintext, filename, encryptedBlob, msgCipher)
            }

            clearPreparedTransportAndBlob(applicationContext, jobId)
            clearResumableState(jobId)
            AttachmentUploadEvents.flow.tryEmit(AttachmentUploadProgress.Success(jobId))
            Result.success()
        }.getOrElse { error ->
            AttachmentUploadEvents.flow.tryEmit(
                AttachmentUploadProgress.Failed(
                    jobId = jobId,
                    error = error.message ?: "Upload failed"
                )
            )
            if (runAttemptCount >= 5) Result.failure() else Result.retry()
        }
    }

    private suspend fun sendInline(
        jobId: String,
        recipientId: Int,
        plaintext: String,
        filename: String,
        encryptedBlob: ByteArray,
        msgCipher: TransportCiphertext
    ) {
        val file = SendDmFile(
            encryptedFileDataB64 = Base64.encode(encryptedBlob),
            filename = filename,
            fileSize = encryptedBlob.size.toLong()
        )
        ApiClient.sendDm(
            recipientId = recipientId,
            plaintext = plaintext,
            clientMessageId = jobId,
            transportFiles = listOf(file),
            preparedTransport = msgCipher
        )
    }

    private suspend fun sendResumable(
        jobId: String,
        recipientId: Int,
        plaintext: String,
        filename: String,
        encryptedBlob: ByteArray,
        msgCipher: TransportCiphertext
    ) {
        val uploadId = settings.getString(uploadIdKey(jobId), "").ifBlank {
            val init = ApiClient.initDmUpload(
                filename = filename,
                totalSize = encryptedBlob.size.toLong(),
                recipientId = recipientId,
                chunkSize = DEFAULT_CHUNK_SIZE
            )
            settings.putString(uploadIdKey(jobId), init.uploadId)
            init.uploadId
        }

        var offset = ApiClient.getDmUploadStatus(uploadId).offset.toInt()
        while (offset < encryptedBlob.size) {
            val nextOffset = minOf(offset + DEFAULT_CHUNK_SIZE, encryptedBlob.size)
            val chunk = encryptedBlob.copyOfRange(offset, nextOffset)
            ApiClient.uploadDmChunk(
                uploadId = uploadId,
                offset = offset.toLong(),
                dataB64 = Base64.encode(chunk)
            )
            offset = nextOffset
            emitProgress(jobId, ((offset.toDouble() / encryptedBlob.size.toDouble()) * 100.0).toInt())
        }

        val completed = ApiClient.completeDmUpload(uploadId)
        ApiClient.sendDm(
            recipientId = recipientId,
            plaintext = plaintext,
            clientMessageId = jobId,
            uploadedFileIds = listOf(completed.fileId),
            preparedTransport = msgCipher
        )
    }

    private fun emitProgress(jobId: String, value: Int) {
        AttachmentUploadEvents.flow.tryEmit(
            AttachmentUploadProgress.InProgress(
                jobId = jobId,
                percent = value.coerceIn(0, 100)
            )
        )
    }

    private suspend fun clearResumableState(jobId: String) {
        settings.putString(uploadIdKey(jobId), "")
    }

    private fun uploadIdKey(jobId: String): String = "dm_upload_id_$jobId"

    private suspend fun savePreparedTransportAndBlob(
        context: Context,
        jobId: String,
        blob: ByteArray,
        cipher: TransportCiphertext
    ) {
        val f = dmBlobCacheFile(context, jobId)
        f.outputStream().use { it.write(blob) }
        val json = JSONObject().apply {
            put("clientPublicKeyB64", cipher.clientPublicKeyB64)
            put("nonceB64", cipher.nonceB64)
            put("ciphertextB64", cipher.ciphertextB64)
        }
        settings.putString(dmTransportCipherPrefsKey(jobId), json.toString())
    }

    private suspend fun loadPreparedTransportAndBlob(
        context: Context,
        jobId: String
    ): Pair<ByteArray, TransportCiphertext>? {
        val f = dmBlobCacheFile(context, jobId)
        val raw = settings.getString(dmTransportCipherPrefsKey(jobId), "").ifBlank { return null }
        if (!f.isFile || f.length() == 0L) return null
        return try {
            val o = JSONObject(raw)
            val cipher = TransportCiphertext(
                clientPublicKeyB64 = o.getString("clientPublicKeyB64"),
                nonceB64 = o.getString("nonceB64"),
                ciphertextB64 = o.getString("ciphertextB64")
            )
            f.readBytes() to cipher
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun clearPreparedTransportAndBlob(context: Context, jobId: String) {
        dmBlobCacheFile(context, jobId).delete()
        settings.putString(dmTransportCipherPrefsKey(jobId), "")
    }
}

