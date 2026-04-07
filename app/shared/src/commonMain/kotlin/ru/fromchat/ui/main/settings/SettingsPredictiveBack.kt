package ru.fromchat.ui.main.settings

import androidx.compose.runtime.Composable

/**
 * Cross-platform hook for Android predictive back progress on the security password flow screen.
 *
 * - On Android, this ties into androidx.activity.compose.PredictiveBackHandler.
 * - On other platforms, it is a no-op.
 */
@Composable
expect fun SettingsSecurityPredictiveBackHandler(
    enabled: Boolean,
    onProgress: (Float) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
)

