package ru.fromchat.api.db

import kotlinx.coroutines.flow.Flow
import ru.fromchat.api.Message
import ru.fromchat.core.cache.CacheContext

/**
 * Instance-scoped message access for UI and send pipeline.
 */
object MessageRepository {
    private fun activeInstance(): String = CacheContext.requireActiveInstanceId()

    fun observeMessages(conversationId: String): Flow<List<Message>> =
        MessageCacheStore.observeMessages(activeInstance(), conversationId)

    fun observePublicMessages(): Flow<List<Message>> =
        observeMessages(conversationIdForGroup(GENERAL_PUBLIC_GROUP_ID))

    fun observeDmMessages(otherUserId: Int): Flow<List<Message>> =
        observeMessages(conversationIdForDm(otherUserId))

    suspend fun loadPublicMessages(): List<Message> = MessageCacheStore.loadPublicMessages()

    suspend fun loadRecentPublicMessages(limit: Long): List<Message> =
        MessageCacheStore.loadRecentPublicMessages(limit)

    suspend fun replacePublicMessages(messages: List<Message>) =
        MessageCacheStore.replacePublicMessages(messages)

    suspend fun upsertPublicMessage(message: Message) = MessageCacheStore.upsertPublicMessage(message)

    suspend fun confirmPublicMessage(clientMessageId: String, confirmed: Message) =
        MessageCacheStore.confirmPublicMessage(clientMessageId, confirmed)

    suspend fun deletePublicMessageByClientMessageId(clientMessageId: String) =
        MessageCacheStore.deletePublicMessageByClientMessageId(clientMessageId)

    suspend fun markMessageDeleted(conversationId: String, messageId: Int) =
        MessageCacheStore.markMessageDeleted(conversationId, messageId)

    suspend fun markPublicMessageDeleted(messageId: Int) =
        markMessageDeleted(conversationIdForGroup(GENERAL_PUBLIC_GROUP_ID), messageId)

    suspend fun loadDmMessages(otherUserId: Int): List<Message> =
        MessageCacheStore.loadDmMessages(otherUserId)

    suspend fun replaceDmMessages(otherUserId: Int, messages: List<Message>) =
        MessageCacheStore.replaceDmMessages(otherUserId, messages)

    suspend fun upsertDmMessage(otherUserId: Int, message: Message) =
        MessageCacheStore.upsertDmMessage(otherUserId, message)

    suspend fun confirmDmMessage(otherUserId: Int, clientMessageId: String, confirmed: Message) =
        MessageCacheStore.confirmDmMessage(otherUserId, clientMessageId, confirmed)

    suspend fun deleteDmMessageByClientMessageId(otherUserId: Int, clientMessageId: String) =
        MessageCacheStore.deleteDmMessageByClientMessageId(otherUserId, clientMessageId)

    suspend fun deleteDmMessageById(otherUserId: Int, messageId: Int) =
        MessageCacheStore.deleteDmMessageById(otherUserId, messageId)

    suspend fun replaceDmConversations(conversations: List<ru.fromchat.api.DmConversation>) =
        MessageCacheStore.replaceDmConversations(conversations)

    suspend fun loadCachedDmConversations(): List<CachedConversation> =
        MessageCacheStore.loadCachedDmConversations()

    suspend fun clearAllCache() = MessageCacheStore.clearAll()
}
