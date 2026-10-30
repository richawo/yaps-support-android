package ai.yaps.support.internal.outbox

import ai.yaps.support.internal.core.Backoff
import ai.yaps.support.internal.protocol.WireJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

@Serializable
internal data class PendingAttachment(
    /** Absolute path of the prepared (downscaled) copy inside the SDK's no-backup directory. */
    val localPath: String,
    val kind: String,
    val contentType: String,
    val size: Long,
    val width: Int? = null,
    val height: Int? = null,
    val filename: String? = null,
    /** Set once the bytes are uploaded, so a retry never uploads the same file twice. */
    val uploadId: String? = null,
)

@Serializable
internal enum class OutboxState { PENDING, SENDING, FAILED }

@Serializable
internal data class OutboxEntry(
    val clientId: String,
    val text: String,
    val attachments: List<PendingAttachment> = emptyList(),
    val createdAt: Long,
    val attempts: Int = 0,
    val state: OutboxState = OutboxState.PENDING,
    val nextAttemptAt: Long = 0,
    /** PROTOCOL §1 error code of the last failure, if any. */
    val lastError: String? = null,
)

internal interface OutboxPersistence {
    fun load(): List<OutboxEntry>
    fun save(entries: List<OutboxEntry>)
}

/** Stores the outbox as one JSON file, written atomically (temp file + rename). */
internal class FileOutboxPersistence(private val file: File) : OutboxPersistence {
    private val serializer = ListSerializer(OutboxEntry.serializer())

