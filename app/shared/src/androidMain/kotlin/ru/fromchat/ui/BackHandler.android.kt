package ru.fromchat.ui

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler as AndroidBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    AndroidBackHandler(enabled = enabled, onBack = onBack)
}

@Composable
actual fun rememberHapticFeedbackInternal(): (Int) -> Unit {
    val view = LocalView.current
    return remember(view) {
        { ordinal ->
            val constant = when (ordinal) {
                HapticFeedbackEvent.ProfileOpened.ordinal -> HapticFeedbackConstants.CLOCK_TICK
                HapticFeedbackEvent.ProfileClosed.ordinal -> HapticFeedbackConstants.CLOCK_TICK
                HapticFeedbackEvent.MessageSent.ordinal -> HapticFeedbackConstants.CONFIRM
                HapticFeedbackEvent.ContextMenuOpened.ordinal -> HapticFeedbackConstants.CONFIRM
                else -> HapticFeedbackConstants.CLOCK_TICK
            }
            view.performHapticFeedback(constant)
        }
    }
}
