package com.namchamvinhcuu.signagekiosk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.MessageDigest
import kotlin.math.min

class UpdateResultTest {

    private val now = 1_000_000_000_000L
    private val six = 6 * 3_600_000L
    private val day = 24 * 3_600_000L

    // ---- UpdateResult ----

    @Test
    fun `nextState per outcome`() {
        val st = UpdateState(backoffUntilMs = 5L, verifyFailedCode = 2, verifyFailedAtMs = 3L)
        assertEquals(st, UpdateResult.nextState(UpdateResult.Outcome.SUCCESS, st, now))
        assertEquals(now + day, UpdateResult.nextState(UpdateResult.Outcome.PENDING_USER_ACTION, st, now).backoffUntilMs)
        assertEquals(now + six, UpdateResult.nextState(UpdateResult.Outcome.ABORTED, st, now).backoffUntilMs)
        assertEquals(now + six, UpdateResult.nextState(UpdateResult.Outcome.FAILURE, st, now).backoffUntilMs)
    }

    @Test
    fun `aborted after pending never shortens the 24 hour backoff`() {
        val pending = UpdateResult.nextState(UpdateResult.Outcome.PENDING_USER_ACTION, UpdateState(), now)
        val after = UpdateResult.nextState(UpdateResult.Outcome.ABORTED, pending, now + 1000)
        assertEquals(pending.backoffUntilMs, after.backoffUntilMs)
    }

    @Test
    fun `clearsPending is true for everything but success`() {
        assertFalse(UpdateResult.clearsPending(UpdateResult.Outcome.SUCCESS))
        assertTrue(UpdateResult.clearsPending(UpdateResult.Outcome.PENDING_USER_ACTION))
        assertTrue(UpdateResult.clearsPending(UpdateResult.Outcome.ABORTED))
        assertTrue(UpdateResult.clearsPending(UpdateResult.Outcome.FAILURE))
    }

    @Test
    fun `shouldRecord blocks only an aborted that follows a pending record of the same code`() {
        val p = UpdateResult.PENDING_USER_ACTION_NAME
        assertFalse(UpdateResult.shouldRecord(UpdateResult.Outcome.ABORTED, p, 7, 7))
        assertTrue(UpdateResult.shouldRecord(UpdateResult.Outcome.ABORTED, p, 7, 8))
        assertTrue(UpdateResult.shouldRecord(UpdateResult.Outcome.ABORTED, "failure", 7, 7))
        assertTrue(UpdateResult.shouldRecord(UpdateResult.Outcome.ABORTED, null, 0, 7))
        assertTrue(UpdateResult.shouldRecord(UpdateResult.Outcome.FAILURE, p, 7, 7))
        assertTrue(UpdateResult.shouldRecord(UpdateResult.Outcome.SUCCESS, p, 7, 7))
        assertTrue(UpdateResult.shouldRecord(UpdateResult.Outcome.PENDING_USER_ACTION, p, 7, 7))
        assertEquals("pending_user_action", p)
    }

    // ---- OutcomeDedupe ----

    @Test
    fun `identical deduped events are sent once`() {
        val d = OutcomeDedupe()
        val f = mapOf("reason" to "up_to_date")
        assertTrue(d.shouldSend("skip", f, true))
        assertFalse(d.shouldSend("skip", f, true))
        assertFalse(d.shouldSend("skip", mapOf("reason" to "up_to_date"), true))
    }

    @Test
    fun `different stage or fields are sent`() {
        val d = OutcomeDedupe()
        assertTrue(d.shouldSend("skip", mapOf("reason" to "a"), true))
        assertTrue(d.shouldSend("skip", mapOf("reason" to "b"), true))
        assertTrue(d.shouldSend("other", mapOf("reason" to "b"), true))
    }

    @Test
    fun `non-deduped events always go out and reset the memory`() {
        val d = OutcomeDedupe()
        val f = mapOf("reason" to "a")
        assertTrue(d.shouldSend("skip", f, true))
        assertTrue(d.shouldSend("skip", f, false))
        assertTrue(d.shouldSend("skip", f, false))
        assertTrue(d.shouldSend("download", emptyMap(), false))
        // memory was reset by the non-deduped event: the same skip is new again
        assertTrue(d.shouldSend("skip", f, true))
        assertFalse(d.shouldSend("skip", f, true))
    }

    // ---- UpdateIo.copyCapped / hex ----

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    /** Returns at most [chunk] bytes per read, so the cap lands between reads. */
    private fun chunked(data: ByteArray, chunk: Int): InputStream = object : InputStream() {
        var pos = 0
        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = min(min(chunk, len), data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    @Test
    fun `copyCapped copies exactly max bytes and hashes them`() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val md = MessageDigest.getInstance("SHA-256")
        val total = UpdateIo.copyCapped(ByteArrayInputStream(data), out, data.size.toLong(), md)
        assertEquals(data.size.toLong(), total)
        assertArrayEquals(data, out.toByteArray())
        assertArrayEquals(sha(data), md.digest())
    }

    @Test
    fun `copyCapped over max returns more than max and writes at most max with a matching hash`() {
        val data = ByteArray(1000) { it.toByte() }
        val out = ByteArrayOutputStream()
        val md = MessageDigest.getInstance("SHA-256")
        val total = UpdateIo.copyCapped(chunked(data, 10), out, 25L, md)
        assertTrue("total=$total", total > 25L)
        assertTrue(out.size() <= 25)
        assertEquals(20, out.size())
        assertArrayEquals(sha(out.toByteArray()), md.digest())
    }

    @Test
    fun `copyCapped with one big read over max writes nothing beyond max`() {
        val data = ByteArray(500)
        val out = ByteArrayOutputStream()
        val total = UpdateIo.copyCapped(ByteArrayInputStream(data), out, 100L, MessageDigest.getInstance("SHA-256"))
        assertTrue(total > 100L)
        assertTrue(out.size() <= 100)
    }

    @Test
    fun `copyCapped of an empty source returns zero`() {
        val out = ByteArrayOutputStream()
        assertEquals(0L, UpdateIo.copyCapped(ByteArrayInputStream(ByteArray(0)), out, 10L, MessageDigest.getInstance("SHA-256")))
        assertEquals(0, out.size())
    }

    @Test
    fun `copyCapped throws InterruptedIOException when the thread is interrupted`() {
        Thread.currentThread().interrupt()
        try {
            UpdateIo.copyCapped(ByteArrayInputStream(ByteArray(10)), ByteArrayOutputStream(), 100L, MessageDigest.getInstance("SHA-256"))
            fail("expected InterruptedIOException")
        } catch (e: InterruptedIOException) {
            // expected
        } finally {
            Thread.interrupted() // clear the flag so other tests are unaffected
        }
    }

    @Test
    fun `hex is lowercase two digits per byte`() {
        assertEquals("000fffa0", UpdateIo.hex(byteArrayOf(0x00, 0x0f, 0xff.toByte(), 0xa0.toByte())))
        assertEquals("", UpdateIo.hex(ByteArray(0)))
    }
}
