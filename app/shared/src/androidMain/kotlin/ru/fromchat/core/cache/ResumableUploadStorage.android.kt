package ru.fromchat.core.cache

import android.net.Uri
import com.pr0gramm3r101.utils.UtilsLibrary
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun uploadDir(instanceId: String): File {
    val safe = instanceId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    return File(UtilsLibrary.context.cacheDir, "fromchat/instances/$safe/uploads").apply { mkdirs() }
}

private fun blobFile(instanceId: String, clientMessageId: String): File {
    val safeId = clientMessageId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    return File(uploadDir(instanceId), "$safeId.enc")
}

private fun cipherFile(instanceId: String, clientMessageId: String): File {
    val safeId = clientMessageId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    return File(uploadDir(instanceId), "$safeId.cipher.json")
}

private fun sourceFile(instanceId: String, clientMessageId: String): File {
    val safeId = clientMessageId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    return File(uploadDir(instanceId), "$safeId.source")
}

actual suspend fun stageOutboundFileForUpload(
    instanceId: String,
    clientMessageId: String,
    sourceUri: String,
): StagedOutboundFile = withContext(Dispatchers.IO) {
    val dest = sourceFile(instanceId, clientMessageId)
    val destUri = Uri.fromFile(dest).toString()
    if (sourceUri != destUri && sourceUri != dest.absolutePath) {
        if (!dest.isFile || dest.length() == 0L) {
            val input = when {
                sourceUri.startsWith("content://") || sourceUri.startsWith("file://") ->
                    UtilsLibrary.context.contentResolver.openInputStream(Uri.parse(sourceUri))
                else -> File(sourceUri).inputStream()
            } ?: throw OutboundFileUnavailableException("Failed to read file from URI")
            input.use { inputStream ->
                dest.outputStream().use { output -> inputStream.copyTo(output) }
            }
        }
    }
    StagedOutboundFile(uri = destUri, sizeBytes = dest.length().coerceAtLeast(0L))
}

actual suspend fun readOutboundFileBytes(fileUri: String): ByteArray =
    withContext(Dispatchers.IO) {
        when {
            fileUri.startsWith("content://") ->
                UtilsLibrary.context.contentResolver.openInputStream(Uri.parse(fileUri))?.use { it.readBytes() }
                    ?: throw OutboundFileUnavailableException("Failed to read file from URI")
            fileUri.startsWith("file://") -> {
                val path = Uri.parse(fileUri).path ?: throw OutboundFileUnavailableException("Invalid file URI")
                val file = File(path)
                if (!file.isFile) throw OutboundFileUnavailableException("File no longer exists")
                file.readBytes()
            }
            else -> {
                val file = File(fileUri)
                if (!file.isFile) throw OutboundFileUnavailableException("File no longer exists")
                file.readBytes()
            }
        }
    }

actual suspend fun saveEncryptedUploadBlob(instanceId: String, clientMessageId: String, bytes: ByteArray) {
    withContext(Dispatchers.IO) {
        blobFile(instanceId, clientMessageId).outputStream().use { it.write(bytes) }
    }
}

actual suspend fun loadEncryptedUploadBlob(instanceId: String, clientMessageId: String): ByteArray? =
    withContext(Dispatchers.IO) {
        val f = blobFile(instanceId, clientMessageId)
        if (!f.isFile || f.length() == 0L) null else f.readBytes()
    }

actual suspend fun saveUploadTransportCipherJson(instanceId: String, clientMessageId: String, json: String) {
    withContext(Dispatchers.IO) {
        cipherFile(instanceId, clientMessageId).writeText(json)
    }
}

actual suspend fun loadUploadTransportCipherJson(instanceId: String, clientMessageId: String): String? =
    withContext(Dispatchers.IO) {
        val f = cipherFile(instanceId, clientMessageId)
        if (!f.isFile) null else f.readText().takeIf { it.isNotBlank() }
    }

actual suspend fun clearUploadArtifacts(instanceId: String, clientMessageId: String) {
    withContext(Dispatchers.IO) {
        blobFile(instanceId, clientMessageId).delete()
        cipherFile(instanceId, clientMessageId).delete()
        sourceFile(instanceId, clientMessageId).delete()
    }
}

actual suspend fun clearUploadSecretsOnly(instanceId: String, clientMessageId: String) {
    withContext(Dispatchers.IO) {
        blobFile(instanceId, clientMessageId).delete()
        cipherFile(instanceId, clientMessageId).delete()
    }
}