    override fun load(): List<OutboxEntry> = try {
        if (file.exists()) WireJson.decodeFromString(serializer, file.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    override fun save(entries: List<OutboxEntry>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(WireJson.encodeToString(serializer, entries))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Exception) {
            // Best effort: the in-memory outbox keeps working; persistence resumes on the next write.
        }
    }
}

/**
 * Durable send queue. PROTOCOL §3.6: persist first, then send, retry with backoff, mark failed after
 * [maxAttempts] attempts or on a non-retryable 4xx. The server deduplicates on `clientId`, so a
 * retry after an unknown outcome (timeout, process death) is always safe.
 */
internal class Outbox(
    private val persistence: OutboxPersistence,
    private val clock: () -> Long = System::currentTimeMillis,
    private val backoff: Backoff = Backoff(baseMillis = 1_000, capMillis = 60_000),
    val maxAttempts: Int = 5,
) {
    private val lock = Any()
    private val _entries = MutableStateFlow(
        // Anything that was mid-send when the process died is retried.
        persistence.load().map { if (it.state == OutboxState.SENDING) it.copy(state = OutboxState.PENDING) else it },
    )
    val entries: StateFlow<List<OutboxEntry>> = _entries.asStateFlow()

    val hasPending: Boolean get() = _entries.value.any { it.state != OutboxState.FAILED }

    /** Adds an entry. Returns `false` (and changes nothing) if [entry]'s clientId is already queued. */
    fun enqueue(entry: OutboxEntry): Boolean = mutate { list ->
        if (list.any { it.clientId == entry.clientId }) null else list + entry
    }

    fun get(clientId: String): OutboxEntry? = _entries.value.firstOrNull { it.clientId == clientId }

    /** Pending entries whose backoff has elapsed, oldest first. */
    fun due(now: Long = clock()): List<OutboxEntry> =
        _entries.value.filter { it.state == OutboxState.PENDING && it.nextAttemptAt <= now }.sortedBy { it.createdAt }

    /** Earliest time a pending entry becomes due, or `null` when nothing is waiting. */
    fun nextWakeAt(): Long? = _entries.value.filter { it.state == OutboxState.PENDING }.minOfOrNull { it.nextAttemptAt }

    fun markSending(clientId: String): Boolean = update(clientId) {
        if (it.state == OutboxState.PENDING) it.copy(state = OutboxState.SENDING) else null
    }

    fun markUploaded(clientId: String, index: Int, uploadId: String) {
        update(clientId) { e ->
            if (index !in e.attachments.indices) null
            else e.copy(attachments = e.attachments.mapIndexed { i, a -> if (i == index) a.copy(uploadId = uploadId) else a })
        }
    }

    /**
     * Records a failed attempt. Retryable failures back off (or wait [retryAfterMillis] for a 429);
     * the entry becomes [OutboxState.FAILED] after [maxAttempts] or immediately when not retryable.
     */
    fun recordFailure(clientId: String, code: String, retryable: Boolean, retryAfterMillis: Long? = null): OutboxEntry? {
        var result: OutboxEntry? = null
        update(clientId) { e ->
            val attempts = e.attempts + 1
            val failed = !retryable || attempts >= maxAttempts
            e.copy(
                attempts = attempts,
                state = if (failed) OutboxState.FAILED else OutboxState.PENDING,
                nextAttemptAt = if (failed) 0 else clock() + (retryAfterMillis ?: backoff.delayFor(attempts)),
                lastError = code,
            ).also { result = it }
        }
        return result
    }

    /** Puts an entry back without counting an attempt (e.g. the socket closed before we knew). */
    fun release(clientId: String) {
        update(clientId) { if (it.state == OutboxState.SENDING) it.copy(state = OutboxState.PENDING) else null }
    }

    /** User tapped Retry: start over with a fresh attempt budget. */
    fun retry(clientId: String): Boolean = update(clientId) {
        if (it.state == OutboxState.FAILED) it.copy(state = OutboxState.PENDING, attempts = 0, nextAttemptAt = 0, lastError = null) else null
    }

    /** Re-queues every failed entry whose error is [code] (e.g. after an unblock or a new identity). */
    fun retryAll(predicate: (OutboxEntry) -> Boolean) {
        mutate { list ->
            if (list.none { it.state == OutboxState.FAILED && predicate(it) }) null
            else list.map { if (it.state == OutboxState.FAILED && predicate(it)) it.copy(state = OutboxState.PENDING, attempts = 0, nextAttemptAt = 0, lastError = null) else it }
        }
    }

    /** The server confirmed this clientId: drop it. Returns the removed entry. */
    fun complete(clientId: String): OutboxEntry? {
        var removed: OutboxEntry? = null
        mutate { list ->
            removed = list.firstOrNull { it.clientId == clientId }
            if (removed == null) null else list.filterNot { it.clientId == clientId }
        }
        return removed
    }

    /** Drops every entry the server already has (seen in history, a `msg` frame or an `ack`). */
    fun reconcile(serverClientIds: Collection<String>): List<OutboxEntry> {
        if (serverClientIds.isEmpty()) return emptyList()
        val ids = serverClientIds.toHashSet()
        var removed: List<OutboxEntry> = emptyList()
        mutate { list ->
            removed = list.filter { it.clientId in ids }
            if (removed.isEmpty()) null else list.filterNot { it.clientId in ids }
        }
        return removed
    }

    fun remove(clientId: String): OutboxEntry? = complete(clientId)

    fun clear(): List<OutboxEntry> {
        val old = _entries.value
        mutate { if (it.isEmpty()) null else emptyList() }
        return old
    }

    private fun update(clientId: String, transform: (OutboxEntry) -> OutboxEntry?): Boolean = mutate { list ->
        val index = list.indexOfFirst { it.clientId == clientId }
        if (index < 0) return@mutate null
        val updated = transform(list[index]) ?: return@mutate null
        list.toMutableList().also { it[index] = updated }
    }

    /** Applies [transform] under the lock; `null` means "no change". Persists every change. */
    private fun mutate(transform: (List<OutboxEntry>) -> List<OutboxEntry>?): Boolean = synchronized(lock) {
        val next = transform(_entries.value) ?: return false
        _entries.value = next
        persistence.save(next)
        true
    }
}
