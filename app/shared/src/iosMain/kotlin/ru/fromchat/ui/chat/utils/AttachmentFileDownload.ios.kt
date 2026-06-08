@file:OptIn(ExperimentalForeignApi::class)

package ru.fromchat.ui.chat.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSDownloadsDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.darwin.NSObject
import ru.fromchat.api.local.download.sanitizeExportFilename
import ru.fromchat.utils.iosTopViewController

@Composable
actual fun rememberCreateDownloadDestinationLauncher(
    onDestination: (String?) -> Unit,
): (filename: String, mimeType: String) -> Unit {
    var activeDelegate by remember { mutableStateOf<SaveDestinationPickerDelegate?>(null) }

    val launcher: (String, String) -> Unit = launcher@{ filename, _ ->
        val host = iosTopViewController()
        if (host == null) {
            onDestination(null)
            return@launcher
        }
        val stagingPath = writePickerStagingPlaceholder(filename)
        if (stagingPath == null) {
            onDestination(null)
            return@launcher
        }
        val fileUrl = NSURL.fileURLWithPath(stagingPath)
        val picker = UIDocumentPickerViewController(
            forExportingURLs = listOf(fileUrl),
            asCopy = true,
        )
        defaultDownloadsDirectoryUrl()?.let { picker.directoryURL = it }
        val delegate = SaveDestinationPickerDelegate(stagingPath) { uri ->
            activeDelegate = null
            onDestination(uri)
        }
        activeDelegate = delegate
        picker.delegate = delegate
        host.presentViewController(picker, animated = true, completion = null)
    }
    return remember(onDestination) { launcher }
}

actual suspend fun persistExportUriPermissionIfNeeded(exportUri: String) {
    // iOS export URIs are file URLs; no persistable permission grant.
}

private fun defaultDownloadsDirectoryUrl(): NSURL? {
    val manager = NSFileManager.defaultManager
    return manager.URLForDirectory(
        NSDownloadsDirectory,
        NSUserDomainMask,
        null,
        false,
        null,
    ) ?: manager.URLForDirectory(
        NSDocumentDirectory,
        NSUserDomainMask,
        null,
        false,
        null,
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun writePickerStagingPlaceholder(filename: String): String? {
    val caches = NSSearchPathForDirectoriesInDomains(
        NSCachesDirectory,
        NSUserDomainMask,
        true,
    ).filterIsInstance<String>().firstOrNull().orEmpty()
    val dir = "$caches/save_export_pick"
    NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
    val path = "$dir/${sanitizeExportFilename(filename)}"
    val placeholder = ByteArray(0)
    val nsData = placeholder.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = 0u)
    } ?: return null
    return if (nsData.writeToFile(path, true)) path else null
}

private class SaveDestinationPickerDelegate(
    private val stagingPath: String,
    private val onFinished: (String?) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {

    private var finished = false

    private fun finish(uri: String?) {
        if (finished) return
        finished = true
        NSFileManager.defaultManager.removeItemAtPath(stagingPath, null)
        onFinished(uri)
    }

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        finish(url?.absoluteString)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        finish(null)
    }
}
