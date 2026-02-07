package ru.fromchat.ui.dm

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ru.fromchat.ui.BackHandler
import ru.fromchat.ui.HapticFeedbackEvent
import ru.fromchat.ui.rememberHapticFeedback
import ru.fromchat.ui.profile.ProfileScreen

private const val TRANSITION_MS = 320

@Composable
fun DmContainerScreen(
    otherUserId: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showProfile by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptic = rememberHapticFeedback()
    val panel = remember(otherUserId) {
        DmPanelCache.getOrCreate(otherUserId, scope)
    }

    BackHandler(enabled = showProfile) {
        haptic(HapticFeedbackEvent.ProfileClosed)
        showProfile = false
    }

    // One stable key per DM to match the same avatar in chat + profile.
    val sharedAvatarKey = remember(otherUserId) { "dm-avatar-$otherUserId" }

    SharedTransitionLayout(modifier = modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = showProfile,
            transitionSpec = {
                fadeIn(animationSpec = tween(TRANSITION_MS)) togetherWith
                    fadeOut(animationSpec = tween(TRANSITION_MS))
            },
            label = "dm_profile"
        ) { targetState ->
            if (!targetState) {
                DmScreen(
                    panel = panel,
                    modifier = Modifier.fillMaxSize(),
                    onTitleClick = {
                        haptic(HapticFeedbackEvent.ProfileOpened)
                        showProfile = true
                    },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedContent,
                    sharedAvatarKey = sharedAvatarKey
                )
            } else {
                ProfileScreen(
                    userId = otherUserId,
                    onBack = {
                        haptic(HapticFeedbackEvent.ProfileClosed)
                        showProfile = false
                    },
                    onChat = { _ ->
                        haptic(HapticFeedbackEvent.ProfileClosed)
                        showProfile = false
                    },
                    modifier = Modifier.fillMaxSize(),
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedContent,
                    sharedAvatarKey = sharedAvatarKey
                )
            }
        }
    }
}
