package ru.fromchat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import ru.fromchat.api.Message
import ru.fromchat.ui.BackHandler
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private val MENU_BG_ALPHA = 0.5f

private data class InitialTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val cornerRadius: Float,
    val bgAlpha: Float
)

@OptIn(ExperimentalTime::class)
private fun formatDateTime(timestamp: String): String {
    return try {
        Instant.parse(timestamp).toLocalDateTime(TimeZone.currentSystemDefault()).let {
            val hour = it.hour.toString().padStart(2, '0')
            val minute = it.minute.toString().padStart(2, '0')
            val month = (it.month.ordinal + 1).toString().padStart(2, '0')
            val day = it.day.toString().padStart(2, '0')
            val year = it.year
            "$month/$day/$year $hour:$minute"
        }
    } catch (_: Exception) {
        timestamp
    }
}

@Composable
fun ImageFullscreenPreview(
    message: Message,
    fileIndex: Int,
    currentUserId: Int?,
    onDismiss: () -> Unit,
    onReply: (Message) -> Unit,
    onDelete: (Message) -> Unit,
    onSave: (Message, Int) -> Unit,
    onClosingChange: (Boolean) -> Unit = {},
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    sharedImageKey: Any? = null,
    modifier: Modifier = Modifier,
    thumbnailBounds: Rect? = null
) {
    val file = message.files?.getOrNull(fileIndex) ?: return
    val envelope = message.dmEnvelope
    val thumbnailBase64 = message.fileThumbnails?.getOrNull(fileIndex)

    var cachedPath by remember(message.id, fileIndex, file.path) {
        mutableStateOf(DecryptedImageCache.getCached(message.id, fileIndex, file.path))
    }
    val thumbnailBytes = remember(thumbnailBase64) {
        thumbnailBase64?.let { runCatching { com.pr0gramm3r101.utils.crypto.Base64.decode(it) }.getOrNull() }
    }

    LaunchedEffect(message.id, fileIndex, file.path, envelope) {
        cachedPath = DecryptedImageCache.getOrDecrypt(message.id, fileIndex, file, envelope, currentUserId)
    }

    val imageModel = cachedPath ?: thumbnailBytes

    var menusVisible by remember { mutableStateOf(true) }
    var dismissRequested by remember { mutableStateOf(false) }
    val backgroundAlpha = remember { Animatable(1f) }
    var hasPlayedOpenAnimation by remember(thumbnailBounds) { mutableStateOf(thumbnailBounds == null) }
    val isInitialOpenState = thumbnailBounds != null && !hasPlayedOpenAnimation
    val effectiveBackgroundAlpha = if (isInitialOpenState) 0f else backgroundAlpha.value
    val effectiveMenusVisible = if (isInitialOpenState) false else menusVisible

    BackHandler(enabled = true) {
        dismissRequested = true
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = effectiveBackgroundAlpha))
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clip(RectangleShape)
                .align(Alignment.Center),
            contentAlignment = Alignment.Center
        ) {
            val containerWidth = constraints.maxWidth.toFloat()
            val containerHeight = constraints.maxHeight.toFloat()
            val fileAspectRatio = message.fileAspectRatios?.getOrNull(fileIndex)?.takeIf { it > 0f }
            val contentHeightAtScale1 = if (fileAspectRatio != null) containerWidth / fileAspectRatio else containerHeight

            when (val model = imageModel) {
                null -> {
                    LaunchedEffect(dismissRequested) {
                        if (dismissRequested) onDismiss()
                    }
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = Color.White
                    )
                }
                else -> {
                    val initial = remember(
                        thumbnailBounds, containerWidth, containerHeight, contentHeightAtScale1
                    ) {
                        if (thumbnailBounds != null && fileAspectRatio != null) {
                            val fullTop = (containerHeight - contentHeightAtScale1) / 2f
                            val fullCenter = Offset(
                                x = containerWidth / 2f,
                                y = fullTop + contentHeightAtScale1 / 2f
                            )
                            val thumbCenter = thumbnailBounds.center
                            val thumbWidth = thumbnailBounds.width
                            val s = thumbWidth / containerWidth
                            val o = thumbCenter - fullCenter
                            InitialTransform(s, o.x, o.y, 12f, 0f)
                        } else {
                            InitialTransform(1f, 0f, 0f, 0f, 1f)
                        }
                    }
                    var scale by remember { mutableStateOf(initial.scale) }
                    var offset by remember { mutableStateOf(Offset(initial.offsetX, initial.offsetY)) }
                    val scaleAnim = remember { Animatable(initial.scale) }
                    val offsetXAnim = remember { Animatable(initial.offsetX) }
                    val offsetYAnim = remember { Animatable(initial.offsetY) }
                    val cornerRadiusAnim = remember { Animatable(initial.cornerRadius) }
                    val state = rememberTransformableState { zoomChange, offsetChange, _ ->
                        scale = (scale * zoomChange).coerceIn(1f, 12f)
                        offset += offsetChange
                    }

                    LaunchedEffect(thumbnailBounds) {
                        if (thumbnailBounds != null && !hasPlayedOpenAnimation) {
                            hasPlayedOpenAnimation = true

                            val fullTop = (containerHeight - contentHeightAtScale1) / 2f
                            val fullCenter = Offset(
                                x = containerWidth / 2f,
                                y = fullTop + contentHeightAtScale1 / 2f
                            )
                            val thumbCenter = thumbnailBounds.center
                            val thumbWidth = thumbnailBounds.width

                            val startScale = thumbWidth / containerWidth
                            val startOffset = thumbCenter - fullCenter

                            scale = startScale
                            offset = startOffset
                            scaleAnim.snapTo(startScale)
                            offsetXAnim.snapTo(startOffset.x)
                            offsetYAnim.snapTo(startOffset.y)
                            cornerRadiusAnim.snapTo(12f)
                            backgroundAlpha.snapTo(0f)
                            menusVisible = false

                            coroutineScope {
                                launch { scaleAnim.animateTo(1f, tween(250)) }
                                launch { offsetXAnim.animateTo(0f, tween(250)) }
                                launch { offsetYAnim.animateTo(0f, tween(250)) }
                                launch { cornerRadiusAnim.animateTo(0f, tween(250)) }
                                launch { backgroundAlpha.animateTo(1f, tween(250)) }
                            }

                            menusVisible = true
                        }
                    }

                    LaunchedEffect(dismissRequested) {
                        if (!dismissRequested) return@LaunchedEffect

                        val hasTransform = scaleAnim.value != 1f ||
                            offsetXAnim.value != 0f ||
                            offsetYAnim.value != 0f

                        if (thumbnailBounds != null) {
                            menusVisible = false

                            val fullTop = (containerHeight - contentHeightAtScale1) / 2f
                            val fullCenter = Offset(
                                x = containerWidth / 2f,
                                y = fullTop + contentHeightAtScale1 / 2f
                            )
                            val thumbCenter = thumbnailBounds.center
                            val thumbWidth = thumbnailBounds.width

                            val targetScale = thumbWidth / containerWidth
                            val targetOffset = thumbCenter - fullCenter

                            scaleAnim.snapTo(scale)
                            offsetXAnim.snapTo(offset.x)
                            offsetYAnim.snapTo(offset.y)
                            cornerRadiusAnim.snapTo(0f)

                            coroutineScope {
                                launch { scaleAnim.animateTo(targetScale, tween(250)) }
                                launch { offsetXAnim.animateTo(targetOffset.x, tween(250)) }
                                launch { offsetYAnim.animateTo(targetOffset.y, tween(250)) }
                                launch { cornerRadiusAnim.animateTo(12f, tween(250)) }
                                launch { backgroundAlpha.animateTo(0f, tween(250)) }
                            }
                        } else {
                            if (hasTransform) {
                                menusVisible = false
                                coroutineScope {
                                    launch { scaleAnim.animateTo(1f, tween(220)) }
                                    launch { offsetXAnim.animateTo(0f, tween(220)) }
                                    launch { offsetYAnim.animateTo(0f, tween(220)) }
                                    launch { cornerRadiusAnim.animateTo(0f, tween(220)) }
                                    launch { backgroundAlpha.animateTo(0f, tween(220)) }
                                }
                            } else {
                                backgroundAlpha.animateTo(0f, tween(220))
                            }
                        }

                        onClosingChange(true)
                        delay(50)
                        onClosingChange(false)
                        onDismiss()
                    }
                    LaunchedEffect(scale, offset, state.isTransformInProgress) {
                        if (state.isTransformInProgress) {
                            scaleAnim.snapTo(scale)
                            offsetXAnim.snapTo(offset.x)
                            offsetYAnim.snapTo(offset.y)
                        }
                    }
                    LaunchedEffect(state.isTransformInProgress) {
                        if (!state.isTransformInProgress) {
                            val clampedScale = scale.coerceIn(1f, 10f)
                            val scaledW = containerWidth * clampedScale
                            val scaledH = contentHeightAtScale1 * clampedScale
                            val maxOffsetX = max(0f, (scaledW - containerWidth) / 2f)
                            val minOffsetX = -maxOffsetX
                            val maxOffsetY = max(0f, (scaledH - containerHeight) / 2f)
                            val minOffsetY = -maxOffsetY
                            val clampedOffset = Offset(
                                offset.x.coerceIn(minOffsetX, maxOffsetX),
                                offset.y.coerceIn(minOffsetY, maxOffsetY)
                            )
                            scaleAnim.snapTo(scale)
                            offsetXAnim.snapTo(offset.x)
                            offsetYAnim.snapTo(offset.y)
                            coroutineScope {
                                launch { scaleAnim.animateTo(clampedScale, tween(300)) }
                                launch { offsetXAnim.animateTo(clampedOffset.x, tween(300)) }
                                launch { offsetYAnim.animateTo(clampedOffset.y, tween(300)) }
                            }
                            scale = scaleAnim.value
                            offset = Offset(offsetXAnim.value, offsetYAnim.value)
                        }
                    }

                    val sharedElementModifier = if (sharedImageKey != null && sharedTransitionScope != null && animatedVisibilityScope != null) {
                        with(sharedTransitionScope) {
                            Modifier.sharedElement(
                                rememberSharedContentState(key = sharedImageKey),
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                    } else Modifier
                    val sizeModifier = if (fileAspectRatio != null) Modifier.fillMaxWidth().aspectRatio(fileAspectRatio)
                    else Modifier.fillMaxSize()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .transformable(state = state, lockRotationOnZoomPan = true)
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { menusVisible = !menusVisible })
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .then(sizeModifier)
                                .then(sharedElementModifier)
                                .offset { IntOffset(offsetXAnim.value.roundToInt(), offsetYAnim.value.roundToInt()) }
                        ) {
                            AsyncImage(
                                model = model,
                                contentDescription = file.name,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = scaleAnim.value
                                        scaleY = scaleAnim.value
                                        val scale = maxOf(scaleAnim.value, 0.01f)
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape((cornerRadiusAnim.value / scale).dp)
                                        clip = true
                                    },
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    }
                }
            }
        }

        // Top bar: back, display name + date/time, 3-dot menu
        AnimatedVisibility(
            visible = effectiveMenusVisible,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = MENU_BG_ALPHA))
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { dismissRequested = true }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = message.username,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Text(
                        text = formatDateTime(message.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
                var menuExpanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Menu",
                            tint = Color.White
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.background(Color.Black.copy(alpha = MENU_BG_ALPHA))
                    ) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.AutoMirrored.Filled.Reply, null, tint = Color.White)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Reply", color = Color.White)
                                }
                            },
                        onClick = {
                            menuExpanded = false
                            onReply(message)
                            dismissRequested = true
                        }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.SaveAlt, null, tint = Color.White)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Save", color = Color.White)
                                }
                            },
                            onClick = {
                                menuExpanded = false
                                onSave(message, fileIndex)
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Delete, null, tint = Color.White)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Delete", color = Color.White)
                                }
                            },
                        onClick = {
                            menuExpanded = false
                            onDelete(message)
                            dismissRequested = true
                        }
                        )
                    }
                }
            }
        }

        // Bottom: message text
        AnimatedVisibility(
            visible = effectiveMenusVisible && message.content.isNotBlank(),
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
        ) {
            if (message.content.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = MENU_BG_ALPHA))
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(16.dp)
                ) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                }
            }
        }
    }
}
