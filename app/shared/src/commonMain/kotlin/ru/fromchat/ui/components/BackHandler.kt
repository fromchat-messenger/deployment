package ru.fromchat.ui.components

import androidx.compose.runtime.Composable

/**
 * Cross-platform hook for Android predictive back progress on the security password flow screen.
 *
 * - On Android, this ties into androidx.activity.compose.PredictiveBackHandler.
 * - On other platforms, it is a no-op.
 */
@Composable
expect fun PredictiveBackHandler(
    enabled: Boolean,
    onProgress: (Float) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
)

/**
 * Handles system back when [enabled]. When enabled, [onBack] is invoked instead of default back.
 * On Android: uses BackHandler (supports predictive back when app opts in).
 * On iOS: no-op.
 */
@Composable
expect fun BackHandler(enabled: Boolean, onBack: () -> Unit)

@Composable
expect fun rememberHapticFeedbackInternal(): (Int) -> Unit
