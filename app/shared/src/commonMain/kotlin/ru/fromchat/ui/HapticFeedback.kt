package ru.fromchat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
fun rememberHapticFeedback(): (HapticFeedbackEvent) -> Unit {
    val h = rememberHapticFeedbackInternal()
    return remember(h) { { event: HapticFeedbackEvent -> h(event.ordinal) } }
}
