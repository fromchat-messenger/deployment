package ru.fromchat.ui.calls

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ru.fromchat.api.calls.LiveKitConnectSession

@Composable
actual fun CallMediaLayer(
    connect: LiveKitConnectSession?,
    showDialingPlaceholder: Boolean,
    showInCallControls: Boolean,
    modifier: Modifier,
) {
    Box(modifier = modifier.fillMaxSize())
}
