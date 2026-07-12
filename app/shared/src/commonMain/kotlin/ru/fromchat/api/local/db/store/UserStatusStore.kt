package ru.fromchat.api.local.db.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class UserStatus(
    val online: Boolean,
    val lastSeen: String? = null,
    val typingUsernames: List<String> = emptyList()
)

object UserStatusStore {
    private val _status = MutableStateFlow<Map<Int, UserStatus>>(emptyMap())
    val status: StateFlow<Map<Int, UserStatus>> = _status.asStateFlow()

    fun update(userId: Int, online: Boolean, lastSeen: String?) {
        if (userId <= 0) return
        _status.update { current ->
            val existing = current[userId] ?: UserStatus(online = false)
            current + (userId to existing.copy(online = online, lastSeen = lastSeen ?: existing.lastSeen))
        }
    }

    fun addTyping(userId: Int, username: String) {
        val trimmed = username.trim().takeIf { it.isNotBlank() } ?: return
        _status.update { current ->
            val existing = current[userId] ?: UserStatus(online = false)
            val typing = (existing.typingUsernames + trimmed)
                .filter { it.isNotBlank() }
                .distinct()
            if (typing == existing.typingUsernames) current else current + (userId to existing.copy(typingUsernames = typing))
        }
    }

    fun removeTyping(userId: Int, username: String) {
        val trimmed = username.trim().takeIf { it.isNotBlank() } ?: return
        _status.update { current ->
            val existing = current[userId] ?: return@update current
            val typing = existing.typingUsernames.filterNot { it.equals(trimmed, ignoreCase = true) }
            current + (userId to existing.copy(typingUsernames = typing))
        }
    }
}
