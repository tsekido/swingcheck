package jp.co.updates.swingcheck.capture

import jp.co.updates.swingcheck.core.SwingClip
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClipCoordinatorTest {
    private val frameUs = 10_000L
    private val base = 5_000_000_000L // カメラの時刻は 0 始まりではない

    private fun sample(i: Int) = EncodedSample(ByteArray(10), base + i * frameUs, i % 50 == 0)

    private class Rig {
        val buffer = SampleRingBuffer(6_000_000, 1L shl 30)
        val origin = TimeOrigin()
        val coordinator = ClipCoordinator(buffer, origin)
    }

    private fun Rig.feed(from: Int, until: Int, base: Long, frameUs: Long): List<ClipSelection> {
        val out = ArrayList<ClipSelection>()
        for (i in from until until) {
            val s = EncodedSample(ByteArray(10), base + i * frameUs, i % 50 == 0)
            origin.ensure(s.ptsUs)
            buffer.add(s)
            out += coordinator.onSampleAdded()
        }
        return out
    }

    @Test
    fun waitsUntilEndMsThenReturnsClip() {
        val r = Rig()
        r.feed(0, 300, base, frameUs) // 0〜2.99 秒
        // 検出：[1.2 秒, 3.5 秒]。まだ 3.5 秒に届いていない
        assertTrue(r.coordinator.request(SwingClip(1200, 3500, false)).isEmpty())
        val ready = r.feed(300, 400, base, frameUs) // 3.0〜3.99 秒
        assertEquals(1, ready.size)
        val sel = ready[0]
        assertEquals(base + 1000 * 1000, sel.firstPtsUs) // 1.2 秒より前で最も近いキーフレームは 1.0 秒
        assertEquals(base + 3500 * 1000, sel.samples.last().ptsUs) // 3.5 秒のコマまで
        assertTrue(sel.coversEnd)
        // 次のコマでは再度出ない
        assertTrue(r.feed(400, 410, base, frameUs).isEmpty())
    }

    @Test
    fun returnsImmediatelyWhenBufferAlreadyCoversEnd() {
        val r = Rig()
        r.feed(0, 400, base, frameUs)
        val ready = r.coordinator.request(SwingClip(1200, 3000, false))
        assertEquals(1, ready.size)
    }

    @Test
    fun negativeStartClampsToBufferHead() {
        val r = Rig()
        r.feed(0, 400, base, frameUs)
        val ready = r.coordinator.request(SwingClip(-700, 1000, false))
        assertEquals(1, ready.size)
        assertEquals(base, ready[0].firstPtsUs)
        assertTrue(ready[0].startClamped)
    }

    @Test
    fun startBeforeEvictedHeadClampsToCurrentHead() {
        val r = Rig()
        r.feed(0, 2000, base, frameUs) // 20 秒。先頭は 13.5 秒付近まで捨てられている
        val head = r.buffer.oldestPtsUs!!
        val ready = r.coordinator.request(SwingClip(1000, 19000, false))
        assertEquals(1, ready.size)
        assertEquals(head, ready[0].firstPtsUs)
        assertTrue(ready[0].startClamped)
    }

    @Test
    fun flushReturnsPendingWithWhatIsAvailable() {
        val r = Rig()
        r.feed(0, 300, base, frameUs)
        assertTrue(r.coordinator.request(SwingClip(1200, 9000, false)).isEmpty())
        val flushed = r.coordinator.flush()
        assertEquals(1, flushed.size)
        assertEquals(false, flushed[0].coversEnd)
        assertTrue(r.coordinator.flush().isEmpty())
    }

    @Test
    fun twoPendingClipsBothReturned() {
        val r = Rig()
        r.feed(0, 100, base, frameUs)
        r.coordinator.request(SwingClip(0, 1500, false))
        r.coordinator.request(SwingClip(500, 2500, false))
        val ready = r.feed(100, 300, base, frameUs)
        assertEquals(2, ready.size)
    }

    @Test
    fun timeOriginMapsBetweenMsAndUs() {
        val o = TimeOrigin()
        assertEquals(0, o.toMs(base))
        assertEquals(1500, o.toMs(base + 1_500_999))
        assertEquals(base + 2_000_000, o.toUs(2000))
        assertEquals(-3, o.toMs(base - 2_001)) // 原点より前（床の除算）
    }
}
