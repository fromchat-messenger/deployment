package ru.fromchat.api.local.download

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

actual suspend fun openCachedAttachmentFile(
    cacheUri: String,
    mimeType: String,
    displayFilename: String?,
): Boolean {
    val url = NSURL.URLWithString(cacheUri) ?: NSURL.fileURLWithPath(cacheUri.removePrefix("file://"))
    return UIApplication.sharedApplication.openURL(url)
}
