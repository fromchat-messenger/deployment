@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pr0gramm3r101.utils.files

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class)
internal actual fun expectExists(path: String): Boolean =
    NSFileManager.defaultManager.fileExistsAtPath(path)

@OptIn(ExperimentalForeignApi::class)
internal actual fun expectWriteBytes(path: String, bytes: ByteArray) {
    val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
    if (parent.isNotEmpty()) {
        NSFileManager.defaultManager.createDirectoryAtPath(parent, true, null, null)
    }
    val nsData = bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
    }
    nsData?.writeToFile(path, true)
}

internal actual fun expectDelete(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, null)
}

internal actual fun expectDeleteFilesWithPrefix(dirPath: String, namePrefix: String) {
    val contents = NSFileManager.defaultManager.contentsOfDirectoryAtPath(dirPath, null)
        ?: return
    (contents as List<*>).filterIsInstance<String>().forEach { name ->
        if (name.startsWith(namePrefix)) {
            NSFileManager.defaultManager.removeItemAtPath("$dirPath/$name", null)
        }
    }
}

internal actual fun expectGetAppCacheDirectory(): String {
    val paths = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
    return (paths.firstOrNull() as? String) ?: ""
}

internal actual fun expectEnsureDirectory(path: String) {
    NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
}
