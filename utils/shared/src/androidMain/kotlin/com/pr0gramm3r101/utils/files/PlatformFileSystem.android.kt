package com.pr0gramm3r101.utils.files

import com.pr0gramm3r101.utils.UtilsLibrary
import java.io.File

internal actual fun expectExists(path: String): Boolean =
    File(path).exists()

internal actual fun expectWriteBytes(path: String, bytes: ByteArray) {
    val file = File(path)
    file.parentFile?.mkdirs()
    file.writeBytes(bytes)
}

internal actual fun expectDelete(path: String) {
    File(path).delete()
}

internal actual fun expectDeleteFilesWithPrefix(dirPath: String, namePrefix: String) {
    val dir = File(dirPath)
    if (!dir.exists()) return
    dir.listFiles()?.forEach { file ->
        if (file.name.startsWith(namePrefix)) {
            file.delete()
        }
    }
}

internal actual fun expectGetAppCacheDirectory(): String =
    UtilsLibrary.context.cacheDir.absolutePath

internal actual fun expectEnsureDirectory(path: String) {
    File(path).mkdirs()
}
