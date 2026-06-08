package ru.fromchat.api.local.workers

expect object AttachmentFileCopyForeground {
    fun onCopyStarted(storageKey: String, displayLabel: String? = null)
    fun onCopyFinished(storageKey: String)
}
