package ru.fromchat.core.cache

/** Original attachment URI is no longer readable (revoked permission, deleted file, etc.). */
class OutboundFileUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

fun Throwable.isOutboundFileUnavailable(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is OutboundFileUnavailableException) return true
        current = current.cause
    }
    return false
}
