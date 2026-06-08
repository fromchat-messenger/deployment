package ru.fromchat.api.local.messages

import ru.fromchat.api.schema.messages.Message
import kotlin.math.abs

/** Stable negative row id for an optimistic / outbox [clientMessageId]. */
fun optimisticMessageIdForClientMessageId(clientMessageId: String): Int {
    val hc = clientMessageId.hashCode()
    val absHc = if (hc == Int.MIN_VALUE) Int.MAX_VALUE else abs(hc)
    return -(if (absHc == 0) 1 else absHc)
}

fun Message.isQueuedOutbound(): Boolean = id < 0
