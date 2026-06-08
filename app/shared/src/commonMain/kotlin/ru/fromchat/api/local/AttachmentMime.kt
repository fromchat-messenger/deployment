package ru.fromchat.api.local

fun mimeTypeForFilename(filename: String): String {
    val ext = filename.substringAfterLast('.').lowercase()
    return when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "bmp" -> "image/bmp"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "txt" -> "text/plain"
        "json" -> "application/json"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "apk", "apks", "xapk", "apkm" -> "application/vnd.android.package-archive"
        "dmg" -> "application/x-apple-diskimage"
        else -> "application/octet-stream"
    }
}
