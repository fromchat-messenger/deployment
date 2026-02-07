package ru.fromchat.ui

import androidx.compose.runtime.Composable

/**
 * Handles system back when [enabled]. When enabled, [onBack] is invoked instead of default back.
 * On Android: uses BackHandler (supports predictive back when app opts in).
 * On iOS: no-op.
 */
@Composable
expect fun BackHandler(enabled: Boolean, onBack: () -> Unit)

@Composable
expect fun rememberHapticFeedbackInternal(): (Int) -> Unit
