package ru.fromchat.ui.chat

import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

actual fun getFilenameFromUri(uri: String): String {
    val context = com.pr0gramm3r101.utils.UtilsLibrary.context
    context.contentResolver.query(Uri.parse(uri), null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            val name = cursor.getString(nameIndex)
            if (!name.isNullOrBlank()) return name
        }
    }
    return uri.substringAfterLast('/').takeIf { it.isNotBlank() } ?: "file"
}

actual suspend fun getImageAspectRatio(uri: String): Float? {
    val context = com.pr0gramm3r101.utils.UtilsLibrary.context
    val parsed = Uri.parse(uri)
    val orientation = when {
        parsed.scheme == "content" || parsed.scheme == "file" -> {
            runCatching {
                context.contentResolver.openInputStream(parsed)?.use { stream ->
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                }
            }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        }
        else -> {
            val path = parsed.path
            if (path != null) {
                runCatching {
                    ExifInterface(path).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
            } else {
                ExifInterface.ORIENTATION_NORMAL
            }
        }
    }
    context.contentResolver.openInputStream(parsed)?.use { stream ->
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(stream, null, options)
        var w = options.outWidth
        var h = options.outHeight
        if (w <= 0 || h <= 0) return null
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE,
            -> {
                val swap = w
                w = h
                h = swap
            }
        }
        return w.toFloat() / h.toFloat()
    }
    return null
}

actual suspend fun getImageDimensions(uri: String): Pair<Int, Int>? {
    val context = com.pr0gramm3r101.utils.UtilsLibrary.context
    val parsed = Uri.parse(uri)
    val orientation = when {
        parsed.scheme == "content" || parsed.scheme == "file" -> {
            runCatching {
                context.contentResolver.openInputStream(parsed)?.use { stream ->
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                }
            }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        }
        else -> {
            val path = parsed.path
            if (path != null) {
                runCatching {
                    ExifInterface(path).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
            } else {
                ExifInterface.ORIENTATION_NORMAL
            }
        }
    }
    context.contentResolver.openInputStream(parsed)?.use { stream ->
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(stream, null, options)
        var w = options.outWidth
        var h = options.outHeight
        if (w <= 0 || h <= 0) return null
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE,
            -> {
                val swap = w
                w = h
                h = swap
            }
        }
        return w to h
    }
    return null
}

@Composable
actual fun rememberImagePicker(onResult: (List<String>) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        onResult(uris.map { it.toString() })
    }
    return {
        launcher.launch(
            PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                .build()
        )
    }
}

@Composable
actual fun rememberFilePicker(onResult: (List<String>) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        onResult(uris.map { it.toString() })
    }
    return {
        launcher.launch(arrayOf("*/*"))
    }
}
