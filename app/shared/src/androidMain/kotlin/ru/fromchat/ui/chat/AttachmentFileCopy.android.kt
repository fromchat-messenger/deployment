package ru.fromchat.ui.chat

import android.net.Uri
import com.pr0gramm3r101.utils.UtilsLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.fromchat.api.local.workers.AttachmentFileCopyForeground
import java.io.File
import java.io.FileInputStream

internal suspend fun copyCachedFileToDestinationUri(
    sourceCacheUri: String,
    destinationUri: String,
    storageKey: String,
    displayFilename: String?,
): Boolean = withContext(Dispatchers.IO) {
    val sourceFile = uriToLocalCacheFile(sourceCacheUri) ?: return@withContext false
    if (!sourceFile.isFile || sourceFile.length() <= 0L) return@withContext false
    AttachmentFileCopyForeground.onCopyStarted(storageKey, displayFilename)
    try {
        runCatching {
            val dest = Uri.parse(destinationUri)
            UtilsLibrary.context.contentResolver.openOutputStream(dest, "w")?.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out, bufferSize = 256 * 1024)
                    out.flush()
                }
            } != null && sourceFile.length() > 0L
        }.getOrDefault(false)
    } finally {
        AttachmentFileCopyForeground.onCopyFinished(storageKey)
    }
}

internal fun uriToLocalCacheFile(cacheUri: String): File? {
    val path = when {
        cacheUri.startsWith("file://") -> Uri.parse(cacheUri).path
        else -> cacheUri
    }?.trim().orEmpty()
    if (path.isEmpty()) return null
    val file = File(path)
    return file.takeIf { it.isFile }
}
