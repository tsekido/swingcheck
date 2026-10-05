package jp.co.updates.swingcheck.display

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SeekMathTest {
    @Test
    fun fractionAndFrameAreInverse() {
        val n = 1000
        for (f in listOf(0, 1, 137, 500, 998, 999)) {
            assertEquals(f, SeekMath.frameAtFraction(SeekMath.fractionOfFrame(f, n), n))
        }
    }

    @Test
    fun fractionClampsAndHandlesTinyCounts() {
        assertEquals(0, SeekMath.frameAtFraction(-0.5f, 100))
        assertEquals(99, SeekMath.frameAtFraction(1.5f, 100))
        assertEquals(0, SeekMath.frameAtFraction(Float.NaN, 100))
        assertEquals(0, SeekMath.frameAtFraction(0.7f, 1))
        assertEquals(0, SeekMath.frameAtFraction(0.7f, 0))
        assertEquals(0f, SeekMath.fractionOfFrame(0, 1))
        assertEquals(1f, SeekMath.fractionOfFrame(99, 100))
        assertEquals(1f, SeekMath.fractionOfFrame(500, 100)) // 範囲外は端に丸める
    }

    @Test
    fun xToFrameUsesTrackGeometry() {
        // トラック：x = 20 から幅 1000、101 コマ → 10px ごとに 1 コマ
        assertEquals(0, SeekMath.frameAtX(0f, 20f, 1000f, 101))
        assertEquals(0, SeekMath.frameAtX(20f, 20f, 1000f, 101))
        assertEquals(50, SeekMath.frameAtX(520f, 20f, 1000f, 101))
        assertEquals(100, SeekMath.frameAtX(5000f, 20f, 1000f, 101))
        assertEquals(51, SeekMath.frameAtX(526f, 20f, 1000f, 101)) // 四捨五入
        assertEquals(0, SeekMath.frameAtX(500f, 20f, 0f, 101))
        assertEquals(520f, SeekMath.xOfFrame(50, 20f, 1000f, 101))
    }

    @Test
    fun stepStopsAtEnds() {
        assertEquals(5, SeekMath.step(4, 1, 10))
        assertEquals(0, SeekMath.step(0, -1, 10))
        assertEquals(9, SeekMath.step(9, 1, 10))
        assertEquals(0, SeekMath.step(3, 1, 0))
    }

    @Test
    fun frameAtPositionPicksLastFrameNotAfterPosition() {
        // 240fps 相当（4.17ms 間隔を切り捨てた時刻）
        val ts = LongArray(10) { (it * 1000L / 240) }
        assertEquals(0, SeekMath.frameAtPosition(ts, -5))
        assertEquals(0, SeekMath.frameAtPosition(ts, 0))
        assertEquals(0, SeekMath.frameAtPosition(ts, 3))
        assertEquals(1, SeekMath.frameAtPosition(ts, ts[1]))
        assertEquals(1, SeekMath.frameAtPosition(ts, ts[2] - 1))
        assertEquals(9, SeekMath.frameAtPosition(ts, 10_000))
        assertEquals(0, SeekMath.frameAtPosition(LongArray(0), 100))
    }

    @Test
    fun hitTestPicksNearestWithinRadius() {
        val xs = listOf(100f, 200f, 210f)
        assertEquals(0, SeekMath.hitTest(xs, 95f, 20f))
        assertNull(SeekMath.hitTest(xs, 150f, 20f))
        assertEquals(1, SeekMath.hitTest(xs, 203f, 20f))
        assertEquals(2, SeekMath.hitTest(xs, 209f, 20f))
    }

    @Test
    fun hitTestTieGoesToPreferredThenLowerIndex() {
        val xs = listOf(100f, 100f, 100f)
        assertEquals(0, SeekMath.hitTest(xs, 100f, 20f))
        assertEquals(2, SeekMath.hitTest(xs, 100f, 20f, preferred = 2))
    }
}
