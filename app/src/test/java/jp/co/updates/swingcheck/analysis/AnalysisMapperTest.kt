package jp.co.updates.swingcheck.analysis

import jp.co.updates.swingcheck.core.Joint
import jp.co.updates.swingcheck.core.PixelPose
import jp.co.updates.swingcheck.core.PixelSequence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AnalysisMapperTest {
    @Test
    fun pixelPoseConvertsBackToNormalizedPoseFrame() {
        val xs = DoubleArray(Joint.COUNT) { 540.0 }
        val ys = DoubleArray(Joint.COUNT) { 480.0 }
        val vis = DoubleArray(Joint.COUNT) { 0.75 }
        val pose = PixelPose(120, xs, ys, vis)
        val frame = AnalysisMapper.toPoseFrame(pose, PixelSequence(listOf(pose), 1080, 1920))
        assertEquals(120L, frame.timestampMs)
        assertEquals(Joint.COUNT, frame.landmarks.size)
        assertEquals(0.5f, frame.landmarks[0].x)
        assertEquals(0.25f, frame.landmarks[0].y)
        assertEquals(0.75f, frame.landmarks[0].visibility)
    }

    @Test
    fun timestampsStrictlyIncrease() {
        assertEquals(10L, Timestamps.nextAfter(5, 10))
        assertEquals(6L, Timestamps.nextAfter(5, 5))
        assertEquals(6L, Timestamps.nextAfter(5, 2))
        assertEquals(0L, Timestamps.nextAfter(-1, 0))
    }
}
