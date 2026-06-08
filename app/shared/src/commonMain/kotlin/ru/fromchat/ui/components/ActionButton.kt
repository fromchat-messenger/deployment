package ru.fromchat.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun ActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable (RowScope.() -> Unit)
) {
    val showCtaAsPrimary = enabled || loading
    val ctaTargetContainer =
        if (showCtaAsPrimary)
            MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerHigh
    val ctaTargetContent =
        if (showCtaAsPrimary)
            MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val ctaContainer by animateColorAsState(
        ctaTargetContainer,
        animationSpec = tween(durationMillis = 220),
        label = "serverConfigCtaContainer",
    )
    val ctaContent by animateColorAsState(
        ctaTargetContent,
        animationSpec = tween(durationMillis = 220),
        label = "serverConfigCtaContent",
    )

    Button(
        onClick = {
            if (enabled) {
                onClick()
            }
        },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(modifier),
        shape = CtaShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = ctaContainer,
            contentColor = ctaContent,
            disabledContainerColor = ctaContainer,
            disabledContentColor = ctaContent,
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
            disabledElevation = 0.dp,
        ),
        interactionSource = interactionSource
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = loading,
                transitionSpec = {
                    (fadeIn(tween(200)) + slideInVertically { it / 4 }) togetherWith
                            (fadeOut(tween(200)) + slideOutVertically { -it / 4 })
                },
                label = "cta",
            ) { loading ->
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = ctaContent,
                    )
                } else {
                    Row {
                        CompositionLocalProvider(
                            LocalTextAlign provides TextAlign.Center
                        ) {
                            content()
                        }
                    }
                }
            }
        }
    }
}