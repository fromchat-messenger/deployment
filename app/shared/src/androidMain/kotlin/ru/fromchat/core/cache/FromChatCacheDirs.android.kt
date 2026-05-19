package ru.fromchat.core.cache

import com.pr0gramm3r101.utils.UtilsLibrary
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun wipeFromChatCacheDirectory() {
    withContext(Dispatchers.IO) {
        File(UtilsLibrary.context.cacheDir, "fromchat").deleteRecursively()
    }
}
