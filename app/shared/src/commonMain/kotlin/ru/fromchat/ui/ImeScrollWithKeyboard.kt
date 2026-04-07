package ru.fromchat.ui

import androidx.compose.ui.Modifier

/**
 * Android: ties scroll to IME insets for smoother keyboard transitions (see Compose keyboard animations).
 * Other platforms: no-op.
 */
expect fun Modifier.imeScrollWithKeyboard(): Modifier
