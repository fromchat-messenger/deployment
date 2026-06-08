package ru.fromchat.ui.chat.utils

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import ru.fromchat.api.local.db.aspectRatioFromDimensionPair
import ru.fromchat.api.local.download.ChatPreviewDecodeSize

/** Bubble top radius (must match [ru.fromchat.ui.chat.MessageItem] bubble shape). */
private val BUBBLE_TOP = 20.dp

/** Padding between bubble edge and attachment image (must match MessageItem image padding). */
internal val ATTACHMENT_IMAGE_INSET = 2.dp

/** Slight rounding on image preview bottom corners (less than bubble). */
private val IMAGE_BOTTOM_CORNER = 4.dp

/** Inner clip: top corners follow bubble minus inset; bottom corners lightly rounded. */
@Suppress("UNUSED_PARAMETER")
internal fun attachmentImageCornerShape(isAuthor: Boolean): RoundedCornerShape {
    val inset = ATTACHMENT_IMAGE_INSET
    return RoundedCornerShape(
        topStart = (BUBBLE_TOP - inset).coerceAtLeast(0.dp),
        topEnd = (BUBBLE_TOP - inset).coerceAtLeast(0.dp),
        bottomStart = IMAGE_BOTTOM_CORNER,
        bottomEnd = IMAGE_BOTTOM_CORNER,
    )
}

/** Width / height for layout and decode. Pixel dimensions and server ratios beat stale pending. */
internal fun imageAspectRatioForMessage(
    fileAspectRatios: List<Float>?,
    fileDimensions: List<Pair<Int, Int>>?,
    pendingFileAspectRatio: Float?,
    fileIndex: Int = 0,
    @Suppress("UNUSED_PARAMETER") confirmed: Boolean = true,
    @Suppress("UNUSED_PARAMETER") hasLocalPreview: Boolean = false,
): Float? {
    fileDimensions?.getOrNull(fileIndex)?.let { (w, h) ->
        if (w > 0 && h > 0) {
            return aspectRatioFromDimensionPair(w, h)
        }
    }
    fileAspectRatios?.getOrNull(fileIndex)?.takeIf { it > 0f }?.let { return it }
    return pendingFileAspectRatio?.takeIf { fileIndex == 0 && it > 0f }
}

internal fun coalesceDecodeTarget(vararg sizes: ChatPreviewDecodeSize?): ChatPreviewDecodeSize {
    val present = sizes.filterNotNull()
    require(present.isNotEmpty())
    return ChatPreviewDecodeSize(
        widthPx = present.maxOf { it.widthPx },
        heightPx = present.maxOf { it.heightPx },
    )
}

internal fun decodeSizeChangedMeaningfully(
    previous: ChatPreviewDecodeSize?,
    measured: ChatPreviewDecodeSize,
): Boolean {
    if (previous == null) return true
    val wRatio = measured.widthPx.toFloat() / previous.widthPx.toFloat()
    val hRatio = measured.heightPx.toFloat() / previous.heightPx.toFloat()
    return wRatio > 1.12f || hRatio > 1.12f || wRatio < 0.88f || hRatio < 0.88f
}
