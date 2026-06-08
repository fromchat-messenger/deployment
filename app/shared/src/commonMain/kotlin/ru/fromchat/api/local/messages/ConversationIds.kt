package ru.fromchat.api.local.messages

/** General public group chat (only group for now). Groups use negative ids. */
const val GENERAL_PUBLIC_GROUP_ID: Int = -1

fun conversationIdForGroup(groupId: Int): String = groupId.toString()

fun conversationIdForDm(otherUserId: Int): String = "dm:$otherUserId"

fun groupIdFromConversationId(conversationId: String): Int? =
    conversationId.toIntOrNull()?.takeIf { it < 0 }

fun dmOtherUserIdFromConversationId(conversationId: String): Int? =
    if (conversationId.startsWith("dm:")) {
        conversationId.removePrefix("dm:").toIntOrNull()
    } else {
        null
    }
