package ru.fromchat.ui.dm

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import ru.fromchat.api.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.fromchat.api.db.MessageRepository
import ru.fromchat.api.outbox.OutgoingMessageCoordinator
import ru.fromchat.api.outbox.scheduleOutboxProcessing
import ru.fromchat.core.cache.CacheContext
import ru.fromchat.ui.chat.ChatScreen

@Composable
fun DmScreen(
    panel: DmPanel,
    scrollToMessageId: Int? = null,
    modifier: Modifier = Modifier,
    onTitleClick: (() -> Unit)? = null,
    hideTitleBarAvatar: Boolean = false,
    onAvatarSlotBounds: ((Rect) -> Unit)? = null,
    onTitleAvatarChange: ((ru.fromchat.ui.chat.AvatarInfo?) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    sharedAvatarKey: Any? = null
) {
    val currentUserId = ApiClient.user?.id
    val activeInstanceId by CacheContext.activeInstanceId.collectAsState()
    val otherUserId = panel.getState().profileUserId

    LaunchedEffect(panel, otherUserId) {
        if (otherUserId != null && otherUserId > 0) {
            panel.loadMessages()
        }
    }

    LaunchedEffect(activeInstanceId, otherUserId) {
        val peerId = otherUserId ?: return@LaunchedEffect
        val instanceId = activeInstanceId.trim()
        if (instanceId.isBlank() || peerId <= 0) return@LaunchedEffect
        scheduleOutboxProcessing(instanceId)
        withContext(Dispatchers.Default) {
            OutgoingMessageCoordinator.drainOutboxForInstance(instanceId)
        }
    }

    LaunchedEffect(panel, activeInstanceId, otherUserId) {
        val peerId = otherUserId ?: return@LaunchedEffect
        if (activeInstanceId.isBlank() || peerId <= 0) return@LaunchedEffect
        MessageRepository.observeDmMessages(peerId).collect { rows ->
            panel.syncMessagesFromDatabase(rows)
        }
    }

    ChatScreen(
        panel = panel,
        currentUserId = currentUserId,
        modifier = modifier.fillMaxSize(),
        scrollToMessageId = scrollToMessageId,
        onTitleClick = onTitleClick,
        hideTitleBarAvatar = hideTitleBarAvatar,
        onAvatarSlotBounds = onAvatarSlotBounds,
        onTitleAvatarChange = onTitleAvatarChange,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        sharedAvatarKey = sharedAvatarKey
    )
}
