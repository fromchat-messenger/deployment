package ru.fromchat.api.schema.messages

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import ru.fromchat.api.schema.messages.dm.DmEnvelope
import ru.fromchat.api.schema.messages.dm.DmFile
import ru.fromchat.api.schema.websocket.types.ReactionData

@Serializable
data class Message(
    val id: Int,
    val user_id: Int,
    val content: String,
    val timestamp: String,
    val is_read: Boolean,
    val is_edited: Boolean,
    val username: String,
    val profile_picture: String? = null,
    val verified: Boolean? = null,
    val reply_to: Message? = null,
    val client_message_id: String? = null,
    val reactions: List<ReactionData>? = null,
    val files: List<DmFile>? = null,
    /** For optimistic UI: local URI when sending, null when confirmed. */
    val pendingFileUri: String? = null,
    /** For optimistic UI: filename when sending file (non-image), null when confirmed. */
    val pendingFilename: String? = null,
    /** For optimistic UI: aspect ratio (width/height) when sending image, null when confirmed. */
    val pendingFileAspectRatio: Float? = null,
    /** For optimistic UI: jobId to track upload progress. */
    val uploadJobId: String? = null,
    /** For optimistic UI: 0-100 upload progress, null when complete. */
    val uploadProgress: Int? = null,
    /** Set when outbound upload failed; use [UPLOAD_ERROR_FILE_TOO_LARGE] for localized copy. */
    @Transient val uploadError: String? = null,
    /** For DM file decryption; not serialized over network. */
    @Transient val dmEnvelope: DmEnvelope? = null,
    /** Blurhashes for image files (by index); from decrypted message JSON. */
    @Transient val fileThumbnails: List<String>? = null,
    /** Aspect ratios (width/height) for image files (by index); from decrypted message JSON. */
    @Transient val fileAspectRatios: List<Float>? = null,
    /** File sizes in bytes (by index); from decrypted message JSON. */
    @Transient val fileSizes: List<Long>? = null,
    /** Image dimensions (width, height) for image files (by index); from decrypted message JSON. */
    @Transient val fileDimensions: List<Pair<Int, Int>>? = null,
    /** True when DM plaintext could not be decrypted and [content] shows the corrupted placeholder. */
    @Transient val isContentCorrupted: Boolean = false
)