package ru.fromchat.ui.profile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.pr0gramm3r101.components.Category
import com.pr0gramm3r101.components.ListItem
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import ru.fromchat.Logger
import ru.fromchat.Res
import ru.fromchat.action_chat
import ru.fromchat.action_copy_link
import ru.fromchat.action_open_settings
import ru.fromchat.api.ApiClient
import ru.fromchat.api.local.db.store.ProfileCache
import ru.fromchat.api.local.db.store.UserStatus
import ru.fromchat.api.local.db.store.UserStatusStore
import ru.fromchat.api.local.db.store.visibleDisplayName
import ru.fromchat.api.local.db.store.visibleUsername
import ru.fromchat.api.schema.user.profile.UserProfile
import ru.fromchat.back
import ru.fromchat.presence_online
import ru.fromchat.presence_recently
import ru.fromchat.profile_headline_bio
import ru.fromchat.profile_headline_member_since
import ru.fromchat.profile_headline_username
import ru.fromchat.profile_headline_verification
import ru.fromchat.profile_load_failed
import ru.fromchat.profile_not_found
import ru.fromchat.profile_title
import ru.fromchat.profile_verified_support
import ru.fromchat.profile_verify_prompt_support
import ru.fromchat.ui.LocalNavController
import ru.fromchat.ui.chat.Avatar
import ru.fromchat.ui.chat.TypingIndicator
import ru.fromchat.ui.chat.panels.publicchat.publicChatProfileSharedAvatarKey
import ru.fromchat.ui.components.Text
import ru.fromchat.utils.formatLastSeen
import ru.fromchat.utils.rememberLastSeenFormatStrings
import com.pr0gramm3r101.utils.scaleOnPress

private sealed interface ProfileLoadError {
    data object Generic : ProfileLoadError
    data class Message(val text: String) : ProfileLoadError
}

private data class ProfileUiState(
    val profile: UserProfile? = null,
    val isLoading: Boolean = true,
    val error: ProfileLoadError? = null
)

