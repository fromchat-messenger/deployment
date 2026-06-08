package ru.fromchat.api.local.messages

import ru.fromchat.api.local.AttachmentMediaLog

/**
 * Coalesces download progress for UI (~display refresh rate) and system notifications (≤1/s).
 */
internal class DownloadProgressThrottle(
    private val uiFrameMs: Long = 16L,
    private val notificationIntervalMs: Long = 1_000L,
) {
    private var lastUiAtMs = 0L
    private var lastUiPercent = Int.MIN_VALUE
    private var lastNotifAtMs = 0L
    private var lastNotifPercent = Int.MIN_VALUE

    fun shouldPublishUi(percent: Int, nowMs: Long = AttachmentMediaLog.nowMs()): Boolean {
        val pct = percent.coerceIn(0, 100)
        if (pct == lastUiPercent) return false
        if (pct !in 2..<100 || lastUiAtMs == 0L || nowMs - lastUiAtMs >= uiFrameMs) {
            lastUiPercent = pct
            lastUiAtMs = nowMs
            return true
        }
        return false
    }

    fun shouldPublishNotification(percent: Int, nowMs: Long = AttachmentMediaLog.nowMs()): Boolean {
        val pct = percent.coerceIn(0, 100)
        if (pct !in 2..<100 || lastNotifAtMs == 0L || nowMs - lastNotifAtMs >= notificationIntervalMs) {
            if (pct != lastNotifPercent || nowMs - lastNotifAtMs >= notificationIntervalMs) {
                lastNotifPercent = pct
                lastNotifAtMs = nowMs
                return true
            }
        }
        return false
    }

    fun reset() {
        lastUiAtMs = 0L
        lastUiPercent = Int.MIN_VALUE
        lastNotifAtMs = 0L
        lastNotifPercent = Int.MIN_VALUE
    }
}
