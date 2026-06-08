package ru.fromchat.api.local.db

import com.pr0gramm3r101.utils.files.PlatformFileSystem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.fromchat.api.schema.messages.Message
import ru.fromchat.api.schema.messages.dm.DmEnvelope
import ru.fromchat.api.local.AttachmentMediaLog
import ru.fromchat.api.local.cache.DecryptedImageCache

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
private data class PersistedOptimisticOutboundPayload(
    @SerialName("text") val text: String,
    @SerialName("pendingFileUri") val pendingFileUri: String? = null,
    @SerialName("pendingFilename") val pendingFilename: String? = null,
    @SerialName("uploadJobId") val uploadJobId: String? = null,
    @SerialName("fileSizes") val fileSizes: List<Long>? = null,
)

@Serializable
private data class PersistedDmMessagePayload(
    @SerialName("text") val text: String,
    @SerialName("envelope") val envelope: DmEnvelope,
    @SerialName("fileThumbnails") val fileThumbnails: List<String>? = null,
    @SerialName("fileAspectRatios") val fileAspectRatios: List<Float>? = null,
    @SerialName("fileSizes") val fileSizes: List<Long>? = null,
    @SerialName("fileDimensions") val fileDimensions: List<List<Int>>? = null,
    @SerialName("isContentCorrupted") val isContentCorrupted: Boolean = false,
    /** Local decrypted preview file (survives outbox / upload staging cleanup). */
    @SerialName("localPreviewUri") val localPreviewUri: String? = null,
)

data class ParsedDmMessageContent(
    val text: String,
    val envelope: DmEnvelope? = null,
    val fileThumbnails: List<String>? = null,
    val fileAspectRatios: List<Float>? = null,
    val fileSizes: List<Long>? = null,
    val fileDimensions: List<Pair<Int, Int>>? = null,
    val isContentCorrupted: Boolean = false,
    val localPreviewUri: String? = null,
    val pendingFileUri: String? = null,
    val pendingFilename: String? = null,
    val uploadJobId: String? = null,
)

/** Persists in-flight attachment fields so SQLDelight reload keeps the file row UI. */
fun encodeOptimisticOutboundMessage(message: Message): String {
    val pendingUri = message.pendingFileUri?.trim().orEmpty()
    if (pendingUri.isEmpty()) return message.content
    return json.encodeToString(
        PersistedOptimisticOutboundPayload(
            text = message.content,
            pendingFileUri = pendingUri,
            pendingFilename = message.pendingFilename?.trim()?.takeIf { it.isNotEmpty() },
            uploadJobId = message.uploadJobId?.trim()?.takeIf { it.isNotEmpty() },
            fileSizes = message.fileSizes,
        ),
    )
}

fun resolveLocalPreviewUri(message: Message): String? {
    message.pendingFileUri?.takeIf { uri ->
        DecryptedImageCache.isDecryptedImageCacheUri(uri) && localPreviewFileExists(uri)
    }?.let { return it }
    val cid = message.client_message_id?.trim()?.takeIf { it.isNotEmpty() }
    if (cid != null) {
        DecryptedImageCache.getCached(message.id, fileIndex = 0, cid)
            ?.takeIf { localPreviewFileExists(it) }
            ?.let { return it }
    }
    if (message.id > 0) {
        DecryptedImageCache.getCached(message.id, fileIndex = 0, clientMessageId = null)
            ?.takeIf { localPreviewFileExists(it) }
            ?.let { return it }
    }
    return null
}

/**
 * Layout width/height. Pixel pairs keep [w,h] order (landscape vs portrait).
 * Do not min/max swap — that forces every landscape 4K image into portrait.
 */
internal fun attachmentDimensionsForLayout(w: Int, h: Int): Pair<Int, Int> {
    if (w <= 0 || h <= 0) return 1 to 1
    return w to h
}

internal fun aspectRatioFromDimensionPair(w: Int, h: Int): Float {
    val (dw, dh) = attachmentDimensionsForLayout(w, h)
    return dw.toFloat() / dh.toFloat()
}

private fun localPreviewFileExists(uri: String): Boolean {
    val path = uri.removePrefix("file://")
    return path.isNotEmpty() && PlatformFileSystem.exists(path)
}

