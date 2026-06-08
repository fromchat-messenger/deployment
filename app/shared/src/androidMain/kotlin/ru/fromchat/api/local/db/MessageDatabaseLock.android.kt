package ru.fromchat.api.local.db

private val lock = Any()

internal actual fun <T> withMessageDatabaseLock(block: () -> T): T = synchronized(lock, block)
