package ru.fromchat.api.local.db

internal expect fun <T> withMessageDatabaseLock(block: () -> T): T