fun encodePersistedDmMessage(message: Message): String {
    val envelope = message.dmEnvelope
        ?: return message.content
    if (message.files.isNullOrEmpty()) return message.content
    val dims = message.fileDimensions?.map { listOf(it.first, it.second) }
    val payload = PersistedDmMessagePayload(
        text = message.content,
        envelope = envelope,
        fileThumbnails = message.fileThumbnails,
        fileAspectRatios = message.fileAspectRatios,
        fileSizes = message.fileSizes,
        fileDimensions = dims,
        isContentCorrupted = message.isContentCorrupted,
        localPreviewUri = resolveLocalPreviewUri(message),
    )
    AttachmentMediaLog.persist(
        "encode",
        "msgId" to message.id,
        "clientId" to message.client_message_id,
        "localPreview" to (payload.localPreviewUri?.take(64) ?: "null"),
        "dims" to (dims?.firstOrNull()?.joinToString("x") ?: "null"),
    )
    return json.encodeToString(payload)
}

fun parseDmMessageContent(plaintext: String): ParsedDmMessageContent {
    val trimmed = plaintext.trim()
    if (trimmed.startsWith("{")) {
        val root = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
        if (root?.containsKey("pendingFileUri") == true) {
            return runCatching {
                val payload = json.decodeFromString<PersistedOptimisticOutboundPayload>(trimmed)
                ParsedDmMessageContent(
                    text = payload.text,
                    pendingFileUri = payload.pendingFileUri?.takeIf { it.isNotBlank() },
                    pendingFilename = payload.pendingFilename?.takeIf { it.isNotBlank() },
                    uploadJobId = payload.uploadJobId?.takeIf { it.isNotBlank() },
                    fileSizes = payload.fileSizes,
                )
            }.getOrElse {
                ParsedDmMessageContent(text = plaintext)
            }
        }
        val isPersistedEnvelope = root?.containsKey("envelope") == true
        if (isPersistedEnvelope) {
            return runCatching {
                val payload = json.decodeFromString<PersistedDmMessagePayload>(trimmed)
                ParsedDmMessageContent(
                    text = payload.text,
                    envelope = payload.envelope,
                    fileThumbnails = payload.fileThumbnails,
                    fileAspectRatios = payload.fileAspectRatios,
                    fileSizes = payload.fileSizes,
                    fileDimensions = payload.fileDimensions?.mapNotNull { pair ->
                        if (pair.size >= 2) pair[0] to pair[1] else null
                    },
                    isContentCorrupted = payload.isContentCorrupted,
                    localPreviewUri = payload.localPreviewUri?.takeIf { localPreviewFileExists(it) },
                )
            }.getOrElse {
                ParsedDmMessageContent(text = plaintext)
            }
        }
        return parseLegacyDmContentJson(trimmed)
    }
    return ParsedDmMessageContent(text = trimmed)
}

private fun parseLegacyDmContentJson(plaintext: String): ParsedDmMessageContent {
    if (!plaintext.startsWith("{")) {
        return ParsedDmMessageContent(text = plaintext)
    }
    return runCatching {
        val obj = json.parseToJsonElement(plaintext).jsonObject
        val text = obj["text"]?.jsonPrimitive?.content ?: plaintext
        val thumbArr = obj["fileThumbnails"]?.jsonArray ?: return@runCatching ParsedDmMessageContent(text)
        val thumbnails = thumbArr.map { it.jsonPrimitive.content }
        val arArr = obj["fileAspectRatios"]?.jsonArray
        val parsed = arArr?.mapNotNull { elem ->
            val a = elem as? JsonArray ?: return@mapNotNull null
            if (a.size == 2) {
                val w = (a.getOrNull(0) as? JsonPrimitive)?.content?.toIntOrNull()
                val h = (a.getOrNull(1) as? JsonPrimitive)?.content?.toIntOrNull()
                if (w != null && h != null && h > 0) {
                    Triple(w, h, aspectRatioFromDimensionPair(w, h))
                } else {
                    null
                }
            } else {
                null
            }
        }?.takeIf { it.size == thumbnails.size }
        val sizesArr = obj["fileSizes"]?.jsonArray
        val fileSizes = sizesArr?.mapNotNull { (it as? JsonPrimitive)?.content?.toLongOrNull() }
            ?.takeIf { it.size == thumbnails.size }
        ParsedDmMessageContent(
            text = text,
            fileThumbnails = thumbnails.ifEmpty { null },
            fileAspectRatios = parsed?.map { it.third },
            fileSizes = fileSizes,
            fileDimensions = parsed?.map { it.first to it.second },
        )
    }.getOrElse {
        ParsedDmMessageContent(text = plaintext)
    }
}
