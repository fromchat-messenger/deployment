@file:OptIn(ExperimentalForeignApi::class)

package ru.fromchat.api.local.cache

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.Foundation.writeToURL
import ru.fromchat.api.local.download.cachedAttachmentFileSize

private val copyScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

internal actual fun enqueuePlatformCopy(storageKey: String) {
    copyScope.launch {
        val entry = PendingFileSaveRegistry.listPending()
            .firstOrNull { it.storageKey == storageKey } ?: return@launch
        val cacheUri = DecryptedFileCache.getCachedUriForStorageKey(storageKey) ?: return@launch
        if (cachedAttachmentFileSize(cacheUri) <= 0L) return@launch
        val bytes = runCatching { readOutboundFileBytes(cacheUri) }.getOrNull() ?: return@launch
        if (bytes.isEmpty()) return@launch
        val ok = writeBytesToDestinationUri(entry.destinationUri, bytes)
        if (ok) {
            PendingFileSaveRegistry.remove(storageKey)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeBytesToDestinationUri(destinationUri: String, bytes: ByteArray): Boolean {
    val url = NSURL.URLWithString(destinationUri)
        ?: NSURL.fileURLWithPath(destinationUri.removePrefix("file://"))
    val nsData = bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
    } ?: return false
    return nsData.writeToURL(url, true) || run {
        val path = url.path ?: return false
        nsData.writeToFile(path, true)
    }
}
