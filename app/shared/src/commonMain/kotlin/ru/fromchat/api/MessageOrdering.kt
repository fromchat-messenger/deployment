package ru.fromchat.api

/**
 * Chat list order: confirmed messages by time, then outgoing queued (negative id) at the bottom.
 * Avoids optimistic rows jumping mid-thread when client clock lags the server.
 */
internal fun sortMessagesForChatDisplay(messages: List<Message>): List<Message> {
    if (messages.size <= 1) return messages
    val (pending, confirmed) = messages.partition { it.id < 0 }
    val comparator = compareBy<Message>(
        { messageSortEpochMillis(it) },
        { it.id.toLong() },
    )
    return confirmed.sortedWith(comparator) + pending.sortedWith(comparator)
}

internal fun messageSortEpochMillis(message: Message): Long =
    parseMessageTimestampMillis(message.timestamp) ?: Long.MIN_VALUE
