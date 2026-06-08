package ru.fromchat.api.local.cache

/**
 * Buffered file writer for streaming HTTP downloads (single open, chunked writes).
 */
internal expect class FileWriteSink(path: String, append: Boolean) : AutoCloseable {
    fun write(buffer: ByteArray, offset: Int, length: Int)
    fun flush()
    override fun close()
}