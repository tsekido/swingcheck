package jp.co.updates.swingcheck.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PreprocessTest {
    private fun frame(ts: Long, x: Float, vis: Float = 1f, y: Float = 0.5f): PoseFrame =
        PoseFrame(ts, List(Joint.COUNT) { Landmark(x, y, 0f, vis) })

    @Test
    fun missingJointsAreLinearlyInterpolatedInTime() {
        val frames = listOf(
            frame(0, 0.2f), frame(100, 0.0f, vis = 0.1f), frame(300, 0.0f, vis = 0.1f), frame(400, 0.6f),
        )
        val seq = PoseSequence(frames, 1000, 1000)
        // 平滑化の影響を避けるため、ほぼ素通しのパラメータにする
        val cfg = SmoothingConfig(minCutoff = 1e6, beta = 0.0, dCutoff = 1e6, wristBeta = 0.0)
        val out = Preprocessor.preprocess(seq, config = cfg)
        assertEquals(200.0, out.poses[0].xs[0], 1e-3)
        assertEquals(300.0, out.poses[1].xs[0], 1e-3) // 200 + (600-200) * 100/400
        assertEquals(500.0, out.poses[2].xs[0], 1e-3)
        assertEquals(600.0, out.poses[3].xs[0], 1e-3)
    }

    @Test
    fun edgeMissingUsesNearestValue() {
        val frames = listOf(frame(0, 0.9f, vis = 0.0f), frame(100, 0.3f), frame(200, 0.3f, vis = 0.0f))
        val cfg = SmoothingConfig(minCutoff = 1e6, beta = 0.0, dCutoff = 1e6, wristBeta = 0.0)
        val out = Preprocessor.preprocess(PoseSequence(frames, 1000, 1000), config = cfg)
        assertEquals(300.0, out.poses[0].xs[5], 1e-3)
        assertEquals(300.0, out.poses[2].xs[5], 1e-3)
    }

    @Test
    fun coordinatesAreScaledToPixelsByWidthAndHeight() {
        val f = frame(0, 0.5f, y = 0.25f)
        val pt = f.landmarks[0].toPixel(1080, 1920)
        assertEquals(540.0, pt.x, 1e-6)
        assertEquals(480.0, pt.y, 1e-6)
    }

    @Test
    fun oneEuroFilterReducesJitterButFollowsFastMotion() {
        val rnd = java.util.Random(1)
        val f = OneEuroFilter(1.0, 0.05, 1.0)
        var errStill = 0.0
        var rawErrStill = 0.0
        for (i in 0 until 300) {
            val noisy = 100.0 + rnd.nextGaussian() * 2.0
            val y = f.filter(i / 60.0, noisy)
            if (i >= 60) {
                errStill += Math.abs(y - 100.0)
                rawErrStill += Math.abs(noisy - 100.0)
            }
        }
        assertTrue(errStill < rawErrStill * 0.5, "still: filtered=$errStill raw=$rawErrStill")

        val g = OneEuroFilter(1.0, 0.3, 1.0)
        var last = 0.0
        for (i in 0 until 60) last = g.filter(i / 60.0, i * 20.0) // 1200 px/s の等速
        assertTrue(Math.abs(last - 59 * 20.0) < 60.0, "fast motion lag too large: $last")
    }

    @Test
    fun leftHandedMirrorFlipsXAndSwapsSides() {
        val lms = List(Joint.COUNT) { i -> Landmark(if (i == Joint.LEFT_SHOULDER) 0.7f else 0.3f, 0.5f, 0f, 1f) }
        val seq = PoseSequence(listOf(PoseFrame(0, lms)), 100, 100)
        val mirrored = Handedness.LEFT.toRightHanded(seq)
        // 元の左肩は、反転後は右肩になり、x は 1 - 0.7
        assertEquals(0.3f, mirrored.frames[0].landmarks[Joint.RIGHT_SHOULDER].x, 1e-6f)
        assertEquals(0.7f, mirrored.frames[0].landmarks[Joint.LEFT_SHOULDER].x, 1e-6f)
        assertTrue(Handedness.RIGHT.toRightHanded(seq) === seq)
    }
}
