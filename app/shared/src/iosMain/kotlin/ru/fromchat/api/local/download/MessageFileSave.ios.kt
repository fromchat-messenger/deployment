@file:OptIn(ExperimentalForeignApi::class)

package ru.fromchat.api.local.download

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.darwin.NSObject
import ru.fromchat.api.local.cache.DecryptedFileCache
import ru.fromchat.api.local.cache.PendingFileSaveEntry
import ru.fromchat.api.local.cache.PendingFileSaveRegistry
import ru.fromchat.utils.iosTopViewController

@Composable
actual fun rememberPlatformSaveMessageFile(
    onComplete: (Boolean) -> Unit,
): (SavableMessageFile) -> Unit {
    val scope = rememberCoroutineScope()
    var pendingSavable by remember { mutableStateOf<SavableMessageFile?>(null) }
    val launcher: (SavableMessageFile) -> Unit = remember {
        { savable ->
            scope.launch {
                val destination = pickSaveDestination(savable.filename, savable.mimeType)
                val pending = pendingSavable
                pendingSavable = null
                if (destination == null || pending == null) {
                    onComplete(false)
                    return@launch
                }
                PendingFileSaveRegistry.schedule(
                    PendingFileSaveEntry(
                        storageKey = pending.storageKey,
                        destinationUri = destination,
                        filename = pending.filename,
                        mimeType = pending.mimeType,
                    ),
                )
                if (DecryptedFileCache.getCached(
                        messageId = DownloadedFileRegistry.messageIdFromStorageKey(pending.storageKey) ?: -1,
                        fileIndex = DownloadedFileRegistry.fileIndexFromStorageKey(pending.storageKey) ?: 0,
                    ) == null
                ) {
                    // Download will trigger copy when cache is ready.
                }
                onComplete(true)
            }
        }
    }
    return remember(launcher) {
        { savable: SavableMessageFile ->
            pendingSavable = savable
            launcher(savable)
        }
    }
}

private suspend fun pickSaveDestination(filename: String, mimeType: String): String? =
    suspendCancellableCoroutine { cont ->
        val host = iosTopViewController()
        if (host == null) {
            cont.resume(null) {}
            return@suspendCancellableCoroutine
        }
        val picker = UIDocumentPickerViewController(forExportingURLs = emptyList<NSURL>(), asCopy = true)
        val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(
                controller: UIDocumentPickerViewController,
                didPickDocumentsAtURLs: List<*>,
            ) {
                val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
                cont.resume(url?.absoluteString) {}
            }

            override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                cont.resume(null) {}
            }
        }
        picker.delegate = delegate
        host.presentViewController(picker, animated = true, completion = null)
    }
