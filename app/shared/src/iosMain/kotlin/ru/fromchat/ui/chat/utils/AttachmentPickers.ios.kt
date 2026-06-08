package ru.fromchat.ui.chat.utils

import androidx.compose.runtime.Composable

actual fun getFilenameFromUri(uri: String): String {
    return uri.substringAfterLast('/').takeIf { it.isNotBlank() } ?: "file"
}

@Composable
actual fun rememberImagePicker(onResult: (List<String>) -> Unit): () -> Unit {
    return { /* Phase 4: PHPickerViewController */ }
}

@Composable
actual fun rememberFilePicker(onResult: (List<String>) -> Unit): () -> Unit {
    return { /* Phase 4: UIDocumentPickerViewController */ }
}

actual suspend fun getImageAspectRatio(uri: String): Float? = null

actual suspend fun getImageDimensions(uri: String): Pair<Int, Int>? = null
