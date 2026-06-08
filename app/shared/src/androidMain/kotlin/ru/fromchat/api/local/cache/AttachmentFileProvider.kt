package ru.fromchat.api.local.cache

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File

/**
 * Serves decrypted attachment files to other apps (installers, viewers).
 * Supplies [OpenableColumns.DISPLAY_NAME] — required by SAI and some document providers.
 */
class AttachmentFileProvider : FileProvider() {
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = resolveFile(uri)
        // Some installers (notably SAI) will crash if DISPLAY_NAME exists but is null.
        // Also, some callers query our URI in ways where FileProvider's internal resolution
        // may work while our custom resolveFile() returns null (e.g. URI forms or encodings).
        // So we always return a row with a best-effort display name.
        val columns = projection?.takeIf { it.isNotEmpty() }
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = MatrixCursor(columns, 1)
        val values = arrayOfNulls<Any>(columns.size)
        val safeDisplayName = file?.let { displayNameFor(it) }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "attachment"
        val safeSize = file?.length()
        for (i in columns.indices) {
            values[i] = when (columns[i]) {
                // Many installers/document providers don't use OpenableColumns constants directly.
                // Populate common aliases so DISPLAY_NAME is never null when a name is requested.
                OpenableColumns.DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                "display_name",
                "_display_name",
                "name",
                "filename",
                "title" ->
                    safeDisplayName
                OpenableColumns.SIZE,
                DocumentsContract.Document.COLUMN_SIZE,
                "size",
                "_size" ->
                    safeSize
                else -> null
            }
        }
        row.addRow(values)
        return row
    }

    override fun getType(uri: Uri): String? {
        val file = resolveFile(uri) ?: return super.getType(uri)
        val name = displayNameFor(file)
        return when {
            name.endsWith(".apk", ignoreCase = true) ||
                name.endsWith(".apks", ignoreCase = true) ||
                name.endsWith(".xapk", ignoreCase = true) ||
                name.endsWith(".apkm", ignoreCase = true) ->
                "application/vnd.android.package-archive"
            else -> super.getType(uri)
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = resolveFile(uri)
        if (file == null) {
            return super.openFile(uri, mode)
                ?: error("Failed to open attachment file")
        }
        val fileMode = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, fileMode)
            ?: error("Failed to open attachment file")
    }

    companion object {
        fun uriForFile(context: Context, file: File): Uri? = runCatching {
            getUriForFile(
                context,
                "${context.packageName}.attachment_files",
                file,
            )
        }.getOrNull()

        /** Strips cache storage-key prefix from on-disk basename (see [DecryptedFileCache]). */
        internal fun displayNameFor(file: File): String =
            displayNameFromBasename(file.name)

        internal fun displayNameFromBasename(basename: String): String {
            Regex("^file_(\\d+)_(\\d+)_(.+)$").matchEntire(basename)?.let {
                return it.groupValues[3]
            }
            Regex("^file_c_(.+)_(\\d+)_(.+)$").matchEntire(basename)?.let {
                return it.groupValues[3]
            }
            return basename
        }
    }

    private fun resolveFile(uri: Uri): File? {
        val ctx = context ?: return null
        if (uri.authority != "${ctx.packageName}.attachment_files") return null
        val segments = uri.pathSegments
        if (segments.isEmpty()) return null
        val root = when (segments.first()) {
            "decrypted_files" -> File(ctx.cacheDir, "decrypted_files")
            "decrypted_images" -> File(ctx.cacheDir, "decrypted_images")
            "fromchat" -> File(ctx.cacheDir, "fromchat")
            else -> return null
        }
        val relative = segments.drop(1).joinToString("/")
        if (relative.isEmpty()) return null
        val file = File(root, relative)
        return file.takeIf { it.isFile }
    }

    private fun emptyResultCursor(projection: Array<out String>?): Cursor {
        val columns = projection?.takeIf { it.isNotEmpty() }
            ?: arrayOf(OpenableColumns.DISPLAY_NAME)
        return MatrixCursor(columns, 0)
    }
}
