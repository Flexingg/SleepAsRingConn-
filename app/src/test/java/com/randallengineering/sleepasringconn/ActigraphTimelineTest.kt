package com.randallengineering.sleepasringconn

import com.randallengineering.sleepasringconn.protocol.BulkRecord
import com.randallengineering.sleepasringconn.protocol.RingAuth
import com.randallengineering.sleepasringconn.protocol.RingProtocol
import com.randallengineering.sleepasringconn.sleepasandroid.ActigraphTimeline
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActigraphTimelineTest {

    private val epochMs = BulkRecord.EPOCH_SECONDS * 1000L

    /** A sleep-vitals epoch starting at [startMs] with the given five 30 s motion counts. */
    private fun epoch(startMs: Long, motion: IntArray, baseline: Int = 1): BulkRecord {
        val c = startMs / 1000 - RingProtocol.SYNC_EPOCH
        val raw = ByteArray(23)
        raw[0] = (c ushr 24).toByte(); raw[1] = (c ushr 16).toByte(); raw[2] = (c ushr 8).toByte(); raw[3] = c.toByte()
        raw[4] = 58; raw[5] = 42; raw[6] = 6; raw[7] = (14 * 8).toByte(); raw[8] = 96; raw[9] = 0x0A
        for (i in 0 until 5) raw[10 + i] = (motion[i] + baseline - 1).toByte()
        return BulkRecord.parseRecord(raw)!!
    }

    private fun aligned(ms: Long) = ms - ms % epochMs

    @Test
    fun emitsExactlyOneValuePerTenSecondsAndNeverZero() {
        val t0 = aligned(System.currentTimeMillis() - 3_600_000L)
        val tl = ActigraphTimeline()
        tl.start(t0)
        // Warm the resting floor with an hour of stillness before the night.
        tl.ingest((-24 until 0).map { epoch(t0 + it * epochMs, intArrayOf(1, 1, 1, 1, 1)) }, t0)

        val night = (0 until 8).map { epoch(t0 + it * epochMs, intArrayOf(1, 1, 1, 1, 1)) }
        tl.ingest(night, t0 + 8 * epochMs + 60_000)
        val out = tl.drain(t0 + 8 * epochMs + 60_000)

        assertEquals("8 epochs x 15 slots", 120, out.size)
        assertTrue(out.all { it > 0f })
        assertTrue(out.all { it == ActigraphTimeline.STILL_VALUE })
        // Nothing new: nothing emitted, nothing repeated.
        assertEquals(0, tl.drain(t0 + 8 * epochMs + 60_000).size)
    }

    @Test
    fun movementLandsInItsOwnThirtySecondBin() {
        val t0 = aligned(System.currentTimeMillis() - 3_600_000L)
        val tl = ActigraphTimeline()
        tl.start(t0)
        tl.ingest((-24 until 0).map { epoch(t0 + it * epochMs, intArrayOf(1, 1, 1, 1, 1)) }, t0)
        tl.ingest(listOf(epoch(t0, intArrayOf(1, 1, 41, 1, 1))), t0 + epochMs)
        val out = tl.drain(t0 + epochMs)

        assertEquals(15, out.size)
        val moving = ActigraphTimeline.toSaaValue(40)
        // Bin 2 = slots 6, 7, 8 (60..90 s into the epoch); everything else still.
        out.forEachIndexed { i, v ->
            if (i in 6..8) assertEquals("slot $i", moving, v, 1e-6f)
            else assertEquals("slot $i", ActigraphTimeline.STILL_VALUE, v, 1e-6f)
        }
    }

    @Test
    fun gen3RestingBaselineIsSubtracted() {
        val t0 = aligned(System.currentTimeMillis() - 3_600_000L)
        val tl = ActigraphTimeline()
        tl.start(t0)
        val still = IntArray(5) { 1 }
        tl.ingest((-24 until 4).map { epoch(t0 + it * epochMs, still, baseline = 15) }, t0 + 4 * epochMs)
        val out = tl.drain(t0 + 4 * epochMs)
        assertEquals(60, out.size)
        assertTrue("a resting Gen 3 must read as still", out.all { it == ActigraphTimeline.STILL_VALUE })
    }

    @Test
    fun streamKeepsFlowingWithFillerWhenRingGoesQuiet() {
        val t0 = aligned(System.currentTimeMillis() - 3_600_000L)
        val tl = ActigraphTimeline(maxLagMs = 6 * 60_000L)
        tl.start(t0)
        // No ring data at all for 10 minutes: values up to now - 6 min are released.
        val out = tl.drain(t0 + 10 * 60_000L)
        assertEquals(24, out.size)
        assertTrue(out.all { it == ActigraphTimeline.STILL_VALUE })
    }

    @Test
    fun duplicateEpochsAreIgnored() {
        val t0 = aligned(System.currentTimeMillis() - 3_600_000L)
        val tl = ActigraphTimeline()
        tl.start(t0)
        val e = epoch(t0, intArrayOf(1, 1, 1, 1, 1))
        tl.ingest(listOf(e), t0 + epochMs)
        tl.ingest(listOf(e), t0 + epochMs)
        assertEquals(15, tl.drain(t0 + epochMs).size)
    }

    @Test
    fun fourHourRingClockOffsetIsCorrected() {
        val now = aligned(System.currentTimeMillis() - 600_000L)
        val tl = ActigraphTimeline()
        tl.start(now - 2 * epochMs)
        // Ring stamps its records 4 h early (the other documented counter anchor).
        val shifted = (-2 until 0).map { epoch(now + it * epochMs - 4 * 3_600_000L, intArrayOf(1, 1, 1, 1, 1)) }
        tl.ingest(shifted, now + 30_000)
        assertEquals(4 * 3_600_000L, tl.clockOffsetMs)
        assertEquals(30, tl.drain(now + 30_000).size)
    }

    @Test
    fun smallClockSkewIsNotMistakenForAnOffset() {
        val now = aligned(System.currentTimeMillis() - 600_000L)
        val tl = ActigraphTimeline()
        tl.start(now - epochMs)
        tl.ingest(listOf(epoch(now - epochMs, intArrayOf(1, 1, 1, 1, 1))), now + 200_000)
        assertEquals(0L, tl.clockOffsetMs)
    }

    @Test
    fun startLeadPlaysRecentHistoryImmediately() {
        val now = aligned(System.currentTimeMillis() - 600_000L)
        val tl = ActigraphTimeline()
        tl.start(now, leadMs = 5 * 60_000L)
        // History already stored before tracking started covers the last 5 minutes.
        val stored = (-2 until 0).map { epoch(now + it * epochMs, intArrayOf(1, 1, 31, 1, 1)) }
        tl.ingest(stored, now, allowCalibration = false)
        val out = tl.drain(now)
        assertEquals("5 min of slots right away", 30, out.size)
        assertTrue("real movement is in there", out.any { it > ActigraphTimeline.STILL_VALUE })
    }

    @Test
    fun seededHistoryNeverCalibratesTheClock() {
        val now = aligned(System.currentTimeMillis() - 600_000L)
        val tl = ActigraphTimeline()
        tl.start(now)
        tl.ingest(listOf(epoch(now - 4 * 3_600_000L - epochMs, intArrayOf(1, 1, 1, 1, 1))), now, allowCalibration = false)
        assertEquals(0L, tl.clockOffsetMs)
    }

    @Test
    fun onlyTheDocumentedFourHourOffsetIsCorrected() {
        val now = aligned(System.currentTimeMillis() - 600_000L)
        val tl = ActigraphTimeline()
        tl.start(now)
        // Stale data ~2 h old must not be mistaken for a clock offset.
        tl.ingest(listOf(epoch(now - 2 * 3_600_000L - epochMs, intArrayOf(1, 1, 1, 1, 1))), now)
        assertEquals(0L, tl.clockOffsetMs)
    }

    @Test
    fun saaValuesArePositiveAndMonotonic() {
        assertTrue(ActigraphTimeline.toSaaValue(0) > 0f)
        assertTrue(ActigraphTimeline.toSaaValue(1) > ActigraphTimeline.toSaaValue(0))
        assertTrue(ActigraphTimeline.toSaaValue(50) > ActigraphTimeline.toSaaValue(5))
        assertEquals(3.5f, ActigraphTimeline.toSaaValue(10_000), 0f)
    }

    @Test
    fun macCandidatePrefersTheOneMatchingTheAdvertisedName() {
        val address = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        val sysId = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0xA1.toByte(), 0xB2.toByte())
        val c = RingAuth.macCandidates(address, sysId, "RingConn Gen2-A1B2")
        assertArrayEquals(sysId, c.first())
        assertEquals(2, c.size)
        assertArrayEquals(address, RingAuth.macCandidates(address, address.copyOf(), "RingConn").single())
    }

    @Test
    fun syncOpenAsksForEverythingUpToNow() {
        val cmd = RingProtocol.createSyncUpToNowCommand(RingProtocol.CHANNEL_SLEEP)
        val cursor = ((cmd[2].toLong() and 0xFF) shl 24) or ((cmd[3].toLong() and 0xFF) shl 16) or
            ((cmd[4].toLong() and 0xFF) shl 8) or (cmd[5].toLong() and 0xFF)
        val nowCursor = System.currentTimeMillis() / 1000 - RingProtocol.SYNC_EPOCH
        assertTrue(kotlin.math.abs(nowCursor - cursor) < 5)
    }
}
