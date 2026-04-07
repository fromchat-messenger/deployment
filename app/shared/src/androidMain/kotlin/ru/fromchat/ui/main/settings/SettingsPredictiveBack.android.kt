package ru.fromchat.ui.main.settings

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

@Composable
actual fun SettingsSecurityPredictiveBackHandler(
    enabled: Boolean,
    onProgress: (Float) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
) {
    PredictiveBackHandler(enabled = enabled) { progressFlow: Flow<BackEventCompat> ->
        try {
            progressFlow.collect { backEvent ->
                onProgress(backEvent.progress.coerceIn(0f, 1f))
            }
            onCommit()
        } catch (e: CancellationException) {
            onCancel()
            throw e
        }
    }
}

