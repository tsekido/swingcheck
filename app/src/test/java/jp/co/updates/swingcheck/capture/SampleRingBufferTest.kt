package jp.co.updates.swingcheck.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SampleRingBufferTest {
    private val frameUs = 10_000L // 100fps
    private val gop = 50 // 0.5 秒

    private fun sample(i: Int, bytes: Int = 100) =
        EncodedSample(ByteArray(bytes), i * frameUs, isKeyFrame = i % gop == 0)

    private fun fill(buffer: SampleRingBuffer, from: Int, until: Int, bytes: Int = 100) {
        for (i in from until until) buffer.add(sample(i, bytes))
    }

    @Test
    fun dropsFramesUntilFirstKeyFrame() {
        val b = SampleRingBuffer(6_000_000, 1L shl 30)
        b.add(sample(1))
        b.add(sample(2))
        assertEquals(0, b.sampleCount)
        b.add(sample(50))
        assertEquals(1, b.sampleCount)
        assertEquals(50 * frameUs, b.oldestPtsUs)
    }

    @Test
    fun keepsAtLeastMaxDurationAndStartsAtKeyFrame() {
        val b = SampleRingBuffer(6_000_000, 1L shl 30)
        fill(b, 0, 2000) // 20 秒
        val oldest = b.oldestPtsUs!!
        val latest = b.latestPtsUs!!
        assertTrue((latest - oldest) >= 6_000_000, "span=${latest - oldest}")
        assertTrue((latest - oldest) < 6_000_000 + 500_000 + frameUs, "span=${latest - oldest}")
        assertEquals(0, (oldest / frameUs) % gop) // キーフレームから始まる
        val sel = b.select(oldest, latest)!!
        assertTrue(sel.samples.first().isKeyFrame)
    }

    @Test
    fun byteLimitDropsOldestGopsRegardlessOfDuration() {
        // 1 GOP = 50 コマ × 1000 バイト = 50,000 バイト。上限 120,000 バイト → 2 GOP まで
        val b = SampleRingBuffer(6_000_000, 120_000)
        fill(b, 0, 500, bytes = 1000)
        assertTrue(b.byteCount <= 120_000, "bytes=${b.byteCount}")
        assertTrue(b.sampleCount >= 50)
        assertTrue(b.select(0, Long.MAX_VALUE)!!.samples.first().isKeyFrame)
    }

    @Test
    fun byteLimitWithSingleGopClearsAndWaitsForNextKeyFrame() {
        val b = SampleRingBuffer(6_000_000, 10_000)
        fill(b, 0, 150, bytes = 1000) // 1 GOP で 50,000 バイト > 上限
        // 破棄された直後のキーフレームではないコマは入れない
        assertTrue(b.byteCount <= 10_000)
        b.select(0, Long.MAX_VALUE)?.let { assertTrue(it.samples.first().isKeyFrame) }
    }

    @Test
    fun selectStartsAtNearestKeyFrameAtOrBeforeStart() {
        val b = SampleRingBuffer(60_000_000, 1L shl 30)
        fill(b, 0, 300) // 3 秒、キーフレームは 0, 50, 100, ...
        val sel = b.select(startUs = 120 * frameUs, endUs = 220 * frameUs)!!
        assertEquals(100 * frameUs, sel.firstPtsUs) // 120 より前で最も近いキーフレームは 100
        assertEquals(220 * frameUs, sel.samples.last().ptsUs)
        assertFalse(sel.startClamped)
        assertTrue(sel.coversEnd)
        // ちょうどキーフレームの時刻
        assertEquals(100 * frameUs, b.select(100 * frameUs, 150 * frameUs)!!.firstPtsUs)
    }

    @Test
    fun selectClampsStartToBufferHead() {
        val b = SampleRingBuffer(60_000_000, 1L shl 30)
        fill(b, 50, 300)
        val sel = b.select(startUs = -500_000, endUs = 200 * frameUs)!!
        assertEquals(50 * frameUs, sel.firstPtsUs)
        assertTrue(sel.startClamped)
    }

    @Test
    fun selectEndBeyondBufferReportsNotCovered() {
        val b = SampleRingBuffer(60_000_000, 1L shl 30)
        fill(b, 0, 100)
        val sel = b.select(0, 500 * frameUs)!!
        assertEquals(99 * frameUs, sel.samples.last().ptsUs)
        assertFalse(sel.coversEnd)
    }

    @Test
    fun selectOnEmptyIsNull() {
        assertNull(SampleRingBuffer(1, 1).select(0, 10))
    }

    @Test
    fun outputTimesStartAtZeroAndFpsIsMeasured() {
        val b = SampleRingBuffer(60_000_000, 1L shl 30)
        fill(b, 0, 400)
        val sel = b.select(240 * frameUs, 350 * frameUs)!!
        assertEquals(200 * frameUs, sel.firstPtsUs)
        assertEquals(0, sel.outputPtsUs(sel.samples.first()))
        assertEquals(150 * frameUs, sel.outputPtsUs(sel.samples.last()))
        assertEquals(100f, sel.measuredFps()!!, 0.01f)
    }

    @Test
    fun measuredFpsNullForSingleSample() {
        val b = SampleRingBuffer(60_000_000, 1L shl 30)
        b.add(sample(0))
        assertNull(b.select(0, 0)!!.measuredFps())
    }

    @Test
    fun selectionIsSnapshotUnaffectedByLaterEviction() {
        val b = SampleRingBuffer(1_000_000, 1L shl 30)
        fill(b, 0, 300)
        val sel = b.select(b.oldestPtsUs!!, b.latestPtsUs!!)!!
        val size = sel.samples.size
        fill(b, 300, 1000)
        assertEquals(size, sel.samples.size)
    }
}
