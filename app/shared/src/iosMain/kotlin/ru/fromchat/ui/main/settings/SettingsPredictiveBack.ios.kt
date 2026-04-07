package ru.fromchat.ui.main.settings

import androidx.compose.runtime.Composable

@Composable
actual fun SettingsSecurityPredictiveBackHandler(
    enabled: Boolean,
    onProgress: (Float) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
) {
    // iOS has no Android predictive back; keep state reset.
    onProgress(0f)
}

