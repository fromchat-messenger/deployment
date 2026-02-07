package ru.fromchat.ui

import androidx.compose.runtime.Composable

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    // iOS has no system back button; back is handled by navigation.
}

@Composable
actual fun rememberHapticFeedbackInternal(): (Int) -> Unit = { }
