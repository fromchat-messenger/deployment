package ru.fromchat.ui.chat

import com.pr0gramm3r101.utils.files.PlatformFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.fromchat.api.DmEnvelope
import ru.fromchat.api.DmFile
import ru.fromchat.crypto.decryptFile

/**
 * Disk cache for decrypted image bytes. Key includes messageId, fileIndex, and file.path
 * so that server updates (e.g. image replacement) produce cache misses via path change.
 */
object DecryptedImageCache {
    private var cacheDir: String? = null
    private val mutex = Mutex()

    fun init(cacheDirPath: String) {
        cacheDir = cacheDirPath
    }

    private fun ensureCacheDir(): String? {
        if (cacheDir == null) {
            val base = PlatformFileSystem.getAppCacheDirectory()
            if (base.isEmpty()) return null
            cacheDir = PlatformFileSystem.ensureDirectory("$base/decrypted_images")
        }
        return cacheDir
    }

    private fun key(messageId: Int, fileIndex: Int, filePath: String): String {
        val safePath = filePath.hashCode().toString(36).replace("-", "m")
        return "img_${messageId}_${fileIndex}_$safePath"
    }

    fun getCached(messageId: Int, fileIndex: Int, filePath: String): String? {
        val dir = ensureCacheDir() ?: return null
        val path = "$dir/${key(messageId, fileIndex, filePath)}"
        return if (PlatformFileSystem.exists(path)) "file://$path" else null
    }

    suspend fun getOrDecrypt(
        messageId: Int,
        fileIndex: Int,
        file: DmFile,
        envelope: DmEnvelope?,
        currentUserId: Int?
    ): String? {
        if (envelope == null) return null
        val dir = ensureCacheDir() ?: return null
        val k = key(messageId, fileIndex, file.path)
        val path = "$dir/$k"

        mutex.withLock {
            if (PlatformFileSystem.exists(path)) return "file://$path"
        }

        val bytes = runCatching {
            withContext(Dispatchers.Default) {
                decryptFile(file, envelope, currentUserId)
            }
        }.getOrNull() ?: return null

        mutex.withLock {
            PlatformFileSystem.writeBytes(path, bytes)
        }
        return "file://$path"
    }

    suspend fun invalidateForMessage(messageId: Int) {
        val dir = ensureCacheDir() ?: return
        withContext(Dispatchers.Default) {
            PlatformFileSystem.deleteFilesWithPrefix(dir, "img_${messageId}_")
        }
    }

    suspend fun invalidateForFile(messageId: Int, fileIndex: Int, filePath: String) {
        val dir = ensureCacheDir() ?: return
        val path = "$dir/${key(messageId, fileIndex, filePath)}"
        withContext(Dispatchers.Default) {
            PlatformFileSystem.delete(path)
        }
    }
}
