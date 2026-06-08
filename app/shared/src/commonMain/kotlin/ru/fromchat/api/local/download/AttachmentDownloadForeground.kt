package ru.fromchat.api.local.download

/**
 * Android: keeps DM file attachment downloads alive in the background via a foreground service.
 * No-op on other platforms.
 */
expect object AttachmentDownloadForeground {
    fun onFileDownloadStarted(storageKey: String)
    fun onFileDownloadProgress(percent: Int, displayLabel: String?)
    fun onFileDownloadFinished(storageKey: String)
}
