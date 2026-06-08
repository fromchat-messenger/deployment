package ru.fromchat.ui.chat.utils

import androidx.compose.runtime.Composable

/**
 * Opens the platform "save as" UI (default Downloads). Invokes [onDestination] with a
 * persistent export URI string, or null if cancelled.
 */
@Composable
expect fun rememberCreateDownloadDestinationLauncher(
    onDestination: (String?) -> Unit,
): (filename: String, mimeType: String) -> Unit

/** Re-applies persistable URI permission for a stored SAF export URI (best-effort). */
expect suspend fun persistExportUriPermissionIfNeeded(exportUri: String)
