package ru.fromchat.ui.chat.utils

import ru.fromchat.api.local.messages.parseMessageTimestampMillis
import ru.fromchat.api.schema.messages.Message
import kotlin.math.abs

/** One row per [Message.client_message_id]; prefers confirmed (id > 0) over optimistic. */
internal fun dedupeMessagesByClientId(messages: List<Message>): List<Message> {
    if (messages.size <= 1) return messages
    val order = ArrayList<String>(messages.size)
    val byKey = LinkedHashMap<String, Message>(messages.size)
    for (msg in messages) {
        val key = messageDedupeKey(msg)
        if (!byKey.containsKey(key)) {
            order.add(key)
        }
        val existing = byKey[key]
        byKey[key] = when {
            existing == null -> msg
            else -> preferMessageForDedupe(existing, msg)
        }
    }
    return order.mapNotNull { byKey[it] }
}

internal fun imageAttachmentKey(message: Message, fileIndex: Int): String {
    val cid = message.client_message_id?.trim().orEmpty()
    return if (cid.isNotEmpty()) "img_${cid}_$fileIndex" else "img_${message.id}_$fileIndex"
}

internal fun messageDedupeKey(msg: Message): String {
    val cid = msg.client_message_id?.trim().orEmpty()
    return if (cid.isNotEmpty()) "c:$cid" else "i:${msg.id}"
}

/** Drops optimistic rows already represented by a confirmed message (same client id or recent own attachment). */
internal fun dropSupersededOptimisticMessages(
    messages: List<Message>,
    currentUserId: Int?,
): List<Message> {
    if (messages.none { it.id < 0 }) return messages
    val confirmed = messages.filter { it.id > 0 }
    val confirmedClientIds = confirmed.mapNotNull { it.client_message_id?.trim()?.takeIf { it.isNotEmpty() } }.toSet()
    val self = currentUserId
    return messages.filter { msg ->
        if (msg.id >= 0) return@filter true
        val cid = msg.client_message_id?.trim().orEmpty()
        if (cid.isNotEmpty() && cid in confirmedClientIds) return@filter false
        // In-flight uploads (file or image): keep until a confirmed row shares the same client id.
        if (msg.pendingFileUri != null || !msg.uploadJobId.isNullOrBlank()) return@filter true
        if (self == null || msg.user_id != self) return@filter true
        val msgTime = parseMessageTimestampMillis(msg.timestamp)
        val nearOwnConfirmed = confirmed.filter { it.user_id == self }.any { confirmedMsg ->
            val confirmedTime = parseMessageTimestampMillis(confirmedMsg.timestamp)
            msgTime != null && confirmedTime != null &&
                abs(msgTime - confirmedTime) <= NEAR_DUPLICATE_MS
        }
        if (!nearOwnConfirmed) return@filter true
        val pendingIsAttachment = !msg.files.isNullOrEmpty()
        val confirmedHasAttachment = confirmed.any { it.user_id == self && !it.files.isNullOrEmpty() }
        when {
            pendingIsAttachment && confirmedHasAttachment -> false
            !pendingIsAttachment && !confirmedHasAttachment -> false
            !pendingIsAttachment && confirmedHasAttachment -> false
            else -> true
        }
    }
}

private const val NEAR_DUPLICATE_MS = 180_000L

private fun preferMessageForDedupe(existing: Message, incoming: Message): Message {
    val preferred = when {
        incoming.id > 0 && existing.id < 0 -> incoming
        existing.id > 0 && incoming.id < 0 -> existing
        incoming.id >= existing.id -> incoming
        else -> existing
    }
    val other = if (preferred === incoming) existing else incoming
    return mergeMessageUiFields(preferred, other)
}
