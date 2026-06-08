package ru.fromchat.api.local.cache

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
actual suspend fun wipeFromChatCacheDirectory() {
    withContext(Dispatchers.Default) {
        val url = NSFileManager.defaultManager.URLForDirectory(
            directory = NSCachesDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = false,
            error = null,
        ) ?: return@withContext
        val path = url.path + "/fromchat"
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
}
