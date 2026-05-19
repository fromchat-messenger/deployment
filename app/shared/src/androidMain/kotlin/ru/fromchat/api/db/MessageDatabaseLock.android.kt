package ru.fromchat.api.db

private val lock = Any()

internal actual fun <T> withMessageDatabaseLock(block: () -> T): T = synchronized(lock, block)
