package ru.fromchat.core.cache

import com.pr0gramm3r101.utils.UtilsLibrary
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.fromchat.api.db.MessageDatabaseProvider
import ru.fromchat.core.cache.wipeFromChatCacheDirectory

private const val GENERATION_FILE = ".generation"

actual suspend fun ensureFromChatCacheGeneration() {
    withContext(Dispatchers.IO) {
        val root = File(UtilsLibrary.context.cacheDir, "fromchat")
        val marker = File(root, GENERATION_FILE)
        if (marker.isFile) return@withContext
        MessageDatabaseProvider.closeAndReset()
        wipeFromChatCacheDirectory()
        marker.parentFile?.mkdirs()
        marker.writeText("1\n")
    }
}

actual suspend fun writeFromChatCacheGeneration() {
    withContext(Dispatchers.IO) {
        val root = File(UtilsLibrary.context.cacheDir, "fromchat")
        root.mkdirs()
        File(root, GENERATION_FILE).writeText("1\n")
    }
}
