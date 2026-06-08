package ru.fromchat.api.local.cache

/** Deletes the `cacheDir/fromchat/` tree (blobs, DB file on disk). Call after [ru.fromchat.api.db.MessageRepository.clearAllCache]. */
expect suspend fun wipeFromChatCacheDirectory()
