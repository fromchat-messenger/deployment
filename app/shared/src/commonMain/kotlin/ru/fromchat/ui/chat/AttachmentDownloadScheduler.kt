package ru.fromchat.ui.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Limits concurrent DM attachment decrypt/download work to [MAX_PARALLEL].
 * Additional requests wait in a priority queue (visible messages first).
 */
object AttachmentDownloadScheduler {
    private const val MAX_PARALLEL = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private data class Pending(
        val storageKey: String,
        val messageId: Int,
        val enqueuedAt: Long,
        val work: suspend () -> String?,
        val result: CompletableDeferred<String?>,
    )

    private val waiting = mutableListOf<Pending>()
    private val keyToDeferred = mutableMapOf<String, CompletableDeferred<String?>>()
    private var activeCount = 0

    /**
     * Runs [work] when a download slot is available. Duplicate [storageKey] shares one result.
     */
    suspend fun run(
        storageKey: String,
        messageId: Int,
        work: suspend () -> String?,
    ): String? {
        val deferred = mutex.withLock {
            keyToDeferred[storageKey] ?: run {
                val created = CompletableDeferred<String?>()
                keyToDeferred[storageKey] = created
                waiting.add(
                    Pending(
                        storageKey = storageKey,
                        messageId = messageId,
                        enqueuedAt = AttachmentMediaLog.nowMs(),
                        work = work,
                        result = created,
                    ),
                )
                sortWaitingLocked()
                created
            }
        }
        pumpLocked()
        return deferred.await()
    }

    fun reprioritize() {
        scope.launch {
            mutex.withLock {
                sortWaitingLocked()
            }
            pumpLocked()
        }
    }

    private suspend fun pumpLocked() {
        val toStart = mutex.withLock {
            val jobs = mutableListOf<Pending>()
            while (activeCount < MAX_PARALLEL && waiting.isNotEmpty()) {
                val next = waiting.removeAt(0)
                activeCount++
                jobs.add(next)
            }
            jobs
        }
        for (pending in toStart) {
            scope.launch {
                runPending(pending)
            }
        }
    }

    private suspend fun runPending(pending: Pending) {
        val outcome = runCatching { pending.work() }
        mutex.withLock {
            activeCount = (activeCount - 1).coerceAtLeast(0)
            keyToDeferred.remove(pending.storageKey)
        }
        outcome.fold(
            onSuccess = { pending.result.complete(it) },
            onFailure = { pending.result.completeExceptionally(it) },
        )
        pumpLocked()
    }

    private fun sortWaitingLocked() {
        waiting.sortWith(
            compareBy<Pending> { pending ->
                if (AttachmentDownloadVisibility.isPrioritized(pending.messageId)) 0 else 1
            }.thenBy { it.enqueuedAt },
        )
    }
}
