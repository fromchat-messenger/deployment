package ru.fromchat.core.cache

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class)
private fun uploadDir(instanceId: String): String {
    val url = NSFileManager.defaultManager.URLForDirectory(
        directory = NSCachesDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    ) ?: return ""
    val safe = instanceId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    val path = url.path + "/fromchat/instances/$safe/uploads"
    NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
    return path
}

@OptIn(ExperimentalForeignApi::class)
private fun blobPath(instanceId: String, clientMessageId: String): String {
    val safeId = clientMessageId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    return "${uploadDir(instanceId)}/$safeId.enc"
}

@OptIn(ExperimentalForeignApi::class)
private fun cipherPath(instanceId: String, clientMessageId: String): String {
    val safeId = clientMessageId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    return "${uploadDir(instanceId)}/$safeId.cipher.json"
}

private fun sourcePath(instanceId: String, clientMessageId: String): String {
    val safeId = clientMessageId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    return "${uploadDir(instanceId)}/$safeId.source"
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun readBytesAtPath(path: String): ByteArray? {
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
    val data = NSData.create(contentsOfFile = path) ?: return null
    val length = data.length.toInt()
    if (length == 0) return null
    val bytesPtr = data.bytes ?: return null
    val bytePtr = bytesPtr.reinterpret<ByteVar>()
    return ByteArray(length) { i -> bytePtr[i] }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun writeBytesAtPath(path: String, bytes: ByteArray) {
    bytes.usePinned { pinned ->
        val data = NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        data?.writeToFile(path, true)
    }
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun stageOutboundFileForUpload(
    instanceId: String,
    clientMessageId: String,
    sourceUri: String,
): StagedOutboundFile = withContext(Dispatchers.Default) {
    val dest = sourcePath(instanceId, clientMessageId)
    if (sourceUri != dest) {
        val existing = readBytesAtPath(dest)
        if (existing == null || existing.isEmpty()) {
            val bytes = readOutboundFileBytes(sourceUri)
            if (bytes.isEmpty()) {
                throw OutboundFileUnavailableException("File is empty or unavailable")
            }
            writeBytesAtPath(dest, bytes)
        }
    }
    val size = readBytesAtPath(dest)?.size?.toLong() ?: 0L
    StagedOutboundFile(uri = dest, sizeBytes = size)
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun readOutboundFileBytes(fileUri: String): ByteArray =
    withContext(Dispatchers.Default) {
        val url = NSURL.URLWithString(fileUri) ?: throw OutboundFileUnavailableException("Invalid file URI")
        val data = NSData.dataWithContentsOfURL(url)
            ?: throw OutboundFileUnavailableException("Failed to read file from URI")
        val length = data.length.toInt()
        val bytesPtr = data.bytes ?: error("Empty file")
        val bytePtr = bytesPtr.reinterpret<ByteVar>()
        ByteArray(length) { i -> bytePtr[i] }
    }

@OptIn(ExperimentalForeignApi::class)
actual suspend fun saveEncryptedUploadBlob(instanceId: String, clientMessageId: String, bytes: ByteArray) {
    withContext(Dispatchers.Default) {
        writeBytesAtPath(blobPath(instanceId, clientMessageId), bytes)
    }
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun loadEncryptedUploadBlob(instanceId: String, clientMessageId: String): ByteArray? =
    withContext(Dispatchers.Default) {
        readBytesAtPath(blobPath(instanceId, clientMessageId))
    }

@OptIn(ExperimentalForeignApi::class)
actual suspend fun saveUploadTransportCipherJson(instanceId: String, clientMessageId: String, json: String) {
    withContext(Dispatchers.Default) {
        writeBytesAtPath(cipherPath(instanceId, clientMessageId), json.encodeToByteArray())
    }
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun loadUploadTransportCipherJson(instanceId: String, clientMessageId: String): String? =
    withContext(Dispatchers.Default) {
        readBytesAtPath(cipherPath(instanceId, clientMessageId))?.decodeToString()?.takeIf { it.isNotBlank() }
    }

@OptIn(ExperimentalForeignApi::class)
actual suspend fun clearUploadArtifacts(instanceId: String, clientMessageId: String) {
    withContext(Dispatchers.Default) {
        NSFileManager.defaultManager.removeItemAtPath(blobPath(instanceId, clientMessageId), null)
        NSFileManager.defaultManager.removeItemAtPath(cipherPath(instanceId, clientMessageId), null)
        NSFileManager.defaultManager.removeItemAtPath(sourcePath(instanceId, clientMessageId), null)
    }
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun clearUploadSecretsOnly(instanceId: String, clientMessageId: String) {
    withContext(Dispatchers.Default) {
        NSFileManager.defaultManager.removeItemAtPath(blobPath(instanceId, clientMessageId), null)
        NSFileManager.defaultManager.removeItemAtPath(cipherPath(instanceId, clientMessageId), null)
    }
}