private val profileActionCardPressSpring = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessLow,
    visibilityThreshold = 0.001f
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    userId: Int?,
    username: String? = null,
    onBack: () -> Unit,
    onChat: (Int) -> Unit,
    hideAvatar: Boolean = false,
    onAvatarSlotBounds: ((Rect) -> Unit)? = null,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    sharedAvatarKey: Any? = null,
    useSharedElementFromNavigation: Boolean = false,
    sharedSourceMessageId: Int = -1,
    initialDisplayName: String? = null,
    showErrorAsToast: Boolean = false,
    onOpenSettings: () -> Unit = {}
) {
    val clipboardManager: ClipboardManager = LocalClipboardManager.current
    val navController = LocalNavController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val profileLoadFailed = stringResource(Res.string.profile_load_failed)
    val profileNotFound = stringResource(Res.string.profile_not_found)

    val targetUserId = userId.takeIf { it != null && it > 0 }
    val targetUsername = username?.trim()?.takeIf { it.isNotBlank() }
    val ownUserId = ApiClient.user?.id?.takeIf { it > 0 }

    val cacheLookupId = when {
        targetUserId != null -> targetUserId
        targetUsername != null -> null
        else -> ownUserId
    }

    val lookupMode = when {
        targetUserId != null -> "id"
        targetUsername != null -> "username"
        else -> "own"
    }

    val lookupIdentifier = when {
        targetUserId != null -> targetUserId.toString()
        !targetUsername.isNullOrBlank() -> targetUsername
        else -> "self"
    }

    var state by remember(targetUserId, targetUsername, ownUserId) {
        mutableStateOf(
            cacheLookupId?.let { ProfileCache.get(it) }.let {
                ProfileUiState(
                    profile = it,
                    isLoading = it == null,
                    error = null
                )
            }
        )
    }

    val latestUi by rememberUpdatedState(state)

    LaunchedEffect(cacheLookupId, targetUserId, targetUsername, lifecycleOwner) {
        Logger.d(
            "ProfileScreen",
            "load start: mode=$lookupMode identifier=$lookupIdentifier cacheLookupId=$cacheLookupId ownUserId=$ownUserId " +
                "lifecycleStarted=${lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)}"
        )

        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                val profile = when {
                    targetUserId == null && targetUsername == null -> ApiClient.getOwnProfile()
                    targetUsername != null -> ApiClient.getProfileByUsername(targetUsername)
                    else -> ApiClient.getProfileById(targetUserId!!)
                }

                if (profile.username.isBlank() && profile.displayName.isNullOrBlank()) {
                    cacheLookupId?.let { ProfileCache.evictUnusableClientPreview(it) }

                    Logger.d(
                        "ProfileScreen",
                        "load success but blank identity for id=${profile.id}, dropping " +
                            "as unusable preview"
                    )

                    state = latestUi.copy(
                        profile = null,
                        isLoading = false,
                        error = ProfileLoadError.Generic
                    )

                    return@repeatOnLifecycle
                }

                Logger.d(
                    "ProfileScreen",
                    "load success: mode=$lookupMode identifier=$lookupIdentifier -> " +
                        "id=${profile.id}, username='${profile.username}', " +
                        "display='${profile.displayName}', deleted=${profile.deleted}, " +
                        "suspended=${profile.suspended}"
                )

                ProfileCache.put(profile)
                state = latestUi.copy(profile = profile, isLoading = false, error = null)
            } catch (err: Exception) {
                val fallbackId = (
                    if (targetUsername != null) null else targetUserId ?: ownUserId
                )?.also {
                    ProfileCache.evictUnusableClientPreview(it)
                }

                val fallback = fallbackId?.let { ProfileCache.get(it) }

                Logger.d(
                    "ProfileScreen",
                    "load failure fallback lookup: fallbackId=$fallbackId " +
                        "fallbackFound=${fallback != null}"
                )

                val resolvedErrorMessage = when {
                    err is ClientRequestException && err.response.status.value == 404 -> profileNotFound
                    else -> err.message?.takeIf { it.isNotBlank() } ?: profileLoadFailed
                }

                if (err is ClientRequestException) {
                    Logger.d(
                        "ProfileScreen",
                        "load failed: mode=$lookupMode identifier=$lookupIdentifier " +
                            "status=${err.response.status.value} fallbackId=$fallbackId error=${err.message}"
                    )
                } else {
                    Logger.d(
                        "ProfileScreen",
                        "load failed: mode=$lookupMode identifier=$lookupIdentifier " +
                            "errorType=${err::class.simpleName} message=${err.message}"
                    )
                }

                if (
                    showErrorAsToast &&
                    (targetUserId != null || targetUsername != null) &&
                    latestUi.profile == null &&
                    fallback == null
                ) {
                    showProfileLoadErrorMessage(resolvedErrorMessage)
                }

                state = latestUi.copy(
                    error = if (latestUi.profile == null && fallback == null) {
                        if (resolvedErrorMessage == profileLoadFailed) {
                            ProfileLoadError.Generic
                        } else {
                            ProfileLoadError.Message(resolvedErrorMessage)
                        }
                    } else {
                        null
                    },
                    profile = latestUi.profile ?: fallback,
                    isLoading = false
                )
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumTopAppBar(
                title = { Text(stringResource(Res.string.profile_title)) },
                navigationIcon = {
                    if (navController.currentDestination?.route != "chat") {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(Res.string.back),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            val loadError = state.error

            val profile = state.profile
            val currentProfileUserId = targetUserId ?: ownUserId ?: profile?.id
            val displayName =
                profile?.visibleDisplayName(currentProfileUserId)
                ?: initialDisplayName?.takeIf { it.isNotBlank() }
                ?: "?"
            val usernameForLinks = profile?.visibleUsername(currentProfileUserId)

            val navSharedAvatarKey =
                if (useSharedElementFromNavigation && targetUserId != null && sharedSourceMessageId != -1) {
                    publicChatProfileSharedAvatarKey(targetUserId, sharedSourceMessageId)
                } else {
                    null
                }

            val effectiveSharedAvatarKey: Any? = sharedAvatarKey ?: navSharedAvatarKey
            val useSharedAvatar = sharedTransitionScope != null &&
                animatedVisibilityScope != null &&
                effectiveSharedAvatarKey != null

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    useSharedAvatar -> {
                        with(sharedTransitionScope) {
                            Avatar(
                                profilePictureUrl = profile?.profilePicture,
                                displayName = displayName,
                                modifier = Modifier
                                    .padding(top = 16.dp)
                                    .sharedElement(
                                        rememberSharedContentState(
                                            key = effectiveSharedAvatarKey
                                        ),
                                        animatedVisibilityScope = animatedVisibilityScope
                                    )
                                    .size(128.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    !hideAvatar -> {
                        Avatar(
                            profilePictureUrl = profile?.profilePicture,
                            displayName = displayName,
                            modifier = Modifier
                                .padding(top = 16.dp)
                                .size(128.dp)
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    onAvatarSlotBounds != null -> {
                        Box(
                            modifier = Modifier
                                .padding(top = 16.dp)
                                .size(128.dp)
                                .onGloballyPositioned { coords ->
                                    onAvatarSlotBounds(
                                        Rect(
                                            coords.positionInRoot().x,
                                            coords.positionInRoot().y,
                                            coords.positionInRoot().x + coords.size.width.toFloat(),
                                            coords.positionInRoot().y + coords.size.height.toFloat()
                                        )
                                    )
                                }
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    else -> {
                        Spacer(modifier = Modifier.height(16.dp + 128.dp + 12.dp))
                    }
                }

                when {
                    state.isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
                    }

                    loadError != null -> {
                        Text(
                            text = when (loadError) {
                                ProfileLoadError.Generic -> profileLoadFailed
                                is ProfileLoadError.Message -> loadError.text
                            },
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 24.dp)
                        )
                    }

                    profile != null -> {
                        val compactIdentityForPublicChat =
                            useSharedElementFromNavigation && sharedSourceMessageId > 0
                        val profileLink =
                            if (compactIdentityForPublicChat) {
                                "https://fromchat.ru/?u=${profile.id}"
                            } else {
                                usernameForLinks
                                    ?.let { "https://fromchat.ru/@$it" }
                                    ?: "https://fromchat.ru/?u=${profile.id}"
                            }

                        val isOwnProfile = ApiClient.user?.id == profile.id
                        val statusState = UserStatusStore
                            .status
                            .collectAsState()
                            .value[profile.id] ?: UserStatus(
                                online = profile.online,
                                lastSeen = profile.lastSeen
                            )

                        val typingUsers = statusState.typingUsernames
                        val statusText = if (statusState.online) {
                            stringResource(Res.string.presence_online)
                        } else {
                            formatLastSeen(
                                false,
                                statusState.lastSeen,
                                rememberLastSeenFormatStrings()
                            ).ifEmpty { stringResource(Res.string.presence_recently) }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.titleLarge
                            )

                            StatusBadge(
                                verified = profile.verified,
                                userId = profile.id
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        AnimatedContent(
                            targetState = when {
                                typingUsers.isNotEmpty() -> "typing:${typingUsers.joinToString("|")}"
                                statusState.online ->"online"
                                else -> "offline"
                            },
                            transitionSpec = {
                                (slideInVertically { it / 2 } + fadeIn()) togetherWith
                                    (slideOutVertically { -it / 2 } + fadeOut())
                            },
                            label = "profile_status_${profile.id}"
                        ) { state ->
                            if (state.startsWith("typing:")) {
                                TypingIndicator(
                                    typingUsers = typingUsers
                                )
                            } else {
                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (state == "online")
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(36.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            @Composable
                            fun Item(label: String, icon: ImageVector, onClick: () -> Unit) {
                                val interactionSource = remember { MutableInteractionSource() }

                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .scaleOnPress(
                                            scale = 0.90f,
                                            interactionSource = interactionSource,
                                            clipShape = MaterialTheme.shapes.extraLarge,
                                            animationSpec = profileActionCardPressSpring
                                        ),
                                    shape = MaterialTheme.shapes.extraLarge,
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(
                                                interactionSource = interactionSource,
                                                indication = LocalIndication.current,
                                                onClick = onClick
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.padding(vertical = 16.dp)
                                        ) {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = null,
                                                modifier = Modifier.size(28.dp)
                                            )

                                            Spacer(modifier = Modifier.height(6.dp))

                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                    }
                                }
                            }

                            if (isOwnProfile) {
                                Item(
                                    stringResource(Res.string.action_open_settings),
                                    Icons.Filled.Settings,
                                    onOpenSettings
                                )
                            } else {
                                Item(
                                    stringResource(Res.string.action_chat),
                                    Icons.AutoMirrored.Filled.Chat
                                ) {
                                    onChat(profile.id)
                                }
                            }

                            Item(
                                stringResource(Res.string.action_copy_link),
                                Icons.Filled.Link
                            ) {
                                clipboardManager.setText(AnnotatedString(profileLink))
                            }
                        }

                        val showDetailsUsername =
                            !compactIdentityForPublicChat && usernameForLinks != null
                        val showDetailsMemberSince =
                            !compactIdentityForPublicChat &&
                                !profile.createdAt.isNullOrBlank()
                        val showDetailsBio = !profile.bio.isNullOrBlank()
                        val showDetailsVerify = profile.verified == true || ApiClient.user?.id == 1

                        if (
                            showDetailsUsername ||
                            showDetailsMemberSince ||
                            showDetailsBio ||
                            showDetailsVerify
                        ) {
                            Category(Modifier.padding(top = 16.dp)) {
                                if (showDetailsUsername) {
                                    ListItem(
                                        headline = stringResource(Res.string.profile_headline_username),
                                        supportingText = usernameForLinks,
                                        divider = true,
                                        leadingContent = {
                                            Icon(
                                                imageVector = Icons.Filled.AlternateEmail,
                                                contentDescription = null
                                            )
                                        }
                                    )
                                }

                                if (showDetailsMemberSince) {
                                    ListItem(
                                        headline = stringResource(Res.string.profile_headline_member_since),
                                        supportingText = profile.createdAt,
                                        divider = true,
                                        leadingContent = {
                                            Icon(
                                                imageVector = Icons.Filled.CalendarMonth,
                                                contentDescription = null
                                            )
                                        }
                                    )
                                }

                                if (showDetailsBio) {
                                    ListItem(
                                        headline = stringResource(Res.string.profile_headline_bio),
                                        supportingText = profile.bio,
                                        divider = true,
                                        leadingContent = {
                                            Icon(
                                                imageVector = Icons.Filled.Info,
                                                contentDescription = null
                                            )
                                        }
                                    )
                                }

                                if (showDetailsVerify) {
                                    ListItem(
                                        headline = stringResource(Res.string.profile_headline_verification),
                                        supportingText =
                                            if (profile.verified == true)
                                                stringResource(Res.string.profile_verified_support)
                                            else stringResource(Res.string.profile_verify_prompt_support),
                                        leadingContent = {
                                            Icon(
                                                imageVector = Icons.Filled.Verified,
                                                contentDescription = null
                                            )
                                        },
                                        divider = true,
                                        onClick = if (ApiClient.user?.id == 1) {
                                            {
                                                scope.launch {
                                                    val result = withContext(Dispatchers.Default) {
                                                        runCatching { ApiClient.verifyUser(profile.id) }.getOrNull()
                                                    }
                                                    result?.verified?.let { newVerified ->
                                                        val updated =
                                                            state.profile?.copy(verified = newVerified)
                                                        state = state.copy(profile = updated)
                                                        updated?.let { ProfileCache.put(it) }
                                                    }
                                                }
                                            }
                                        } else null
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

expect fun showProfileLoadErrorMessage(message: String)