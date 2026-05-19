package ru.fromchat.api.db

internal expect fun <T> withMessageDatabaseLock(block: () -> T): T
