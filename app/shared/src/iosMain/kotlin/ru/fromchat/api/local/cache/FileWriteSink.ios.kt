package ru.fromchat.api.local.cache

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fwrite

@OptIn(ExperimentalForeignApi::class)
internal actual class FileWriteSink actual constructor(
    path: String,
    append: Boolean,
) : AutoCloseable {
    private val file = fopen(
        path,
        if (append) "ab" else "wb",
    ) ?: error("Failed to open file for write: $path")

    actual fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        buffer.usePinned { pinned ->
            val written = fwrite(
                pinned.addressOf(offset),
                1.convert(),
                length.convert(),
                file,
            ).toInt()
            if (written != length) {
                error("Short write ($written of $length bytes)")
            }
        }
    }

    actual fun flush() {
        fflush(file)
    }

    actual override fun close() {
        fflush(file)
        fclose(file)
    }
}
