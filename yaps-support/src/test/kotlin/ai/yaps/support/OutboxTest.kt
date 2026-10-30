package ai.yaps.support

import ai.yaps.support.internal.core.Backoff
import ai.yaps.support.internal.outbox.FileOutboxPersistence
import ai.yaps.support.internal.outbox.Outbox
import ai.yaps.support.internal.outbox.OutboxEntry
import ai.yaps.support.internal.outbox.OutboxPersistence
import ai.yaps.support.internal.outbox.OutboxState
import ai.yaps.support.internal.outbox.PendingAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class OutboxTest {

    private class MemoryPersistence(var saved: List<OutboxEntry> = emptyList()) : OutboxPersistence {
        var writes = 0
        override fun load() = saved
        override fun save(entries: List<OutboxEntry>) {
            saved = entries
            writes++
        }
    }

    private var now = 1_000_000L
    private fun outbox(p: OutboxPersistence = MemoryPersistence()) =
        Outbox(p, clock = { now }, backoff = Backoff(baseMillis = 1_000, capMillis = 60_000, jitter = 0.0))

    private fun entry(cid: String, text: String = "hi") = OutboxEntry(clientId = cid, text = text, createdAt = now)

    @Test
    fun persistsBeforeSending() {
        val p = MemoryPersistence()
        val o = outbox(p)
        assertTrue(o.enqueue(entry("A")))
        assertEquals(listOf("A"), p.saved.map { it.clientId })
    }

    @Test
    fun duplicateClientIdsAreDeduplicated() {
        val p = MemoryPersistence()
        val o = outbox(p)
        assertTrue(o.enqueue(entry("A", "first")))
        assertFalse(o.enqueue(entry("A", "second")))
        assertEquals(1, o.entries.value.size)
        assertEquals("first", o.entries.value.single().text)
        assertEquals(1, p.writes)
    }

    @Test
    fun serverEchoesReconcileAwayQueuedEntries() {
        val o = outbox()
        o.enqueue(entry("A"))
        o.enqueue(entry("B"))
        val removed = o.reconcile(listOf("A", "Z"))
        assertEquals(listOf("A"), removed.map { it.clientId })
        assertEquals(listOf("B"), o.entries.value.map { it.clientId })
        assertTrue(o.reconcile(emptyList()).isEmpty())
    }

    @Test
    fun retryableFailuresBackOffExponentiallyThenFailAfterFiveAttempts() {
        val o = outbox()
        o.enqueue(entry("A"))
        val delays = mutableListOf<Long>()
        repeat(4) {
            assertEquals(listOf("A"), o.due().map { it.clientId })
            assertTrue(o.markSending("A"))
            assertTrue(o.due().isEmpty())
            val e = o.recordFailure("A", "server_error", retryable = true)!!
            assertEquals(OutboxState.PENDING, e.state)
            delays += e.nextAttemptAt - now
            assertTrue("not due before its backoff elapses", o.due().isEmpty())
            now = e.nextAttemptAt
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), delays)
        o.markSending("A")
        val last = o.recordFailure("A", "server_error", retryable = true)!!
        assertEquals(5, last.attempts)
        assertEquals(OutboxState.FAILED, last.state)
        assertTrue(o.due(now + 3_600_000).isEmpty())
        assertNull(o.nextWakeAt())
    }

    @Test
    fun rateLimitWaitsForRetryAfter() {
        val o = outbox()
        o.enqueue(entry("A"))
        o.markSending("A")
        val e = o.recordFailure("A", "rate_limited", retryable = true, retryAfterMillis = 5_000)!!
        assertEquals(now + 5_000, e.nextAttemptAt)
        assertEquals(now + 5_000, o.nextWakeAt())
    }

    @Test
    fun nonRetryable4xxFailsImmediatelyAndRetryResetsTheBudget() {
        val o = outbox()
        o.enqueue(entry("A"))
        o.markSending("A")
        val e = o.recordFailure("A", "blocked", retryable = false)!!
        assertEquals(OutboxState.FAILED, e.state)
        assertEquals("blocked", e.lastError)
        assertTrue(o.retry("A"))
        val r = o.get("A")!!
        assertEquals(OutboxState.PENDING, r.state)
        assertEquals(0, r.attempts)
        assertEquals(listOf("A"), o.due().map { it.clientId })
        assertFalse("retry only applies to failed entries", o.retry("A"))
    }

    @Test
    fun retryAllRequeuesMatchingFailures() {
        val o = outbox()
        o.enqueue(entry("A")); o.enqueue(entry("B"))
        o.markSending("A"); o.recordFailure("A", "verification_required", retryable = false)
        o.markSending("B"); o.recordFailure("B", "too_large", retryable = false)
        o.retryAll { it.lastError == "verification_required" }
        assertEquals(OutboxState.PENDING, o.get("A")!!.state)
        assertEquals(OutboxState.FAILED, o.get("B")!!.state)
    }

    @Test
    fun inFlightEntriesAreRetriedAfterProcessDeath() {
        val p = MemoryPersistence()
        outbox(p).apply { enqueue(entry("A")); markSending("A") }
        assertEquals(OutboxState.SENDING, p.saved.single().state)
        val revived = outbox(p)
        assertEquals(OutboxState.PENDING, revived.get("A")!!.state)
        assertEquals(listOf("A"), revived.due().map { it.clientId })
    }

    @Test
    fun uploadedAttachmentsAreRememberedSoRetriesDoNotReupload() {
        val o = outbox()
        val att = PendingAttachment(localPath = "/x/1.jpg", kind = "image", contentType = "image/jpeg", size = 10)
        o.enqueue(entry("A").copy(attachments = listOf(att, att.copy(localPath = "/x/2.jpg"))))
        o.markUploaded("A", 1, "upl_2")
        assertEquals(listOf(null, "upl_2"), o.get("A")!!.attachments.map { it.uploadId })
    }

    @Test
    fun completeRemovesAndReturnsTheEntry() {
        val o = outbox()
        o.enqueue(entry("A"))
        assertEquals("A", o.complete("A")?.clientId)
        assertNull(o.complete("A"))
        assertFalse(o.hasPending)
    }

    @Test
    fun filePersistenceRoundTripsAndSurvivesCorruption() {
        val dir = Files.createTempDirectory("outbox").toFile()
        val file = dir.resolve("outbox.json")
        val p = FileOutboxPersistence(file)
        val o = outbox(p)
        o.enqueue(entry("A", "héllo 🐓"))
        val reloaded = FileOutboxPersistence(file).load()
        assertEquals("héllo 🐓", reloaded.single().text)
        file.writeText("{corrupt")
        assertTrue(FileOutboxPersistence(file).load().isEmpty())
        dir.deleteRecursively()
    }
}
